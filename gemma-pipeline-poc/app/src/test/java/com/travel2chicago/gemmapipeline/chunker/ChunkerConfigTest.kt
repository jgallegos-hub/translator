package com.travel2chicago.gemmapipeline.chunker

import com.travel2chicago.gemmapipeline.audio.AudioFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ChunkerConfigTest {

    @Test
    fun `defaults are tuned for Gemma AST`() {
        val c = ChunkerConfig()
        assertEquals(3000, c.minChunkMs)
        assertEquals(6000, c.maxChunkMs)
        assertEquals(700, c.silenceEndMs)
        assertEquals(200, c.preRollMs)
    }

    @Test
    fun `sample and frame math at 16 kHz mono 512-block`() {
        val c = ChunkerConfig()
        val fmt = AudioFormat()
        assertEquals(48_000, c.minChunkSamples(fmt))   // 3 s
        assertEquals(96_000, c.maxChunkSamples(fmt))   // 6 s
        // 700 ms = 11200 samples / 512 = 21 frames
        assertEquals(21, c.silenceEndFrames(fmt))
        // 200 ms = 3200 samples / 512 = 6 frames
        assertEquals(6, c.preRollFrames(fmt))
    }

    @Test
    fun `preRollMs zero yields zero pre-roll frames`() {
        val c = ChunkerConfig(preRollMs = 0)
        val fmt = AudioFormat()
        assertEquals(0, c.preRollFrames(fmt))
    }

    @Test
    fun `invalid ordering throws`() {
        assertThrows(IllegalArgumentException::class.java) {
            ChunkerConfig(minChunkMs = 5000, maxChunkMs = 1000)
        }
        assertThrows(IllegalArgumentException::class.java) { ChunkerConfig(minChunkMs = 0) }
        assertThrows(IllegalArgumentException::class.java) { ChunkerConfig(silenceEndMs = 0) }
        assertThrows(IllegalArgumentException::class.java) { ChunkerConfig(preRollMs = -1) }
    }
}
