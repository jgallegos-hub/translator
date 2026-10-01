package com.travel2chicago.gemmapipeline.ast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EchoTextHistoryTest {

    @Test
    fun `identical sentence matches with similarity 1`() {
        val h = EchoTextHistory(clockMs = { 0L })
        h.record("Good morning, how are you?")
        val m = h.findMatch("good morning how are you", 0.6, 15_000L)
        assertNotNull(m)
        assertEquals(1.0, m!!.similarity, 1e-9)
    }

    @Test
    fun `partial echo is caught by containment`() {
        val h = EchoTextHistory(clockMs = { 0L })
        h.record("I would like to buy two tickets for the museum, please.")
        // Chunk boundary cut the echo in half.
        assertNotNull(h.findMatch("two tickets for the museum", 0.6, 15_000L))
    }

    @Test
    fun `short reply is not swallowed by containment`() {
        val h = EchoTextHistory(clockMs = { 0L })
        h.record("Yes, I think the hotel is close to the station.")
        assertNull(h.findMatch("Yes.", 0.6, 15_000L))
    }

    @Test
    fun `unrelated sentence does not match`() {
        val h = EchoTextHistory(clockMs = { 0L })
        h.record("The museum opens at nine.")
        assertNull(h.findMatch("Where can I buy a train ticket?", 0.6, 15_000L))
    }

    @Test
    fun `entries expire after the window and are pruned`() {
        var now = 0L
        val h = EchoTextHistory(clockMs = { now })
        h.record("The museum opens at nine.")
        now = 15_000L
        assertNotNull(h.findMatch("The museum opens at nine.", 0.6, 15_000L))
        now = 15_001L
        assertNull(h.findMatch("The museum opens at nine.", 0.6, 15_000L))
        assertEquals(0, h.size)
    }

    @Test
    fun `re-recording the same text refreshes instead of duplicating`() {
        var now = 0L
        val h = EchoTextHistory(clockMs = { now })
        h.record("Hello there.")
        now = 10_000L
        h.record("Hello there!")
        assertEquals(1, h.size)
        now = 20_000L
        assertNotNull(h.findMatch("hello there", 0.6, 15_000L))
    }

    @Test
    fun `history is capped at maxEntries`() {
        val h = EchoTextHistory(clockMs = { 0L }, maxEntries = 3)
        for (i in 1..5) h.record("sentence number $i")
        assertEquals(3, h.size)
    }

    @Test
    fun `skip normalisation helpers`() {
        assertTrue(AstChunkRouter.isSkipReply(" SKIP. "))
        assertTrue(AstChunkRouter.isSkipReply("skip"))
        assertTrue(AstChunkRouter.isSkipReply("English: SKIP"))
        assertFalse(AstChunkRouter.isSkipReply("Skip the line."))
        assertTrue(AstChunkRouter.isSkipPrefix(""))
        assertTrue(AstChunkRouter.isSkipPrefix("Sk"))
        assertFalse(AstChunkRouter.isSkipPrefix("Hello"))
    }

    @Test
    fun `activePrompt is unchanged when skipNonSpanish is off`() {
        val off = AstConfig(skipNonSpanish = false)
        assertEquals(off.prompt, off.activePrompt)
        assertEquals(off.legacyPrompt, off.copy(useOfficialAstPrompt = false).activePrompt)
        val on = AstConfig()
        assertTrue(on.activePrompt.startsWith(on.prompt))
        assertTrue(on.activePrompt.contains("English: SKIP"))
    }
}
