package com.travel2chicago.gemmapipeline.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class RenderTimelineTest {

    private fun read(t: RenderTimeline, from: Long, n: Int) = FloatArray(n).also { t.read(from, it) }

    @Test
    fun `blocks are contiguous while queued and start at capture time after a drain`() {
        val t = RenderTimeline(capacity = 10_000)
        assertEquals(100L, t.append(ShortArray(50) { 1 }, captureNow = 100))   // [100,150)
        assertEquals(150L, t.append(ShortArray(50) { 2 }, captureNow = 120))   // queued → contiguous
        assertEquals(1_000L, t.append(ShortArray(10) { 3 }, captureNow = 1_000)) // drained → now
        assertEquals(1f, read(t, 149, 1)[0])
        assertEquals(2f, read(t, 150, 1)[0])
        assertEquals(0f, read(t, 500, 1)[0])        // gap is silence
        assertEquals(3f, read(t, 1_000, 1)[0])
        assertEquals(0f, read(t, 1_010, 1)[0])      // beyond end
        assertEquals(0f, read(t, -5, 1)[0])         // before start
    }

    @Test
    fun `pre-delay read returns the block that played preDelay samples ago`() {
        val t = RenderTimeline(capacity = 10_000)
        t.append(ShortArray(160) { 7 }, captureNow = 0)
        val pre = 380L * 16                          // 380 ms @16 kHz
        // At capture index `pre`, the reference frame read is the one placed at 0.
        assertEquals(7f, read(t, pre - pre, 160).last())
    }

    @Test
    fun `samples older than the ring capacity read as silence`() {
        val t = RenderTimeline(capacity = 100)
        t.append(ShortArray(80) { 5 }, captureNow = 0)
        t.append(ShortArray(80) { 6 }, captureNow = 0)   // [80,160), overwrites ring slots 0..59
        assertEquals(0f, read(t, 10, 1)[0])              // 10 < 160-100 → too old
        assertEquals(5f, read(t, 70, 1)[0])
        assertEquals(6f, read(t, 159, 1)[0])
    }
}
