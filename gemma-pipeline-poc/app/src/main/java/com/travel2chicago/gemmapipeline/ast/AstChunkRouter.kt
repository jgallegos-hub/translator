package com.travel2chicago.gemmapipeline.ast

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

private const val TAG = "AstChunkRouter"

/**
 * Bridges [AudioChunker]'s output to [GemmaAstEngine].
 *
 * Topology:
 *   - PRODUCER (Dispatchers.Default): subscribes to `bus.events` and filters
 *     [AudioEvent.ChunkReady]. Submits each chunk to a bounded internal
 *     channel with DROP_OLDEST semantics. Because Gemma's inference is much
 *     slower (~2 s) than the chunker can emit (every ~3–6 s of speech, but
 *     possibly burstier), the queue exists to absorb short bursts while
 *     preserving the most recent context — older chunks get dropped first.
 *
 *   - CONSUMER (ioDispatcher, default Dispatchers.IO, single-threaded by
 *     design): pulls one chunk at a time, builds a WAV via [WavBuilder],
 *     calls [GemmaAstEngine.translate], and publishes either
 *     [AudioEvent.TranslationReady] or [AudioEvent.AstError] back to the bus.
 *
 * Single-threaded consumption is intentional: one `Conversation` runs one
 * inference at a time. Parallelism would require a pool of engines, which
 * is out of scope for the POC.
 *
 * @param sampleRate samples-per-second of the chunks coming from the chunker.
 *   The chunker always produces 16 kHz mono — exposed as a constructor arg
 *   only so tests can construct chunks at any rate without ceremony.
 */
