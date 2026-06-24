package com.travel2chicago.gemmapipeline.audio

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.sin

private const val TAG = "AudioPlayback"

/**
 * Picks a [PlaybackSink] implementation based on the chosen output device.
 * Mirrors the dispatch logic from audio-capture-poc that proved out the
 * dual-backend approach on the Xiaomi 15T Pro.
 */
class AudioPlaybackManager(
    private val context: Context,
    private val nativeEngine: NativeAudioEngine,
    private val config: AudioEngineConfig,
    private val scope: CoroutineScope,
) {
    @Volatile private var sink: PlaybackSink? = null
    @Volatile private var feedJob: Job? = null

    val isRunning: Boolean get() = sink?.isStarted == true
    val activeSinkLabel: String? get() = sink?.label

    fun start(device: AudioDeviceManager.DeviceEntry?): Boolean {
        if (isRunning) {
            Log.w(TAG, "start: already running with ${sink?.label}, ignoring")
            return true
        }

        val chosen = pickSink(device)
        val deviceInfo = device?.info
        val ok = chosen.start(deviceInfo)
        if (!ok) {
            chosen.close()
            return false
        }
        sink = chosen
        Log.i(TAG, "Playback sink active: ${chosen.label} (device=${deviceInfo?.id ?: "default"})")
        return true
    }

    fun stop() {
        feedJob?.cancel()
        feedJob = null
        sink?.stop()
        sink?.close()
        sink = null
    }

    fun play(samples: ShortArray): Job? {
        val s = sink ?: run {
            Log.w(TAG, "play: no sink started")
            return null
        }
        feedJob?.cancel()
        val job = scope.launch(Dispatchers.IO) { feed(s, samples) }
        feedJob = job
        return job
    }

    fun playSineWave(freqHz: Float = 440f, durationMs: Int = 1500, amplitude: Float = 0.4f): Job? {
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

    fun feedLoopback(samples: ShortArray): Int = sink?.write(samples) ?: 0

    fun routedDeviceId(): Int = sink?.routedDeviceId() ?: -1
    fun latencyMs(): Int = sink?.latencyMs() ?: -1
    fun underflowOrDropCount(): Long = sink?.underflowOrDropCount() ?: 0L
    fun sampleRate(): Int = sink?.sampleRate() ?: -1

    private fun pickSink(device: AudioDeviceManager.DeviceEntry?): PlaybackSink {
        val type = device?.info?.type
        return when {
            type != null && type.isBluetoothOutput() -> {
                Log.i(TAG, "Picking AudioTrackSink (BT type=${typeName(type)})")
                AudioTrackSink(context, config.format.sampleRate)
            }
            else -> {
                Log.i(TAG, "Picking OboePlaybackSink (type=${type?.let { typeName(it) } ?: "default"})")
                OboePlaybackSink(nativeEngine)
            }
        }
    }

    private suspend fun feed(sink: PlaybackSink, samples: ShortArray) {
        val chunk = config.format.sampleRate / 8
        var offset = 0
        while (offset < samples.size && currentCoroutineContext().isActive) {
            val len = minOf(chunk, samples.size - offset)
            val slice = samples.copyOfRange(offset, offset + len)
            sink.write(slice)
            offset += len
            delay((len * 800L / config.format.sampleRate).coerceAtLeast(5L))
        }
    }
}
