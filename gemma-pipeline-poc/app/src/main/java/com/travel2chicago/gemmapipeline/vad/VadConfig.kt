package com.travel2chicago.gemmapipeline.vad

import com.travel2chicago.gemmapipeline.audio.AudioFormat

/**
 * Tuning for [SileroVadProcessor]. Port of `VADConfig` in
 * `src/audio_manager/config.py`.
 *
 * The hysteresis durations ([minSpeechMs] / [minSilenceMs]) are expressed in
 * milliseconds so they survive a change in [AudioFormat.blockSize]. The
 * processor converts them into frame counts at construction time via
 * [framesFor].
 */
data class VadConfig(
    /** Silero raw probability ≥ this counts as a speech frame (range 0–1, exclusive). */
    val threshold: Float = 0.5f,
    /**
     * Minimum SPEECH frames before flipping SILENCE → SPEECH. Anti-flicker;
     * filters out single-frame outliers (coughs, transients).
     */
    val minSpeechMs: Int = 50,
    /**
     * Minimum SILENCE frames before flipping SPEECH → SILENCE. Lets short
     * inter-syllable gaps stay inside one utterance.
     */
    val minSilenceMs: Int = 300,
) {
    init {
        require(threshold in 0.0f..1.0f) { "threshold must be in [0, 1], got $threshold" }
        require(minSpeechMs >= 0) { "minSpeechMs must be ≥ 0" }
        require(minSilenceMs >= 0) { "minSilenceMs must be ≥ 0" }
    }

    /** Convert a duration in ms into a frame count for the given [format] (≥ 1). */
    fun framesFor(ms: Int, format: AudioFormat): Int {
        val samples = ms * format.sampleRate / 1000
        return (samples / format.blockSize).coerceAtLeast(1)
    }
}
