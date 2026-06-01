package com.travel2chicago.audiopoc.audio

/**
 * Thin Kotlin wrapper over the JNI bridge in `cpp/jni_bridge.cpp`.
 *
 * Lifetime: the `nativeHandle` is allocated in [nativeCreate] and freed in
 * [close]. After [close] all other calls are no-ops.
 *
 * All methods that take or return PCM samples use `ShortArray` to keep the
 * surface simple. Production-grade code should pool buffers — see the Fase 2
 * risk register in the plan file.
 */
class NativeAudioEngine private constructor(
    private var nativeHandle: Long,
) : AutoCloseable {

    val isClosed: Boolean get() = nativeHandle == 0L

    fun startCapture(inputDeviceId: Int = 0): Boolean {
        if (isClosed) return false
        return nativeStartCapture(nativeHandle, inputDeviceId)
    }

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

    /** Returns the number of samples actually drained into [dst]. */
    fun drainCapture(dst: ShortArray): Int =
        if (isClosed) 0 else nativeDrainCapture(nativeHandle, dst)

    /** Returns the number of samples written into the playback ring. */
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

    // ── External native ─────────────────────────────────────────────────────

    private external fun nativeStartCapture(handle: Long, inputDeviceId: Int): Boolean
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
        init { System.loadLibrary("audiopoc") }

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

/**
 * Thin wrapper around the native `RingBuffer` used exclusively by tests
 * (instrumented test on device runs the JVM with libaudiopoc.so loaded).
 */
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
        init { System.loadLibrary("audiopoc") }

        @JvmStatic
        private external fun nativeCreate(capacitySamples: Int): Long
    }
}
