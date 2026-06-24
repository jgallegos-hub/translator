package com.travel2chicago.gemmapipeline.vad

import com.travel2chicago.gemmapipeline.audio.AudioFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class VadConfigTest {

    @Test
    fun `defaults match Python prototype VADConfig`() {
        val c = VadConfig()
        assertEquals(0.5f, c.threshold, 1e-6f)
        assertEquals(50, c.minSpeechMs)
        assertEquals(300, c.minSilenceMs)
    }

    @Test
    fun `framesFor converts ms to frames at 16 kHz mono 512-sample blocks`() {
        val cfg = VadConfig()
        val fmt = AudioFormat()
        // 32 ms @ 16 kHz = 512 samples = 1 frame
        assertEquals(1, cfg.framesFor(32, fmt))
        // 64 ms @ 16 kHz = 1024 samples = 2 frames
        assertEquals(2, cfg.framesFor(64, fmt))
        // 300 ms @ 16 kHz = 4800 samples / 512 = 9 frames
        assertEquals(9, cfg.framesFor(300, fmt))
        // 0 ms is clamped to 1 frame minimum
        assertEquals(1, cfg.framesFor(0, fmt))
    }

    @Test
    fun `threshold outside 0_1 throws`() {
        assertThrows(IllegalArgumentException::class.java) { VadConfig(threshold = -0.1f) }
        assertThrows(IllegalArgumentException::class.java) { VadConfig(threshold = 1.01f) }
    }

    @Test
    fun `negative durations throw`() {
        assertThrows(IllegalArgumentException::class.java) { VadConfig(minSpeechMs = -1) }
        assertThrows(IllegalArgumentException::class.java) { VadConfig(minSilenceMs = -1) }
    }
}
