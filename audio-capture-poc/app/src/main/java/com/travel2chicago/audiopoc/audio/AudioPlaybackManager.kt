package com.travel2chicago.audiopoc.audio

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.sin

private const val TAG = "AudioPlayback"

/**
 * Drives [NativeAudioEngine.startPlayback]. Offers two ways to feed audio:
 *  - [play] for an arbitrary PCM buffer (e.g. loop-back of capture).
 *  - [playSineWave] for a smoke test that does not depend on the mic.
 *
 * The actual blocking write happens on a coroutine because the playback ring
 * is bounded; if we tried to enqueue the whole buffer at once we would drop
 * frames on overflow.
 */
class AudioPlaybackManager(
    private val engine: NativeAudioEngine,
    private val config: AudioEngineConfig,
    private val scope: CoroutineScope,
) {
    @Volatile private var feedJob: Job? = null

    val isRunning: Boolean get() = feedJob?.isActive == true

    /** Open the playback stream. Idempotent. */
    fun start(outputDeviceId: Int = 0): Boolean {
        if (engine.startPlayback(outputDeviceId)) {
            Log.i(TAG, "Playback started (deviceId=$outputDeviceId, routed=${engine.playbackRoutedDeviceId()})")
            return true
        }
        Log.e(TAG, "engine.startPlayback failed for deviceId=$outputDeviceId")
        return false
    }

    fun stop() {
        feedJob?.cancel()
        feedJob = null
        engine.stopPlayback()
        Log.i(TAG, "Playback stopped")
    }

    /**
     * Schedule [samples] to play through the open stream. Drops any previous
     * feed job. Returns the [Job] so callers can `join` if they want to know
     * when the buffer is fully enqueued.
     */
    fun play(samples: ShortArray): Job {
        feedJob?.cancel()
        val job = scope.launch(Dispatchers.IO) {
            feed(samples)
        }
        feedJob = job
        return job
    }

    /** Generate and play a sine tone — quick way to verify the output path. */
    fun playSineWave(freqHz: Float = 440f, durationMs: Int = 1500, amplitude: Float = 0.4f): Job {
        val sr = config.format.sampleRate
        val total = sr * durationMs / 1000
        val buffer = ShortArray(total)
        val twoPiF = 2.0 * PI * freqHz
        val maxAmp = (Short.MAX_VALUE * amplitude).toInt().coerceAtMost(Short.MAX_VALUE.toInt())
        for (i in 0 until total) {
            buffer[i] = (maxAmp * sin(twoPiF * i / sr)).toInt().toShort()
        }
        return play(buffer)
    }

    private suspend fun feed(samples: ShortArray) {
        // Write in chunks so we don't blow the ring on overflow. One chunk =
        // ~120 ms which leaves headroom for the audio callback to drain.
        val chunk = config.format.sampleRate / 8
        var offset = 0
        while (offset < samples.size) {
            val len = minOf(chunk, samples.size - offset)
            val slice = samples.copyOfRange(offset, offset + len)
            engine.writePlayback(slice)
            offset += len
            // Sleep a bit less than the chunk duration so the ring stays primed.
            delay((len * 800L / config.format.sampleRate).coerceAtLeast(5L))
        }
    }
}
