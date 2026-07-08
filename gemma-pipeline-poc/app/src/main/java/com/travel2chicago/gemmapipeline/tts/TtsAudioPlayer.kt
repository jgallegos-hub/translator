package com.travel2chicago.gemmapipeline.tts

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

private const val TAG = "TtsAudioPlayer"

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
) : TtsPlayerSink, AutoCloseable {

    private var track: AudioTrack? = null
    private val mutex = Mutex()

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
        isInitialized = true
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
            }
        } finally {
            if (standalone) ttsPlaying.set(false)
        }
    }

    override fun close() {
        runCatching { track?.stop() }
        runCatching { track?.release() }
        track = null
        isInitialized = false
        Log.i(TAG, "AudioTrack released")
    }
}
