package com.travel2chicago.gemmapipeline.audio

/**
 * Audio format used end-to-end. Matches the Python prototype defaults
 * (`src/audio_manager/config.py`): 16 kHz mono int16, 512-sample blocks.
 */
data class AudioFormat(
    val sampleRate: Int = 16_000,
    val channelCount: Int = 1,
    val blockSize: Int = 512,
) {
    val frameDurationMs: Double get() = blockSize.toDouble() * 1000.0 / sampleRate

    init {
        require(sampleRate > 0) { "sampleRate must be positive" }
        require(channelCount in 1..2) { "channelCount must be 1 or 2" }
        require(blockSize > 0) { "blockSize must be positive" }
    }
}

data class RingBufferConfig(
    val capacitySeconds: Double = 5.0,
) {
    init {
        require(capacitySeconds > 0) { "capacitySeconds must be positive" }
    }
}

data class AudioEngineConfig(
    val format: AudioFormat = AudioFormat(),
    val ringBuffer: RingBufferConfig = RingBufferConfig(),
    val drainIntervalMs: Long = 25L,
)
