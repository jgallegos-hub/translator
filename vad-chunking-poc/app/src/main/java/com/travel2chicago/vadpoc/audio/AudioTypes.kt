package com.travel2chicago.vadpoc.audio

import android.media.AudioDeviceInfo

/**
 * Two states the VAD state machine can be in. Port of the Python `VADState`
 * enum in `src/audio_manager/types.py`.
 */
enum class VadState { SILENCE, SPEECH }

/**
 * Events emitted by [AudioEventBus]. Sealed class so consumers can exhaustively
 * pattern-match. Extends the audio-capture-poc hierarchy with [VadTransition]
 * and [ChunkReady] — these are the reason this POC exists.
 */
sealed class AudioEvent {
    data class AudioData(
        val samples: ShortArray,
        val frameCount: Int,
        val timestampNs: Long,
    ) : AudioEvent() {
        override fun equals(other: Any?): Boolean = this === other
        override fun hashCode(): Int = System.identityHashCode(this)
    }

    data class DeviceConnected(val info: AudioDeviceInfo, val isInput: Boolean) : AudioEvent()
    data class DeviceDisconnected(val deviceId: Int, val description: String) : AudioEvent()
    data class BufferOverflow(val droppedSamples: Long) : AudioEvent()
    data class EngineStatus(val message: String) : AudioEvent()

    /**
     * VAD crossed its hysteresis threshold and flipped state. [probability] is
     * the raw Silero output of the frame that triggered the transition.
     */
    data class VadTransition(
        val state: VadState,
        val timestampNs: Long,
        val probability: Float,
    ) : AudioEvent()

    /**
     * The chunker has emitted a 3–6 s utterance ready to be fed to Gemma
     * (or, in this POC, replayed for sanity check). [samples] includes the
     * pre-roll, so the first word is not clipped.
     */
    data class ChunkReady(
        val samples: ShortArray,
        val durationMs: Int,
        val peak: Int,
        val timestampNs: Long,
    ) : AudioEvent() {
        override fun equals(other: Any?): Boolean = this === other
        override fun hashCode(): Int = System.identityHashCode(this)
    }
}
