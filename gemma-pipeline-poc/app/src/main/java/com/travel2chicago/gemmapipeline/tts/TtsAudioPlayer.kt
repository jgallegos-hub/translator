package com.travel2chicago.gemmapipeline.tts

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.travel2chicago.gemmapipeline.audio.AecProcessor
import com.travel2chicago.gemmapipeline.audio.EchoDatasetRecorder
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

private const val TAG = "TtsAudioPlayer"
private const val KEEPALIVE_CHUNK_MS = 20
private const val KEEPALIVE_MAX_QUEUED_MS = 40

/**
 * Abstraction over the sink [TtsRouter] writes PCM to. `TtsAudioPlayer` is
 * the production impl (Android `AudioTrack`); tests can swap in a fake to
 * assert call ordering without touching the Android media stack.
 *
 * The `beginUtterance` / `endUtterance` pair exists so a multi-sentence
 * utterance (Fase 6 Stage A splits Gemma's reply into per-sentence
 * `TranslationReady` events; Stage B streams each sentence's PCM as it
 * synthesises) can be treated as ONE mute window from the VAD's point of
 * view — otherwise the `ttsPlaying` flag would flicker `true→false→true`
 * between adjacent `play()` calls and the pipeline's mute-rising-edge
 * handler would fire spurious chunker resets.
 */
interface TtsPlayerSink {
    /** Increment utterance depth. On the 0→1 transition, raise the shared
     *  VAD-mute flag. Safe to call from any thread. Idempotent under
     *  balanced pairing with [endUtterance]. */
    fun beginUtterance()

    /** Decrement utterance depth (clamped at 0). On the 1→0 transition,
     *  lower the VAD-mute flag. Safe to call from any thread. */
    fun endUtterance()

    /** Write [pcm] to the sink, suspending until it's been handed to the
     *  OS audio stack. Concurrent calls are serialised internally. */
    suspend fun play(pcm: ShortArray)
}

/**
 * Dedicated audio sink for TTS PCM at 24 kHz (Kokoro's native rate). Kept
 * deliberately separate from [com.travel2chicago.gemmapipeline.audio.AudioPlaybackManager]
 * — that one is wedded to the 16 kHz capture/chunk rate baked into the Oboe
 * engine config, and re-opening it per playback would risk glitches.
 *
 * Implementation notes:
 *   - `AudioTrack.MODE_STREAM` so we can keep writing samples while playback
 *     drains, no pre-allocation of the full buffer.
 *   - `CONTENT_TYPE_SPEECH` is a hint to the Bluetooth A2DP stack to favour
 *     latency over fidelity — appropriate for spoken translations.
 *   - One [Mutex] serialises [play] calls so two `TtsAudioReady` events
 *     arriving back-to-back are spoken in order, not overlapped.
 *   - Long writes are chunked into ~100 ms windows so a `cancel()` on the
 *     calling coroutine actually stops playback in a bounded time.
 */
