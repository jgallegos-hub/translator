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
     * [latencyMs] is wall-clock time spent inside `Conversation.sendMessage` for
     * the non-streaming path; for streaming it's the time from the chunk's
     * router entry to the current emission's first-token or per-sentence
     * completion (approximate — see `AstChunkRouter`).
     *
     * **Streaming fields** ([sentenceIndex], [isFinal]) are populated only
     * when `AstConfig.streamingEnabled = true`. In that mode Fase 6 emits ONE
     * event per sentence of Gemma's reply so Kokoro can begin synthesising
     * sentence 1 while Gemma is still decoding sentence 2. Consumers that
     * don't care can ignore both fields — the non-streaming path emits a
     * single event with `sentenceIndex = null` and `isFinal = true`, i.e.
     * the pre-Fase-6 shape.
     */
    data class TranslationReady(
        val text: String,
        val sourceChunkTimestampNs: Long,
        val sourceDurationMs: Int,
        val sourcePeak: Int,
        val latencyMs: Long,
        val timestampNs: Long,
        /**
         * 0-based index within the reply for the source chunk when streaming
         * is on. `null` in the non-streaming path (single-event-per-chunk
         * contract preserved).
         */
        val sentenceIndex: Int? = null,
        /**
         * `true` when this is the LAST event the router will emit for the
         * given [sourceChunkTimestampNs]. In the non-streaming path always
         * `true`. In streaming: `false` for every sentence except the last
         * (which may be a trailing non-terminated fragment or the final
         * closed sentence). Consumers can use this to bookend per-utterance
         * work — e.g. `TtsAudioPlayer.endUtterance` (Stage B).
         */
        val isFinal: Boolean = true,
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

    /**
     * Kokoro TTS has synthesized PCM audio for a previously emitted
     * [TranslationReady]. [samples] is int16 mono at [sampleRate] (typically
     * 24 kHz — note this is DIFFERENT from the 16 kHz capture/chunk rate, so
     * the playback path for TTS uses a dedicated [tts.TtsAudioPlayer] running
     * its own AudioTrack at 24 kHz rather than the Oboe 16 kHz playback).
     */
    data class TtsAudioReady(
        val samples: ShortArray,
        val sampleRate: Int,
        val sourceText: String,
        val sourceTranslationTimestampNs: Long,
        val latencyMs: Long,
        val timestampNs: Long,
    ) : AudioEvent() {
        override fun equals(other: Any?): Boolean = this === other
        override fun hashCode(): Int = System.identityHashCode(this)
    }

    /**
     * Kokoro TTS failed to synthesize a translation. Router continues; the
     * next translation gets a fresh attempt. Surfaced so the UI can show the
     * error.
     */
    data class TtsError(
        val message: String,
        val sourceText: String,
        val sourceTranslationTimestampNs: Long,
        val timestampNs: Long,
    ) : AudioEvent()
}