class AstChunkRouter(
    private val bus: AudioEventBus,
    private val engine: GemmaAstEngine,
    private val config: AstConfig,
    private val sampleRate: Int = 16_000,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val channel: Channel<AudioEvent.ChunkReady> =
        Channel(capacity = config.queueCapacity)

    @Volatile private var producerJob: Job? = null
    @Volatile private var consumerJob: Job? = null

    // Metrics — atomic so the UI thread can read them without locking.
    private val queueDepth = AtomicInteger(0)
    private val translatedCount = AtomicLong(0)
    private val droppedCount = AtomicLong(0)
    private val totalLatencyMs = AtomicLong(0)
    private val errorCount = AtomicLong(0)
    private val lowEnergyDiscardCount = AtomicLong(0)
    private val metaTextDiscardCount = AtomicLong(0)

    val isRunning: Boolean
        get() = producerJob?.isActive == true && consumerJob?.isActive == true

    val queueSize: Int get() = queueDepth.get()
    val totalTranslated: Long get() = translatedCount.get()
    val totalDropped: Long get() = droppedCount.get()
    val totalErrors: Long get() = errorCount.get()
    /** Chunks skipped BEFORE Gemma because their RMS was under [AstConfig.rmsThreshold]. */
    val totalDiscardedLowEnergy: Long get() = lowEnergyDiscardCount.get()
    /** Translations dropped AFTER Gemma because the reply matched a meta-text pattern. */
    val totalDiscardedMeta: Long get() = metaTextDiscardCount.get()
    val averageLatencyMs: Double get() {
        val n = translatedCount.get()
        return if (n > 0) totalLatencyMs.get().toDouble() / n else 0.0
    }

    fun start(scope: CoroutineScope) {
        if (isRunning) {
            Log.w(TAG, "start: already running, ignoring")
            return
        }
        Log.i(TAG, "Starting router (queueCapacity=${config.queueCapacity}, backend=${engine.backendUsed})")
        bus.emit(AudioEvent.EngineStatus(
            "AST router subscribed (backend=${engine.backendUsed}, queueCap=${config.queueCapacity})"))

        producerJob = scope.launch(Dispatchers.Default) {
            bus.events
                .filterIsInstance<AudioEvent.ChunkReady>()
                .collect { chunk -> submitChunk(chunk) }
        }

        consumerJob = scope.launch(ioDispatcher) {
            // consumeEach handles cancellation + cleanup correctly.
            channel.consumeEach { chunk ->
                queueDepth.decrementAndGet()
                processChunk(chunk)
            }
        }
    }

    /**
     * Hard-cancel. Both producer and consumer jobs are cancelled immediately;
     * any chunk queued or in-flight is lost. Safe to call from non-suspend
     * contexts like `ViewModel.onCleared()` or tests that just need to tear
     * down quickly.
     *
     * Use [stopGracefully] from a coroutine when you want pending chunks to
     * finish translating before shutdown (e.g. user-initiated Stop button).
     */
    fun cancel() {
        producerJob?.cancel()
        consumerJob?.cancel()
        producerJob = null
        consumerJob = null
        // Close the channel so any items still buffered are released and
        // the consumer's consumeEach exits cleanly. The router is one-shot:
        // the caller (ViewModel) constructs a fresh router on each start.
        channel.close()
        Log.i(TAG, "Router cancelled. translated=$totalTranslated dropped=$totalDropped errors=$totalErrors")
    }

    /**
     * Graceful shutdown. Stops accepting new chunks, then waits for the
     * consumer to drain everything already in the channel before returning.
     *
     *  1. Cancel the producer so no further `ChunkReady` events get enqueued.
     *  2. Close the channel — `consumeEach` exits naturally once the buffer
     *     is empty.
     *  3. Wait up to [drainTimeoutMs] for the consumer job to complete.
     *  4. If the timeout fires (Gemma stuck on a single inference), fall
     *     back to hard cancel so the call still returns deterministically.
     *
     * @param drainTimeoutMs maximum time to wait for queued + in-flight
     *   chunks to finish. Default 8000 ms ≈ queueCapacity(4) × ~2 s/inference.
     * @return number of translations that completed during the drain window
     *   (zero if the channel was already empty when called, or if the
     *   timeout fired before any chunk finished).
     */
    suspend fun stopGracefully(drainTimeoutMs: Long = 8_000L): Int {
        producerJob?.cancel()
        producerJob = null

        // Closing the channel signals "no more sends". consumeEach will
        // exit cleanly once the in-memory buffer is drained.
        channel.close()

        val before = translatedCount.get()
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

        val completedDuringDrain = (translatedCount.get() - before).toInt()
        Log.i(
            TAG,
            "Router stopped gracefully. drained=$completedDuringDrain timedOut=${drained == null} " +
                "totalTranslated=$totalTranslated totalDropped=$totalDropped totalErrors=$totalErrors",
        )
        return completedDuringDrain
    }

    /**
     * Single-producer DROP_OLDEST.
     *
     * Counter discipline:
     *   - Every successful [Channel.trySend] is paired with `queueDepth++`.
     *   - Every successful [Channel.tryReceive] is paired with `queueDepth--`
     *     — both the consumer's [consumeEach] (which we decrement in [start])
     *     and our manual pop here.
     *
     * The router is the only writer (sequential collect on Dispatchers.Default),
     * but the consumer can drain in parallel — so a pop can fail if the
     * consumer just emptied the channel. We retry once; if the retry also
     * fails (pathological — channel filled up again instantly) we count the
     * NEW chunk as the drop.
     */
    private fun submitChunk(chunk: AudioEvent.ChunkReady) {
        val sent = channel.trySend(chunk)
        if (sent.isSuccess) {
            queueDepth.incrementAndGet()
            return
        }
        // Channel reported full — drop the oldest manually.
        val popped = channel.tryReceive()
        if (popped.isSuccess) {
            queueDepth.decrementAndGet()
            droppedCount.incrementAndGet()
        }
        // Whether or not the pop succeeded, try the new chunk one more time.
        val retry = channel.trySend(chunk)
        if (retry.isSuccess) {
            queueDepth.incrementAndGet()
            if (popped.isSuccess) {
                bus.emit(AudioEvent.EngineStatus(
                    "AST queue full, dropped oldest (totalDropped=${droppedCount.get()})"))
            }
        } else {
            // Single producer guarantees this shouldn't happen unless the
            // consumer is contending hard. Count the new chunk as dropped.
            droppedCount.incrementAndGet()
            Log.w(TAG, "submitChunk: trySend failed twice — dropping new chunk")
        }
    }

    private suspend fun processChunk(chunk: AudioEvent.ChunkReady) {
        // Pre-filter: skip near-silent chunks BEFORE the expensive Gemma call.
        // See AstConfig.rmsThreshold for rationale.
        if (config.rmsThreshold > 0.0) {
            val rms = computeRms(chunk.samples)
            if (rms < config.rmsThreshold) {
                val n = lowEnergyDiscardCount.incrementAndGet()
                Log.i(
                    TAG,
                    "Chunk discarded: low RMS (${"%.1f".format(rms)} < ${config.rmsThreshold}) " +
                        "durMs=${chunk.durationMs} peak=${chunk.peak} totalLowEnergy=$n",
                )
                bus.emit(AudioEvent.EngineStatus(
                    "AST skipped low-energy chunk (rms=${"%.0f".format(rms)}, total=$n)"))
                return
            }
        }

        val wav = try {
            WavBuilder.build(chunk.samples, sampleRate = sampleRate, channels = 1)
        } catch (t: Throwable) {
            Log.e(TAG, "WAV build failed for chunk ts=${chunk.timestampNs}", t)
            emitError("WAV build failed: ${t.message}", chunk)
            return
        }

        val result = try {
            engine.translate(wav, config.prompt)
        } catch (t: Throwable) {
            // Don't propagate — one bad chunk shouldn't kill the consumer.
            // The next chunk gets a fresh attempt on the same engine.
            errorCount.incrementAndGet()
            Log.e(TAG, "Gemma translate() failed", t)
            emitError("Gemma translate failed: ${t.javaClass.simpleName}: ${t.message}", chunk)
            return
        }

        translatedCount.incrementAndGet()
        totalLatencyMs.addAndGet(result.latencyMs)

        // Post-filter: Gemma sometimes replies with meta-text on near-silent
        // chunks that slipped past the RMS gate. Kill the reply BEFORE it
        // reaches TtsRouter so Kokoro never speaks "please provide the audio".
        val matched = matchMetaPattern(result.text)
        if (matched != null) {
            val n = metaTextDiscardCount.incrementAndGet()
            Log.i(
                TAG,
                "Translation discarded: meta-text detected ('$matched') text='${result.text.take(80)}' " +
                    "totalMeta=$n",
            )
            bus.emit(AudioEvent.EngineStatus(
                "AST dropped meta-text reply ('$matched', total=$n)"))
            return
        }

        bus.emit(
            AudioEvent.TranslationReady(
                text = result.text,
                sourceChunkTimestampNs = chunk.timestampNs,
                sourceDurationMs = chunk.durationMs,
                sourcePeak = chunk.peak,
                latencyMs = result.latencyMs,
                timestampNs = System.nanoTime(),
            ),
        )
    }

    private fun matchMetaPattern(text: String): String? {
        if (config.metaTextPatterns.isEmpty()) return null
        val lower = text.trim().lowercase()
        if (lower.isEmpty()) return null
        return config.metaTextPatterns.firstOrNull { pat -> lower.contains(pat) }
    }

    /**
     * Per-sample RMS on int16 PCM. Uses `Int` accumulation up-front and
     * `Double` sums to avoid overflow on long chunks — a 6-second chunk at
     * 16 kHz is 96 000 samples, and `sum(s*s)` on peaks-near-32 767 comfortably
     * exceeds `Long.MAX_VALUE` if you accumulate in the wrong type.
     */
    private fun computeRms(samples: ShortArray): Double {
        if (samples.isEmpty()) return 0.0
        var sumSq = 0.0
        for (s in samples) {
            val v = s.toDouble()
            sumSq += v * v
        }
        return kotlin.math.sqrt(sumSq / samples.size)
    }

    private suspend fun emitError(message: String, chunk: AudioEvent.ChunkReady) {
        // Always emit even on cancellation so the UI gets a final signal.
        withContext(NonCancellable) {
            bus.emit(
                AudioEvent.AstError(
                    message = message,
                    sourceChunkTimestampNs = chunk.timestampNs,
                    timestampNs = System.nanoTime(),
                ),
            )
        }
    }
}
