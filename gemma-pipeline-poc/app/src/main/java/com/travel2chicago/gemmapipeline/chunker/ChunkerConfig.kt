package com.travel2chicago.gemmapipeline.chunker

import com.travel2chicago.gemmapipeline.audio.AudioFormat

/**
 * Tuning for [AudioChunker]. Port of `ChunkerConfig` in
 * `src/audio_manager/config.py`, with defaults retuned for Gemma 4 E4B AST:
 *
 *   - Python prototype: 500 ms min / 2000 ms max (sized for Whisper)
 *   - This POC:        3000 ms min / 6000 ms max (Gemma needs more context
 *                       per AST inference)
 *
 * All values are millisecond-based so they survive a [AudioFormat.blockSize]
 * change; the chunker derives sample / frame counts at construction.
 */
data class ChunkerConfig(
    val minChunkMs: Int = 3000,
    val maxChunkMs: Int = 6000,
    val silenceEndMs: Int = 700,
    val preRollMs: Int = 200,
) {
    init {
        require(minChunkMs > 0) { "minChunkMs must be > 0" }
        require(maxChunkMs >= minChunkMs) { "maxChunkMs ($maxChunkMs) must be ≥ minChunkMs ($minChunkMs)" }
        require(silenceEndMs > 0) { "silenceEndMs must be > 0" }
        require(preRollMs >= 0) { "preRollMs must be ≥ 0" }
    }

    fun minChunkSamples(format: AudioFormat): Int = minChunkMs * format.sampleRate / 1000
    fun maxChunkSamples(format: AudioFormat): Int = maxChunkMs * format.sampleRate / 1000
    fun silenceEndFrames(format: AudioFormat): Int =
        ((silenceEndMs * format.sampleRate / 1000) / format.blockSize).coerceAtLeast(1)
    fun preRollFrames(format: AudioFormat): Int =
        ((preRollMs * format.sampleRate / 1000) / format.blockSize).coerceAtLeast(if (preRollMs > 0) 1 else 0)
}