class TtsAudioPlayer(
    private val sampleRate: Int = 24_000,
    /**
     * Shared flag toggled while [play] is actively writing samples to the
     * `AudioTrack`. The VAD/chunker pipeline reads this to short-circuit
     * incoming mic frames during playback — otherwise the mic picks up
     * the speaker output and feeds it back into Gemma as new "speech".
     *
     * Owned by the ViewModel; the player only sets it. Default is an
     * unshared instance so tests and standalone uses work without any
     * wiring.
     */
    private val ttsPlaying: AtomicBoolean = AtomicBoolean(false),
    /**
     * Optional software echo canceller. When non-null AND initialised,
     * [play] pipes each PCM buffer into [AecProcessor.bufferFarend] BEFORE
     * writing to the `AudioTrack` — the timestamp of that call is the
     * anchor for the delay estimate the near-end path uses. When null
     * (or when [AecProcessor.isInitialized] is false), the pump is a
     * no-op and playback path is unchanged.
     */
    @Volatile private var aecProcessor: AecProcessor? = null,
) : TtsPlayerSink, AutoCloseable {

    /** Attach an [AecProcessor] after construction. Used by the ViewModel
     *  when the software AEC toggle flips ON — avoids re-creating the
     *  player (which owns the `AudioTrack` handle) just to bind the
     *  processor. Passing `null` detaches. */
    fun setAecProcessor(processor: AecProcessor?) {
        aecProcessor = processor
    }

    /** Experimento E0/E1: cuando no es null, cada buffer reproducido se copia
     *  como referencia al dataset de eco. */
    @Volatile private var datasetRecorder: EchoDatasetRecorder? = null

    fun setDatasetRecorder(recorder: EchoDatasetRecorder?) {
        datasetRecorder = recorder
    }

    private var track: AudioTrack? = null
    private val mutex = Mutex()

    /**
     * Bluetooth keep-alive. El A2DP se "duerme" tras unos segundos de
     * silencio y, al despertar, recorta o retrasa el inicio del audio
     * (device test 2026-10-08: sólo 2 de 10 chirps sonaron; el delay
     * altavoz→mic saltaba de ~0.5 s a ~1.5 s). Con esto ON, un hilo escribe
     * silencio a ritmo real mientras no hay TTS, manteniendo la ocupación
     * del buffer bajo [KEEPALIVE_MAX_QUEUED_MS] para no añadir latencia.
     */
    @Volatile private var keepAliveEnabled: Boolean = true
    @Volatile private var keepAliveThread: Thread? = null

    /** Frames entregados al AudioTrack (voz + silencio). Sólo se toca con
     *  [mutex] tomado. Junto con `playbackHeadPosition` da la ocupación. */
    private var framesWritten: Long = 0L

    fun setKeepAlive(enabled: Boolean) {
        keepAliveEnabled = enabled
        Log.i(TAG, "BT keep-alive → $enabled")
    }

    /** Utterance-bookend depth. `beginUtterance` increments, `endUtterance`
     *  decrements (clamped at 0). While `> 0`, per-call `play()` DOES NOT
     *  touch [ttsPlaying] — the bookends own the flag for the entire
     *  utterance so adjacent sentences don't produce false un-mute edges. */
    private val utteranceDepth = AtomicInteger(0)

    @Volatile var isInitialized: Boolean = false
        private set

    fun init() {
        if (track != null) return
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(sampleRate * 2)  // floor: at least 1 s of audio buffered
        Log.i(TAG, "init: sampleRate=$sampleRate minBuf=$minBuf bytes")
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(minBuf * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        t.play()
        track = t
        framesWritten = 0L
        isInitialized = true
        startKeepAlive()
        Log.i(TAG, "AudioTrack started (state=${t.state}, playState=${t.playState})")
    }

    /**
     * Write [pcm] to the AudioTrack. Suspends until the entire buffer is
     * handed off to the OS audio stack. Concurrent calls are serialised via
     * the internal Mutex — the second caller waits until the first finishes
     * speaking.
     */
    override fun beginUtterance() {
        val newDepth = utteranceDepth.incrementAndGet()
        if (newDepth == 1) {
            ttsPlaying.set(true)
            Log.i(TAG, "beginUtterance: VAD mute ON (depth=$newDepth)")
        }
    }

    override fun endUtterance() {
        // Clamp at 0 defensively — unbalanced calls (e.g. router bug or
        // DROP_OLDEST removing the isFinal event) must not drive the depth
        // negative and stick the VAD in a permanently-muted state.
        val newDepth = utteranceDepth.updateAndGet { d -> maxOf(0, d - 1) }
        if (newDepth == 0) {
            ttsPlaying.set(false)
            Log.i(TAG, "endUtterance: VAD mute OFF")
        }
    }

    override suspend fun play(pcm: ShortArray) = mutex.withLock {
        val t = track ?: error("TtsAudioPlayer not initialised — call init() first")
        if (pcm.isEmpty()) return@withLock
        // Pump the whole sentence as far-end reference BEFORE the first
        // AudioTrack.write() — the delay estimate the near-end path uses
        // is measured from here to when the mic re-hears the echo. Doing
        // it once per play() (not per 100 ms chunk) keeps the reference
        // stream causally simple; AECM buffers internally so it can
        // consume the whole utterance in one go even though it processes
        // in 10 ms frames. No-op when AEC isn't wired in.
        aecProcessor?.let { proc ->
            if (proc.isInitialized) proc.bufferFarend(pcm, sampleRate)
        }
        datasetRecorder?.onRef(pcm, sampleRate)
        // If we're INSIDE a beginUtterance/endUtterance bookend, the flag
        // is owned by that pair for the entire utterance — do not touch
        // it here or adjacent per-sentence play() calls would drop the
        // flag between sentences and give VAD a spurious un-mute edge.
        // Legacy (non-streaming) callers hit the standalone branch and
        // manage the flag per call, exactly like Fase 5.
        val standalone = utteranceDepth.get() == 0
        if (standalone) ttsPlaying.set(true)
        try {
            // Write in ~100 ms chunks so a cancellation lands within ~100 ms.
            val chunkSize = (sampleRate / 10).coerceAtLeast(256)
            var off = 0
            while (off < pcm.size) {
                if (!currentCoroutineContext().isActive) {
                    Log.i(TAG, "play: cancelled at offset $off / ${pcm.size}")
                    return@withLock
                }
                val n = minOf(chunkSize, pcm.size - off)
                val written = t.write(pcm, off, n)
                if (written < 0) {
                    Log.e(TAG, "AudioTrack.write returned $written — aborting")
                    return@withLock
                }
                off += written
                framesWritten += written
            }
        } finally {
            if (standalone) ttsPlaying.set(false)
        }
    }

    private fun startKeepAlive() {
        if (keepAliveThread != null) return
        val silence = ShortArray(sampleRate * KEEPALIVE_CHUNK_MS / 1000)
        val maxQueued = sampleRate.toLong() * KEEPALIVE_MAX_QUEUED_MS / 1000
        keepAliveThread = Thread({
            while (!Thread.currentThread().isInterrupted) {
                try {
                    Thread.sleep(KEEPALIVE_CHUNK_MS.toLong() / 2)
                } catch (_: InterruptedException) {
                    break
                }
                val t = track ?: continue
                if (!keepAliveEnabled) continue
                // Never block a real play(): skip this tick if it holds the lock.
                if (!mutex.tryLock()) continue
                try {
                    val played = t.playbackHeadPosition.toLong() and 0xFFFFFFFFL
                    if (framesWritten - played < maxQueued) {
                        val n = t.write(silence, 0, silence.size, AudioTrack.WRITE_NON_BLOCKING)
                        if (n > 0) framesWritten += n
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "keep-alive write failed: ${e.message}")
                } finally {
                    mutex.unlock()
                }
            }
        }, "tts-bt-keepalive").apply { isDaemon = true; start() }
    }

    override fun close() {
        keepAliveThread?.interrupt()
        keepAliveThread = null
        runCatching { track?.stop() }
        runCatching { track?.release() }
        track = null
        isInitialized = false
        Log.i(TAG, "AudioTrack released")
    }
}
