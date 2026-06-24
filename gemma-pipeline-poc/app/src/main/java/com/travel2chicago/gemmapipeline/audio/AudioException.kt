package com.travel2chicago.gemmapipeline.audio

sealed class AudioException(message: String, cause: Throwable? = null) : RuntimeException(message, cause) {

    class DeviceNotFoundError(identifier: String) :
        AudioException("Audio device not found: $identifier")

    class DeviceDisconnectedError(deviceName: String) :
        AudioException("Audio device disconnected: $deviceName")

    class StreamError(message: String, cause: Throwable? = null) :
        AudioException(message, cause)

    class BufferOverflowError(droppedSamples: Long) :
        AudioException("Capture ring buffer dropped $droppedSamples samples")
}
