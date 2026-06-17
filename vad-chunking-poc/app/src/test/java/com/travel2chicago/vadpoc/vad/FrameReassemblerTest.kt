package com.travel2chicago.vadpoc.vad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class FrameReassemblerTest {

    @Test
    fun `exact frame size returns one frame and zero pending`() {
        val r = FrameReassembler(frameSize = 512)
        val out = r.feed(ShortArray(512) { it.toShort() })
        assertEquals(1, out.size)
        assertEquals(512, out[0].size)
        assertEquals(0, r.pendingSamples)
    }

    @Test
    fun `over-size chunk emits one frame and keeps remainder`() {
        val r = FrameReassembler(frameSize = 512)
        val out = r.feed(ShortArray(600) { it.toShort() })
        assertEquals(1, out.size)
        assertEquals(88, r.pendingSamples)
    }

    @Test
    fun `multiple small chunks accumulate into one frame`() {
        val r = FrameReassembler(frameSize = 512)
        assertEquals(0, r.feed(ShortArray(200)).size)
        assertEquals(0, r.feed(ShortArray(200)).size)
        val out = r.feed(ShortArray(200))
        assertEquals(1, out.size)
        assertEquals(88, r.pendingSamples)
    }

    @Test
    fun `empty feed returns empty and does not touch pending`() {
        val r = FrameReassembler(frameSize = 512)
        r.feed(ShortArray(300))
        assertEquals(0, r.feed(ShortArray(0)).size)
        assertEquals(300, r.pendingSamples)
    }

    @Test
    fun `large multi-frame chunk emits all complete frames`() {
        val r = FrameReassembler(frameSize = 512)
        val out = r.feed(ShortArray(2048))
        assertEquals(4, out.size)
        assertEquals(0, r.pendingSamples)
        out.forEach { assertEquals(512, it.size) }
    }

    @Test
    fun `random sequence preserves total sample count`() {
        val r = FrameReassembler(frameSize = 512)
        val rng = Random(1234L)
        var totalIn = 0L
        var totalOut = 0L
        repeat(30) {
            val size = rng.nextInt(1, 2049)
            totalIn += size
            val frames = r.feed(ShortArray(size))
            totalOut += frames.size.toLong() * 512L
        }
        assertEquals(totalIn, totalOut + r.pendingSamples)
        assertEquals(totalIn, r.totalSamplesIn)
        assertEquals(totalOut, r.totalSamplesOut)
    }

    @Test
    fun `reset clears tail and counters`() {
        val r = FrameReassembler(frameSize = 512)
        r.feed(ShortArray(700))
        assertTrue(r.pendingSamples > 0)
        r.reset()
        assertEquals(0, r.pendingSamples)
        assertEquals(0L, r.totalSamplesIn)
        assertEquals(0L, r.totalSamplesOut)
    }

    @Test
    fun `emitted frames are independent arrays`() {
        val r = FrameReassembler(frameSize = 512)
        val out = r.feed(ShortArray(1024) { it.toShort() })
        assertEquals(2, out.size)
        assertNotSame(out[0], out[1])
        // Mutating the second frame must not bleed into the first.
        out[1][0] = 42
        assertEquals(0, out[0][0])
    }
}
