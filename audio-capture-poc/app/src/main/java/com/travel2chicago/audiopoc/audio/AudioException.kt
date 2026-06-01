package com.travel2chicago.audiopoc.audio

/**
 * Sealed hierarchy of failures from the audio subsystem. Port of the Python
 * `exceptions.py` so the Kotlin call sites can use the same naming.
 */
sealed class AudioException(message: String, cause: Throwable? = null) : RuntimeException(message, cause) {

    /** A requested device id is not present in [android.media.AudioManager.getDevices]. */
    class DeviceNotFoundError(identifier: String) :
        AudioException("Audio device not found: $identifier")

    /** A device we were using disappeared (USB unplug, BT disconnect). */
    class DeviceDisconnectedError(deviceName: String) :
        AudioException("Audio device disconnected: $deviceName")

    /** Oboe `openStream` or `requestStart` failed. */
    class StreamError(message: String, cause: Throwable? = null) :
        AudioException(message, cause)

    /** The native ring buffer dropped samples because it was full. */
    class BufferOverflowError(droppedSamples: Long) :
        AudioException("Capture ring buffer dropped $droppedSamples samples")
}
