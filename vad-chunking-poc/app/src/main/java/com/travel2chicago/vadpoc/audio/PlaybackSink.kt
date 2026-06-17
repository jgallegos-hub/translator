package com.travel2chicago.vadpoc.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat as AndroidAudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log

private const val TAG = "PlaybackSink"

/**
 * Abstraction over the two playback back-ends — same design that worked in
 * audio-capture-poc.
 *
 *   - [OboePlaybackSink]  → USB DAC + built-in speaker (LowLatency, Exclusive)
 *   - [AudioTrackSink]    → Bluetooth A2DP / BLE (Oboe Exclusive/LowLatency
 *     cannot route A2DP on HyperOS — silently reroutes to the earpiece).
 */
interface PlaybackSink : AutoCloseable {
    val isStarted: Boolean
    fun start(deviceInfo: AudioDeviceInfo?): Boolean
    fun stop()
    fun write(samples: ShortArray): Int
    fun routedDeviceId(): Int
    fun latencyMs(): Int
    fun underflowOrDropCount(): Long
    fun sampleRate(): Int
    val label: String

    override fun close() { stop() }
}

class OboePlaybackSink(
    private val engine: NativeAudioEngine,
) : PlaybackSink {

    @Volatile private var started: Boolean = false

    override val isStarted: Boolean get() = started
    override val label: String = "Oboe"

    override fun start(deviceInfo: AudioDeviceInfo?): Boolean {
        val deviceId = deviceInfo?.id ?: 0
        val ok = engine.startPlayback(deviceId)
        if (ok) {
            started = true
            Log.i(TAG, "Oboe playback started (requested deviceId=$deviceId, routed=${engine.playbackRoutedDeviceId()})")
        } else {
            Log.e(TAG, "Oboe playback failed to start for deviceId=$deviceId")
        }
        return ok
    }

    override fun stop() {
        if (started) {
            engine.stopPlayback()
            started = false
            Log.i(TAG, "Oboe playback stopped")
        }
    }

    override fun write(samples: ShortArray): Int = engine.writePlayback(samples)
    override fun routedDeviceId(): Int = engine.playbackRoutedDeviceId()
    override fun latencyMs(): Int = engine.playbackLatencyMs()
    override fun underflowOrDropCount(): Long = engine.playbackUnderflowCount()
    override fun sampleRate(): Int = engine.actualSampleRatePlayback()
}

class AudioTrackSink(
    context: Context,
    private val sampleRate: Int = 16_000,
) : PlaybackSink {

    private val audioManager: AudioManager =
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    @Volatile private var track: AudioTrack? = null
    @Volatile private var previousAudioMode: Int = AudioManager.MODE_NORMAL
    @Volatile private var droppedSamples: Long = 0L

    override val isStarted: Boolean get() = track != null
    override val label: String = "AudioTrack"

    override fun start(deviceInfo: AudioDeviceInfo?): Boolean {
        if (track != null) return true

        Log.i(
            TAG,
            "AudioManager pre-start: mode=${audioModeName(audioManager.mode)} " +
                "a2dpOn=${audioManager.isBluetoothA2dpOn} speakerOn=${audioManager.isSpeakerphoneOn}",
        )

        previousAudioMode = audioManager.mode
        if (audioManager.mode != AudioManager.MODE_NORMAL) {
            audioManager.mode = AudioManager.MODE_NORMAL
            Log.i(TAG, "Switched AudioManager.mode → MODE_NORMAL (was ${audioModeName(previousAudioMode)})")
        }

        val channelMask = AndroidAudioFormat.CHANNEL_OUT_MONO
        val encoding = AndroidAudioFormat.ENCODING_PCM_16BIT

        val minBytes = AudioTrack.getMinBufferSize(sampleRate, channelMask, encoding)
        if (minBytes <= 0) {
            Log.e(TAG, "getMinBufferSize returned $minBytes")
            return false
        }
        val bufBytes = maxOf(minBytes * 4, sampleRate * 2)

        val format = AndroidAudioFormat.Builder()
            .setSampleRate(sampleRate)
            .setChannelMask(channelMask)
            .setEncoding(encoding)
            .build()
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        val t = AudioTrack.Builder()
            .setAudioAttributes(attrs)
            .setAudioFormat(format)
            .setBufferSizeInBytes(bufBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        if (t.state != AudioTrack.STATE_INITIALIZED) {
            Log.e(TAG, "AudioTrack init failed (state=${t.state})")
            t.release()
            restoreMode()
            return false
        }

        if (deviceInfo != null) {
            val routingRequested = t.setPreferredDevice(deviceInfo)
            Log.i(TAG, "setPreferredDevice(id=${deviceInfo.id}) → $routingRequested")
        }

        t.play()

        val routed = t.routedDevice
        Log.i(
            TAG,
            "AudioTrack playback started: bufBytes=$bufBytes routed=${routed?.id ?: -1} " +
                "(${routed?.productName ?: "?"})",
        )

        track = t
        droppedSamples = 0L
        return true
    }

    override fun stop() {
        val t = track ?: return
        track = null
        try {
            t.stop()
        } catch (_: Exception) { /* may throw if already stopped */ }
        t.release()
        restoreMode()
        Log.i(TAG, "AudioTrack playback stopped")
    }

    override fun write(samples: ShortArray): Int {
        val t = track ?: return 0
        val written = t.write(samples, 0, samples.size, AudioTrack.WRITE_NON_BLOCKING)
        if (written < 0) {
            Log.w(TAG, "AudioTrack.write returned error code $written")
            return 0
        }
        if (written < samples.size) {
            droppedSamples += (samples.size - written).toLong()
        }
        return written
    }

    override fun routedDeviceId(): Int = track?.routedDevice?.id ?: -1

    override fun latencyMs(): Int = -1

    override fun underflowOrDropCount(): Long = droppedSamples

    override fun sampleRate(): Int = track?.sampleRate ?: -1

    private fun restoreMode() {
        if (audioManager.mode != previousAudioMode) {
            audioManager.mode = previousAudioMode
            Log.i(TAG, "Restored AudioManager.mode → ${audioModeName(previousAudioMode)}")
        }
    }
}

private fun audioModeName(mode: Int): String = when (mode) {
    AudioManager.MODE_NORMAL -> "NORMAL"
    AudioManager.MODE_RINGTONE -> "RINGTONE"
    AudioManager.MODE_IN_CALL -> "IN_CALL"
    AudioManager.MODE_IN_COMMUNICATION -> "IN_COMMUNICATION"
    AudioManager.MODE_CALL_SCREENING -> "CALL_SCREENING"
    AudioManager.MODE_CURRENT -> "CURRENT"
    AudioManager.MODE_INVALID -> "INVALID"
    else -> "MODE_$mode"
}
