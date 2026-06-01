package com.travel2chicago.audiopoc.audio

/**
 * Audio format used end-to-end. Matches the Python prototype defaults
 * (`src/audio_manager/config.py`): 16 kHz mono int16, 512-sample blocks.
 */
data class AudioFormat(
    val sampleRate: Int = 16_000,
    val channelCount: Int = 1,
    val blockSize: Int = 512,
) {
    /** Duration of one [blockSize] block in milliseconds. */
    val frameDurationMs: Double get() = blockSize.toDouble() * 1000.0 / sampleRate

    init {
        require(sampleRate > 0) { "sampleRate must be positive" }
        require(channelCount in 1..2) { "channelCount must be 1 or 2" }
        require(blockSize > 0) { "blockSize must be positive" }
    }
}

/**
 * Capacity of the lock-free ring buffer used between the Oboe callback and
 * the drain coroutine. 5 s at 16 kHz mono = 80 000 samples ≈ 160 KB.
 */
data class RingBufferConfig(
    val capacitySeconds: Double = 5.0,
) {
    init {
        require(capacitySeconds > 0) { "capacitySeconds must be positive" }
    }
}

/**
 * Aggregate config passed to the engine. Mirrors `AudioManagerConfig` in
 * the Python prototype.
 */
data class AudioEngineConfig(
    val format: AudioFormat = AudioFormat(),
    val ringBuffer: RingBufferConfig = RingBufferConfig(),
    /** Polling interval for the Kotlin drain coroutine. */
    val drainIntervalMs: Long = 25L,
)
