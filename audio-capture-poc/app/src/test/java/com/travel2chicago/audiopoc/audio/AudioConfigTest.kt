package com.travel2chicago.audiopoc.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** Ports `tests/audio_manager/test_config.py` defaults check. */
class AudioConfigTest {

    @Test
    fun `default AudioFormat matches Python prototype`() {
        val f = AudioFormat()
        assertEquals(16_000, f.sampleRate)
        assertEquals(1, f.channelCount)
        assertEquals(512, f.blockSize)
    }

    @Test
    fun `frameDurationMs is derived from blockSize and sampleRate`() {
        val f = AudioFormat(sampleRate = 16_000, blockSize = 512)
        // 512 samples / 16 kHz = 32 ms
        assertEquals(32.0, f.frameDurationMs, 1e-9)
    }

    @Test
    fun `invalid sample rate is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            AudioFormat(sampleRate = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AudioFormat(sampleRate = -1)
        }
    }

    @Test
    fun `invalid channel count is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { AudioFormat(channelCount = 0) }
        assertThrows(IllegalArgumentException::class.java) { AudioFormat(channelCount = 3) }
    }

    @Test
    fun `ring buffer capacity must be positive`() {
        assertThrows(IllegalArgumentException::class.java) { RingBufferConfig(capacitySeconds = 0.0) }
        assertThrows(IllegalArgumentException::class.java) { RingBufferConfig(capacitySeconds = -1.5) }
    }

    @Test
    fun `default AudioEngineConfig wires sensible defaults`() {
        val cfg = AudioEngineConfig()
        assertEquals(16_000, cfg.format.sampleRate)
        assertEquals(5.0, cfg.ringBuffer.capacitySeconds, 1e-9)
        assertEquals(25L, cfg.drainIntervalMs)
    }
}
