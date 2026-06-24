package com.travel2chicago.gemmapipeline.audio

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

    /**
     * Gemma AST has produced a translation for a previously emitted [ChunkReady].
     * [sourceChunkTimestampNs] is the timestamp of the originating [ChunkReady]
     * so the UI can correlate translations with the chunks they came from.
     * [latencyMs] is wall-clock time spent inside `Conversation.sendMessage`.
     */
    data class TranslationReady(
        val text: String,
        val sourceChunkTimestampNs: Long,
        val sourceDurationMs: Int,
        val sourcePeak: Int,
        val latencyMs: Long,
        val timestampNs: Long,
    ) : AudioEvent()

    /**
     * Gemma AST failed to translate a chunk. Pipeline continues; the next
     * chunk gets a fresh attempt. Surfaced so the UI can show the error and
     * the operator can decide whether to restart the engine.
     */
    data class AstError(
        val message: String,
        val sourceChunkTimestampNs: Long,
        val timestampNs: Long,
    ) : AudioEvent()
}
