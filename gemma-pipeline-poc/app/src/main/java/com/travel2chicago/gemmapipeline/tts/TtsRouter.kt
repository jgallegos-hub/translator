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
    /**
     * Fase 6 Stage B — when non-null AND [TtsConfig.streamingEnabled] is
     * `true`, the router drives the sink directly (per-sentence
     * `beginUtterance` / `play` / `endUtterance`) instead of emitting
     * `TtsAudioReady` for the ViewModel to play. When `null` OR streaming
     * is off, the router falls back to the Fase 5 bus-emit path — the
     * ViewModel picks up `TtsAudioReady` and calls `player.play()` itself.
     * Leaving this at the default `null` keeps every existing test's
     * bus-only assertions valid unchanged.
     *
     * When [TtsConfig.useFastMode] is on, the sink is also used to raise
     * the VAD-mute flag around each Android TTS `speak` call — the sink's
     * `play` is not called (there's no PCM to play; the OS handles audio).
     */
    private val player: TtsPlayerSink? = null,
    /**
     * Optional Android system TTS engine. When provided AND
     * [TtsConfig.useFastMode] is `true`, the router routes each translation
     * through [AndroidTtsEngine.speak] instead of [KokoroTtsEngine].
     * Default `null` preserves every existing test — Kokoro-only path.
     */
    private val androidEngine: AndroidTtsEngine? = null,
) {
    private val channel: Channel<AudioEvent.TranslationReady> =
        Channel(capacity = config.queueCapacity)

    @Volatile private var producerJob: Job? = null
    @Volatile private var consumerJob: Job? = null
    @Volatile private var playbackJob: Job? = null

    /**
     * Streaming path only: synthesis and playback are decoupled (2026-10-16).
     * The consumer synthesises and enqueues here; [playbackJob] drains it into
     * the sink. Before, `sink.play` blocked the consumer for the whole piece
     * (device r14: up to 7.3 s), so the next piece / utterance could not be
     * synthesised meanwhile — gaps between clauses and a 12 s queue wait.
     */
    private sealed interface PlayItem {
        data object Begin : PlayItem
        class Pcm(val pcm: ShortArray, val sampleRate: Int, val firstOfUtteranceTs: Long?, val enqueuedNs: Long) : PlayItem
        data object End : PlayItem
    }
    private val playChannel: Channel<PlayItem> = Channel(Channel.UNLIMITED)

    /**
     * Current Kokoro synthesis speed multiplier. Seeded from [config.speed] at
     * construction and mutated by [setSpeed] as the UI slider changes — read
     * on every synthesize call so speed changes apply to the NEXT sentence
     * without needing a router restart (unlike `voice` / `useFastMode` /
     * `streamingEnabled`, which change router behaviour more fundamentally
     * and warrant a rebuild).
     */
    @Volatile private var currentSpeed: Float = config.speed

    /**
     * Update the speed multiplier applied on subsequent synthesize calls.
     * Value is clamped to [TtsConfig.MIN_SPEED]..[TtsConfig.MAX_SPEED] to
     * match the UI slider bounds; ONNX-side clamping / rounding for
     * int32-typed `speed` inputs lives in [KokoroOnnxEngine.synthesizeOne].
     */
    fun setSpeed(newSpeed: Float) {
        currentSpeed = newSpeed.coerceIn(TtsConfig.MIN_SPEED, TtsConfig.MAX_SPEED)
    }

    private val queueDepth = AtomicInteger(0)
    private val synthesizedCount = AtomicLong(0)
    private val droppedCount = AtomicLong(0)
    private val totalLatencyMs = AtomicLong(0)
    private val errorCount = AtomicLong(0)
    /**
     * Wall-clock ms from the source `ChunkReady.timestampNs` (carried on
     * `TranslationReady.sourceChunkTimestampNs`) to the moment we handed
     * the FIRST PCM of the utterance to the sink / bus. Last-value
     * semantics. Zero = "no utterance played yet". Measures the full
     * mic-to-first-audio window, letting the UI compare Stage A / A+B / off
     * apples-to-apples.
     */
    private val firstAudioLatencyMsAtomic = AtomicLong(0)

    val isRunning: Boolean
        get() = producerJob?.isActive == true && consumerJob?.isActive == true

    val queueSize: Int get() = queueDepth.get()
    val totalSynthesized: Long get() = synthesizedCount.get()
    val totalDropped: Long get() = droppedCount.get()
    val totalErrors: Long get() = errorCount.get()
    /** ms from `ChunkReady.timestampNs` to first PCM handed to sink/bus. */
    val firstAudioLatencyMs: Long get() = firstAudioLatencyMsAtomic.get()
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

        val sink = player
        if (sink != null) playbackJob = scope.launch(ioDispatcher) { runPlayback(sink) }
    }

    /** Drains [playChannel] into the sink; keeps begin/end balanced even when cancelled. */
    private suspend fun runPlayback(sink: TtsPlayerSink) {
        var open = 0
        try {
            for (item in playChannel) {
                when (item) {
                    PlayItem.Begin -> { sink.beginUtterance(); open++ }
                    PlayItem.End -> if (open > 0) { sink.endUtterance(); open-- }
                    is PlayItem.Pcm -> {
                        val queuedMs = (System.nanoTime() - item.enqueuedNs) / 1_000_000
                        item.firstOfUtteranceTs?.let {
                            firstAudioLatencyMsAtomic.set((System.nanoTime() - it) / 1_000_000)
                        }
                        Log.i(TAG, "TTSDIAG playback: waitedMs=$queuedMs audioMs=${item.pcm.size * 1000L / item.sampleRate}")
                        sink.play(item.pcm)
                    }
                }
            }
        } finally {
            withContext(NonCancellable) { repeat(open) { sink.endUtterance() } }
        }
    }

    /**
     * Hard-cancel. Anything in flight is lost; the channel is closed and
     * both jobs are cancelled. Safe to call from non-suspend contexts.
     */
    fun cancel() {
        producerJob?.cancel()
        consumerJob?.cancel()
        playbackJob?.cancel()
        producerJob = null
        consumerJob = null
        playbackJob = null
        channel.close()
        playChannel.close()
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
        // Let already-synthesised audio finish playing (bounded by the same timeout).
        playChannel.close()
        val playback = playbackJob
        if (withTimeoutOrNull(drainTimeoutMs) { playback?.join(); true } == null) playback?.cancel()
        playbackJob = null

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

    /**
     * The `sourceChunkTimestampNs` of the utterance whose bookend is
     * currently open, or `null` if no utterance is in progress. Used by
     * the streaming path to detect utterance boundaries when multiple
     * per-sentence [AudioEvent.TranslationReady] events arrive for the
     * same source chunk (Fase 6 Stage A). Only touched from the single
     * consumer coroutine — no atomicity needed, but `@Volatile` keeps
     * writes visible if the router is later re-wired across dispatchers.
     */
    @Volatile private var openUtteranceTs: Long? = null

    private suspend fun processTranslation(tr: AudioEvent.TranslationReady) {
        // Latency instrumentation (2026-10-13): how long this translation sat
        // in the TTS queue before the single consumer picked it up.
        Log.i(TAG, "TTSDIAG start: waitMs=${(System.nanoTime() - tr.timestampNs) / 1_000_000} " +
            "queue=${queueDepth.get()} chars=${tr.text.length} isFinal=${tr.isFinal}")
        val text = tr.text.trim()
        if (text.isEmpty()) {
            // Eager-emit utterance closer: every sentence already went out.
            if (tr.isFinal && tr.sourceChunkTimestampNs == openUtteranceTs) {
                openUtteranceTs = null
                if (config.useFastMode && androidEngine != null) player?.endUtterance()
                else playChannel.send(PlayItem.End)
                return
            }
            Log.w(TAG, "Empty translation, skipping")
            return
        }
        // Fast-mode path takes precedence over Kokoro streaming — the two are
        // mutually exclusive (Android TTS speaks the whole utterance in one
        // call, no per-sentence handoff).
        val fast = androidEngine
        if (config.useFastMode && fast != null) {
            processTranslationFast(tr, text, fast)
            return
        }
        if (config.streamingEnabled && player != null) {
            processTranslationStreaming(tr, text, player)
        } else {
            processTranslationOneShot(tr, text)
        }
    }

    /**
     * Android system TTS path. The OS renders audio directly to the system
     * output (typically the paired A2DP speaker), so we don't push PCM to
     * [TtsPlayerSink]. We still open/close the utterance bookend on the
     * sink (when available) so [TtsAudioPlayer]'s depth counter raises the
     * shared VAD-mute flag for the duration of playback — otherwise the
     * mic would re-capture the speaker output as new "speech".
     *
     * `firstAudioLatencyMs` is anchored at Android's `onStart` callback,
     * which fires when the OS starts playing samples (the closest signal
     * available for "user hears audio now").
     */
    private suspend fun processTranslationFast(
        tr: AudioEvent.TranslationReady,
        text: String,
        fast: AndroidTtsEngine,
    ) {
        // Streaming from Gemma (Fase 6 Stage A) can produce multiple
        // TranslationReady events per chunk — for fast mode we still want a
        // single VAD-mute window that spans all sentences of the same source
        // chunk. Bookend logic mirrors the streaming Kokoro path.
        val newUtterance = tr.sourceChunkTimestampNs != openUtteranceTs
        val sink = player
        if (newUtterance) {
            val prev = openUtteranceTs
            if (prev != null && sink != null) {
                Log.w(TAG, "Fast: utterance ts=$prev never terminated; endUtterance defensively")
                sink.endUtterance()
            }
            openUtteranceTs = tr.sourceChunkTimestampNs
            sink?.beginUtterance()
        }

        val latencyMs = try {
            fast.speak(text) {
                // onStart fires from the OS when audio playback begins.
                firstAudioLatencyMsAtomic.set(
                    (System.nanoTime() - tr.sourceChunkTimestampNs) / 1_000_000,
                )
            }
        } catch (t: Throwable) {
            errorCount.incrementAndGet()
            Log.e(TAG, "Android TTS speak() failed for text='${text.take(60)}'", t)
            emitError(
                "Android TTS failed: ${t.javaClass.simpleName}: ${t.message}",
                tr,
            )
            if (tr.isFinal || !config.streamingEnabled) {
                openUtteranceTs = null
                sink?.endUtterance()
            }
            return
        }

        synthesizedCount.incrementAndGet()
        totalLatencyMs.addAndGet(latencyMs)

        // Emit a lightweight TtsAudioReady so the UI/metrics fire the same
        // way they do for the Kokoro path. Empty samples signal "the OS
        // already played the audio" — `TtsPlayerSink.play(ShortArray(0))`
        // is a no-op in `TtsAudioPlayer`, so the ViewModel's non-streaming
        // handler doesn't double-play or glitch.
        bus.emit(
            AudioEvent.TtsAudioReady(
                samples = ShortArray(0),
                sampleRate = 0,
                sourceText = text,
                sourceTranslationTimestampNs = tr.timestampNs,
                latencyMs = latencyMs,
                timestampNs = System.nanoTime(),
            ),
        )

        // Close the bookend when the utterance completes. For non-streaming
        // Gemma every TranslationReady is `isFinal=true` — close immediately.
        // For streaming Gemma we only close on the last sentence.
        if (tr.isFinal || !config.streamingEnabled) {
            openUtteranceTs = null
            sink?.endUtterance()
        }
    }

    private suspend fun processTranslationOneShot(tr: AudioEvent.TranslationReady, text: String) {
        val result = try {
            engine.synthesize(text, config.voice, currentSpeed)
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

        // One-shot: emit is the moment PCM is available for the ViewModel to
        // play. Chunk timestamp is on the TranslationReady we consumed.
        firstAudioLatencyMsAtomic.set(
            (System.nanoTime() - tr.sourceChunkTimestampNs) / 1_000_000,
        )

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

    /**
     * Fase 6 Stage B — streaming path.
     *
     * Detects utterance boundaries by watching [tr.sourceChunkTimestampNs]:
     *  - When it changes from the previously-open utterance, open a new
     *    one (`beginUtterance`). If the previous one never received an
     *    `isFinal=true` event (DROP_OLDEST removed it, or Gemma errored),
     *    close it defensively first so the sink's depth counter stays
     *    balanced.
     *  - When [tr.isFinal] is `true`, close the utterance
     *    (`endUtterance`) after synthesis completes.
     *
     * Between those boundaries, every per-sentence PCM produced by
     * [KokoroTtsEngine.synthesizeStreaming] is handed directly to
     * [TtsPlayerSink.play]. A lightweight `TtsAudioReady` is also emitted
     * per sentence for UI/metrics — the ViewModel branches on
     * `TtsConfig.streamingEnabled` and skips its own `player.play()` in
     * that path, so the samples are NOT played twice.
     */
    private suspend fun processTranslationStreaming(
        tr: AudioEvent.TranslationReady,
        text: String,
        @Suppress("UNUSED_PARAMETER") sink: TtsPlayerSink,
    ) {
        val newUtterance = tr.sourceChunkTimestampNs != openUtteranceTs
        if (newUtterance) {
            val prev = openUtteranceTs
            if (prev != null) {
                Log.w(TAG, "Streaming: utterance ts=$prev never terminated; endUtterance defensively")
                playChannel.send(PlayItem.End)
            }
            openUtteranceTs = tr.sourceChunkTimestampNs
            playChannel.send(PlayItem.Begin)
        }
        // Latency landmark: the playback coroutine records the utterance's
        // first sink.play (marked on the first piece of a new utterance).
        var firstAudioPending = newUtterance
        var tMark = System.nanoTime()

        val result = try {
            engine.synthesizeStreaming(text, config.voice, currentSpeed) { pcm, sr, _ ->
                val tSynth = System.nanoTime()
                Log.i(TAG, "TTSDIAG piece: synthMs=${(tSynth - tMark) / 1_000_000} " +
                    "audioMs=${pcm.size * 1000L / sr}")
                playChannel.send(
                    PlayItem.Pcm(pcm, sr, if (firstAudioPending) tr.sourceChunkTimestampNs else null, tSynth),
                )
                firstAudioPending = false
                tMark = System.nanoTime()
                bus.emit(
                    AudioEvent.TtsAudioReady(
                        samples = pcm,
                        sampleRate = sr,
                        sourceText = text,
                        sourceTranslationTimestampNs = tr.timestampNs,
                        // Per-sentence latency isn't meaningful mid-stream —
                        // the aggregate lands on the isFinal event's own
                        // synthesizedCount increment.
                        latencyMs = 0L,
                        timestampNs = System.nanoTime(),
                    ),
                )
            }
        } catch (t: Throwable) {
            errorCount.incrementAndGet()
            Log.e(TAG, "Kokoro synthesizeStreaming() failed for text='${text.take(60)}'", t)
            emitError(
                "Kokoro synthesizeStreaming failed: ${t.javaClass.simpleName}: ${t.message}",
                tr,
            )
            // Balance the bookend so the sink doesn't get stuck muted; if not
            // final, the next utterance's defensive End closes it.
            if (tr.isFinal) {
                openUtteranceTs = null
                playChannel.send(PlayItem.End)
            }
            return
        }

        synthesizedCount.incrementAndGet()
        totalLatencyMs.addAndGet(result.latencyMs)

        if (tr.isFinal) {
            openUtteranceTs = null
            playChannel.send(PlayItem.End)
        }
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
