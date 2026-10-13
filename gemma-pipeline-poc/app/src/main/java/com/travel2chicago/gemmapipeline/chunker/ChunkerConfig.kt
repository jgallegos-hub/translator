package com.travel2chicago.gemmapipeline.chunker

import com.travel2chicago.gemmapipeline.audio.AudioFormat

/**
 * Tuning for [AudioChunker]. Port of `ChunkerConfig` in
 * `src/audio_manager/config.py`, with defaults retuned for Gemma 4 E4B AST:
 *
 *   - Python prototype:   500 ms min / 2000 ms max (sized for Whisper).
 *   - Fase 4 / 5 default: 3000 ms min / 6000 ms max — conservative floor for
 *                          Gemma to get enough context per AST inference.
 *   - Fase 6 Stage C:     1500 ms min / 6000 ms max, silenceEndMs 500 —
 *                          shipping Gemma streaming (Stage A) + Kokoro
 *                          streaming (Stage B) means the audible latency is
 *                          bounded by the first-sentence emit, not the whole
 *                          chunk boundary. Halving the min shaves ~1.5 s off
 *                          the end-of-speech → first-audio window without
 *                          meaningfully hurting translation quality on the
 *                          10-phrase canonical device test — the sliders in
 *                          the UI stay wired so the operator can dial back
 *                          up to 3000 / 700 per utterance if a specific
 *                          audio type needs more context.
 *
 *   - Smart cut (2026-10-13): with the old hard cut at 6 s, 22-29 % of
 *     chunks were split mid-phrase on device and Gemma translated each half
 *     without the other's context (inaccurate output, hallucinated generic
 *     sentences on short tails). Now [smartCut] prefers the natural end of
 *     the utterance; only when a chunk reaches [maxChunkMs] (12 s) it is cut
 *     at the LAST pause (>= [pauseCutMs] of non-speech frames) and the rest is
 *     carried into the next chunk. Offline on 5 device recordings
 *     (tools/chunker_sim.py): mid-speech cuts 9 -> 0, no extra short tails.
 *
 * All values are millisecond-based so they survive a [AudioFormat.blockSize]
 * change; the chunker derives sample / frame counts at construction.
 */
data class ChunkerConfig(
    val minChunkMs: Int = 1500,
    /** Hard cap. With [smartCut] the chunk is cut at its last pause when it
     *  reaches this length; without it, cut right here (legacy behaviour). */
    val maxChunkMs: Int = 12_000,
    val silenceEndMs: Int = 500,
    val preRollMs: Int = 200,
    val smartCut: Boolean = true,
    /** Minimum run of non-speech frames (Silero p < threshold) that counts as
     *  a pause candidate for the smart cut. */
    val pauseCutMs: Int = 100,
) {
    init {
        require(minChunkMs > 0) { "minChunkMs must be > 0" }
        require(maxChunkMs >= minChunkMs) { "maxChunkMs ($maxChunkMs) must be ≥ minChunkMs ($minChunkMs)" }
        require(silenceEndMs > 0) { "silenceEndMs must be > 0" }
        require(preRollMs >= 0) { "preRollMs must be ≥ 0" }
        require(pauseCutMs > 0) { "pauseCutMs must be > 0" }
    }

    fun minChunkSamples(format: AudioFormat): Int = minChunkMs * format.sampleRate / 1000
    fun maxChunkSamples(format: AudioFormat): Int = maxChunkMs * format.sampleRate / 1000
    fun silenceEndFrames(format: AudioFormat): Int =
        ((silenceEndMs * format.sampleRate / 1000) / format.blockSize).coerceAtLeast(1)
    fun pauseCutFrames(format: AudioFormat): Int =
        ((pauseCutMs * format.sampleRate / 1000) / format.blockSize).coerceAtLeast(1)
    fun preRollFrames(format: AudioFormat): Int =
        ((preRollMs * format.sampleRate / 1000) / format.blockSize).coerceAtLeast(if (preRollMs > 0) 1 else 0)
}
