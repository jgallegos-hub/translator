package com.travel2chicago.audiopoc.audio

import android.media.AudioDeviceInfo

/**
 * Events emitted by [AudioEventBus]. Sealed class so consumers can exhaustively
 * pattern-match. Port of the Python `AudioEvent` union in `types.py`.
 */
sealed class AudioEvent {
    /** A new chunk of PCM samples is available from capture. */
    data class AudioData(
        val samples: ShortArray,
        val frameCount: Int,
        val timestampNs: Long,
    ) : AudioEvent() {
        override fun equals(other: Any?): Boolean = this === other  // ShortArray identity
        override fun hashCode(): Int = System.identityHashCode(this)
    }

    /** An input or output device was plugged in or otherwise became available. */
    data class DeviceConnected(val info: AudioDeviceInfo, val isInput: Boolean) : AudioEvent()

    /** An input or output device disappeared (USB unplug, BT disconnect). */
    data class DeviceDisconnected(val deviceId: Int, val description: String) : AudioEvent()

    /** The capture ring buffer dropped samples because it was full. */
    data class BufferOverflow(val droppedSamples: Long) : AudioEvent()

    /** Free-form engine status used for debugging in the UI. */
    data class EngineStatus(val message: String) : AudioEvent()
}
