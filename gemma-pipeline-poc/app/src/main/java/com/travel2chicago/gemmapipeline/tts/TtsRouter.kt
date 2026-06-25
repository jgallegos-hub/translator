package com.travel2chicago.gemmapipeline.tts

import android.util.Log
import com.travel2chicago.gemmapipeline.audio.AudioEvent
import com.travel2chicago.gemmapipeline.audio.AudioEventBus
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

private const val TAG = "TtsRouter"

/**
 * Bridges Gemma's [AudioEvent.TranslationReady] output to
 * [KokoroTtsEngine.synthesize] and re-emits the resulting PCM as
 * [AudioEvent.TtsAudioReady] for the audio player.
 *
 * Mirror of [com.travel2chicago.gemmapipeline.ast.AstChunkRouter] — same
 * bounded-channel + DROP_OLDEST + single-threaded-consumer + graceful-drain
 * patterns. The two routers run in parallel; each owns one ONNX/LiteRT
 * inference path and one queue.
 */
class TtsRouter(
    private val bus: AudioEventBus,
    private val engine: KokoroTtsEngine,
    private val config: TtsConfig,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val channel: Channel<AudioEvent.TranslationReady> =
        Channel(capacity = config.queueCapacity)

    @Volatile private var producerJob: Job? = null
    @Volatile private var consumerJob: Job? = null

    private val queueDepth = AtomicInteger(0)
    private val synthesizedCount = AtomicLong(0)
    private val droppedCount = AtomicLong(0)
    private val totalLatencyMs = AtomicLong(0)
    private val errorCount = AtomicLong(0)

    val isRunning: Boolean
        get() = producerJob?.isActive == true && consumerJob?.isActive == true

    val queueSize: Int get() = queueDepth.get()
    val totalSynthesized: Long get() = synthesizedCount.get()
    val totalDropped: Long get() = droppedCount.get()
    val totalErrors: Long get() = errorCount.get()
    val averageLatencyMs: Double get() {
        val n = synthesizedCount.get()
        return if (n > 0) totalLatencyMs.get().toDouble() / n else 0.0
    }

    fun start(scope: CoroutineScope) {
        if (isRunning) {
            Log.w(TAG, "start: already running, ignoring")
            return
        }
        Log.i(TAG, "Starting TTS router (queueCapacity=${config.queueCapacity}, voice=${config.voice})")
        bus.emit(AudioEvent.EngineStatus(
            "TTS router subscribed (voice=${config.voice}, queueCap=${config.queueCapacity})"))

        producerJob = scope.launch(Dispatchers.Default) {
            bus.events
                .filterIsInstance<AudioEvent.TranslationReady>()
                .collect { tr -> submitTranslation(tr) }
        }

        consumerJob = scope.launch(ioDispatcher) {
            channel.consumeEach { tr ->
                queueDepth.decrementAndGet()
                processTranslation(tr)
            }
        }
    }

    /**
     * Hard-cancel. Anything in flight is lost; the channel is closed and
     * both jobs are cancelled. Safe to call from non-suspend contexts.
     */
    fun cancel() {
        producerJob?.cancel()
        consumerJob?.cancel()
        producerJob = null
        consumerJob = null
        channel.close()
        Log.i(TAG, "TTS router cancelled. synthesized=$totalSynthesized dropped=$totalDropped errors=$totalErrors")
    }

    /**
     * Graceful shutdown — stop accepting new translations, then wait for the
     * consumer to drain the queue. Falls back to hard cancel if the timeout
     * fires. Timeout default 12 s ≈ queueCapacity(4) × ~1.5–3 s/synth + slack.
     */
    suspend fun stopGracefully(drainTimeoutMs: Long = 12_000L): Int {
        producerJob?.cancel()
        producerJob = null
        channel.close()

        val before = synthesizedCount.get()
        val consumer = consumerJob
        val drained = withTimeoutOrNull(drainTimeoutMs) {
            consumer?.join()
            true
        }
        if (drained == null) {
            Log.w(TAG, "stopGracefully: drain timed out after ${drainTimeoutMs}ms — hard-cancelling consumer")
            consumer?.cancel()
        }
        consumerJob = null

        val completed = (synthesizedCount.get() - before).toInt()
        Log.i(TAG, "TTS router stopped gracefully. drained=$completed timedOut=${drained == null} " +
            "totalSynthesized=$totalSynthesized totalDropped=$totalDropped totalErrors=$totalErrors")
        return completed
    }

    private fun submitTranslation(tr: AudioEvent.TranslationReady) {
        val sent = channel.trySend(tr)
        if (sent.isSuccess) {
            queueDepth.incrementAndGet()
            return
        }
        val popped = channel.tryReceive()
        if (popped.isSuccess) {
            queueDepth.decrementAndGet()
            droppedCount.incrementAndGet()
        }
        val retry = channel.trySend(tr)
        if (retry.isSuccess) {
            queueDepth.incrementAndGet()
            if (popped.isSuccess) {
                bus.emit(AudioEvent.EngineStatus(
                    "TTS queue full, dropped oldest (totalDropped=${droppedCount.get()})"))
            }
        } else {
            droppedCount.incrementAndGet()
            Log.w(TAG, "submitTranslation: trySend failed twice — dropping new translation")
        }
    }

    private suspend fun processTranslation(tr: AudioEvent.TranslationReady) {
        val text = tr.text.trim()
        if (text.isEmpty()) {
            Log.w(TAG, "Empty translation, skipping")
            return
        }
        val result = try {
            engine.synthesize(text, config.voice)
        } catch (t: Throwable) {
            errorCount.incrementAndGet()
            Log.e(TAG, "Kokoro synthesize() failed for text='${text.take(60)}'", t)
            emitError(
                "Kokoro synthesize failed: ${t.javaClass.simpleName}: ${t.message}",
                tr,
            )
            return
        }

        synthesizedCount.incrementAndGet()
        totalLatencyMs.addAndGet(result.latencyMs)

        bus.emit(
            AudioEvent.TtsAudioReady(
                samples = result.pcm,
                sampleRate = result.sampleRate,
                sourceText = text,
                sourceTranslationTimestampNs = tr.timestampNs,
                latencyMs = result.latencyMs,
                timestampNs = System.nanoTime(),
            ),
        )
    }

    private suspend fun emitError(message: String, tr: AudioEvent.TranslationReady) {
        withContext(NonCancellable) {
            bus.emit(
                AudioEvent.TtsError(
                    message = message,
                    sourceText = tr.text,
                    sourceTranslationTimestampNs = tr.timestampNs,
                    timestampNs = System.nanoTime(),
                ),
            )
        }
    }
}
