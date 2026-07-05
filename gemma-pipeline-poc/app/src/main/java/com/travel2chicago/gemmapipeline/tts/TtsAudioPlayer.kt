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

private const val TAG = "TtsAudioPlayer"

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
) : AutoCloseable {

    private var track: AudioTrack? = null
    private val mutex = Mutex()

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
    suspend fun play(pcm: ShortArray) = mutex.withLock {
        val t = track ?: error("TtsAudioPlayer not initialised — call init() first")
        if (pcm.isEmpty()) return@withLock
        // Raise the flag BEFORE the first sample hits the DAC so the VAD
        // pipeline mutes in time; lower it in `finally` so cancellations
        // don't leave the mic gated shut.
        ttsPlaying.set(true)
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
            ttsPlaying.set(false)
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
