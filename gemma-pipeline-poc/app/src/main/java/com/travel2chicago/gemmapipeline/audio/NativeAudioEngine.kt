package com.travel2chicago.gemmapipeline.audio

/**
 * Thin Kotlin wrapper over the JNI bridge in `cpp/jni_bridge.cpp`.
 *
 * Lifetime: the `nativeHandle` is allocated in [nativeCreate] and freed in
 * [close]. After [close] all other calls are no-ops.
 */
/**
 * Mirror of the subset of `oboe::InputPreset` values we care about. The int
 * values match the Oboe enum (which in turn matches AAudio /
 * `MediaRecorder.AudioSource`) so we can pass them straight through JNI
 * without a translation table.
 *
 * `VOICE_COMMUNICATION` is the analog of `MediaRecorder.AudioSource.
 * VOICE_COMMUNICATION` — it engages the HAL's AEC / noise suppression /
 * AGC path. `UNPROCESSED` disables all of that (Fase 2 baseline).
 */
object InputPreset {
    const val UNPROCESSED = 9
    const val VOICE_COMMUNICATION = 7
    const val VOICE_RECOGNITION = 6
}

class NativeAudioEngine private constructor(
    private var nativeHandle: Long,
) : AutoCloseable {

    val isClosed: Boolean get() = nativeHandle == 0L

    /**
     * @param inputDeviceId 0 = system default, otherwise `AudioDeviceInfo.id`
     * @param inputPreset AAudio input preset (Oboe `InputPreset` enum int).
     *   Default 0 = leave at Oboe default (Unprocessed). Use
     *   [InputPreset.VOICE_COMMUNICATION] to enable HAL-level AEC.
     * @param allocateSessionId when `true`, the Oboe stream opens with
     *   `SessionId::Allocate` so [captureSessionId] returns a real session
     *   id that Kotlin can bind `AcousticEchoCanceler` (or other audiofx
     *   effects) to. Slightly higher startup cost so we only opt in when
     *   the caller actually intends to attach an effect.
     */
    fun startCapture(
        inputDeviceId: Int = 0,
        inputPreset: Int = 0,
        allocateSessionId: Boolean = false,
    ): Boolean {
        if (isClosed) return false
        return nativeStartCapture(
            nativeHandle,
            inputDeviceId,
            inputPreset,
            if (allocateSessionId) 1 else 0,
        )
    }

    /** Session id of the currently-open capture stream, or -1 if capture is
     *  not running or was started without `allocateSessionId = true`. */
    fun captureSessionId(): Int =
        if (isClosed) -1 else nativeCaptureSessionId(nativeHandle)

    fun stopCapture() {
        if (!isClosed) nativeStopCapture(nativeHandle)
    }

    fun startPlayback(outputDeviceId: Int = 0): Boolean {
        if (isClosed) return false
        return nativeStartPlayback(nativeHandle, outputDeviceId)
    }

    fun stopPlayback() {
        if (!isClosed) nativeStopPlayback(nativeHandle)
    }

    fun drainCapture(dst: ShortArray): Int =
        if (isClosed) 0 else nativeDrainCapture(nativeHandle, dst)

    fun writePlayback(src: ShortArray): Int =
        if (isClosed) 0 else nativeWritePlayback(nativeHandle, src)

    fun captureLatencyMs(): Int = if (isClosed) -1 else nativeCaptureLatencyMs(nativeHandle)
    fun playbackLatencyMs(): Int = if (isClosed) -1 else nativePlaybackLatencyMs(nativeHandle)
    fun captureOverflowCount(): Long = if (isClosed) 0L else nativeCaptureOverflowCount(nativeHandle)
    fun playbackUnderflowCount(): Long = if (isClosed) 0L else nativePlaybackUnderflowCount(nativeHandle)
    fun captureRoutedDeviceId(): Int = if (isClosed) -1 else nativeCaptureRoutedDeviceId(nativeHandle)
    fun playbackRoutedDeviceId(): Int = if (isClosed) -1 else nativePlaybackRoutedDeviceId(nativeHandle)
    fun actualSampleRateCapture(): Int = if (isClosed) -1 else nativeActualSampleRateCapture(nativeHandle)
    fun actualSampleRatePlayback(): Int = if (isClosed) -1 else nativeActualSampleRatePlayback(nativeHandle)

    override fun close() {
        if (!isClosed) {
            stopCapture()
            stopPlayback()
            nativeDestroy(nativeHandle)
            nativeHandle = 0L
        }
    }

    private external fun nativeStartCapture(
        handle: Long,
        inputDeviceId: Int,
        inputPreset: Int,
        allocateSessionId: Int,
    ): Boolean
    private external fun nativeCaptureSessionId(handle: Long): Int
    private external fun nativeStopCapture(handle: Long)
    private external fun nativeStartPlayback(handle: Long, outputDeviceId: Int): Boolean
    private external fun nativeStopPlayback(handle: Long)
    private external fun nativeDrainCapture(handle: Long, dst: ShortArray): Int
    private external fun nativeWritePlayback(handle: Long, src: ShortArray): Int
    private external fun nativeCaptureLatencyMs(handle: Long): Int
    private external fun nativePlaybackLatencyMs(handle: Long): Int
    private external fun nativeCaptureOverflowCount(handle: Long): Long
    private external fun nativePlaybackUnderflowCount(handle: Long): Long
    private external fun nativeCaptureRoutedDeviceId(handle: Long): Int
    private external fun nativePlaybackRoutedDeviceId(handle: Long): Int
    private external fun nativeActualSampleRateCapture(handle: Long): Int
    private external fun nativeActualSampleRatePlayback(handle: Long): Int
    private external fun nativeDestroy(handle: Long)

    companion object {
        init { System.loadLibrary("gemmapipeline") }

        fun create(config: AudioEngineConfig): NativeAudioEngine {
            val handle = nativeCreate(
                config.format.sampleRate,
                config.format.channelCount,
                config.ringBuffer.capacitySeconds.toInt().coerceAtLeast(1),
            )
            check(handle != 0L) { "nativeCreate returned null handle" }
            return NativeAudioEngine(handle)
        }

        @JvmStatic
        private external fun nativeCreate(sampleRate: Int, channelCount: Int, ringBufferSeconds: Int): Long
    }
}

class NativeRingBuffer(capacitySamples: Int) : AutoCloseable {
    private var handle: Long = nativeCreate(capacitySamples)

    val isClosed: Boolean get() = handle == 0L

    fun write(src: ShortArray): Int = if (isClosed) 0 else nativeWrite(handle, src)
    fun read(dst: ShortArray): Int = if (isClosed) 0 else nativeRead(handle, dst)
    fun available(): Int = if (isClosed) 0 else nativeAvailable(handle)
    fun overflowCount(): Long = if (isClosed) 0L else nativeOverflowCount(handle)

    override fun close() {
        if (!isClosed) {
            nativeDestroy(handle)
            handle = 0L
        }
    }

    private external fun nativeWrite(handle: Long, src: ShortArray): Int
    private external fun nativeRead(handle: Long, dst: ShortArray): Int
    private external fun nativeAvailable(handle: Long): Int
    private external fun nativeOverflowCount(handle: Long): Long
    private external fun nativeDestroy(handle: Long)

    companion object {
        init { System.loadLibrary("gemmapipeline") }

        @JvmStatic
        private external fun nativeCreate(capacitySamples: Int): Long
    }
}
