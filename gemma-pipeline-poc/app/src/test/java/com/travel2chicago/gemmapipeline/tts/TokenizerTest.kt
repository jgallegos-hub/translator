package com.travel2chicago.gemmapipeline.tts

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class TokenizerTest {

    /**
     * Tiny synthetic vocab that mirrors the layout of the real Kokoro
     * vocab — PAD at 0, then a few IPA / punctuation characters at
     * arbitrary IDs. The real vocab has gaps in the IDs; tests don't
     * need to mimic that, only the lookup behaviour.
     */
    private fun make(maxLen: Int = 10): Tokenizer = Tokenizer(
        charToId = mapOf(
            "h" to 50,
            "ə" to 83,
            "l" to 54,
            "o" to 57,
            " " to 16,
        ),
        padId = 0,
        maxLen = maxLen,
    )

    @Test
    fun `known chars map to their ids one per character`() {
        val t = make()
        // "hələ" — 4 chars, no PAD wrapping at this layer.
        assertArrayEquals(longArrayOf(50, 83, 54, 83), t.tokenize("hələ"))
    }

    @Test
    fun `unknown character maps to padId and increments counter`() {
        val t = make()
        val out = t.tokenize("hZl")  // Z unknown
        assertArrayEquals(longArrayOf(50, 0, 54), out)
        assertEquals(1L, t.unkHits)
    }

    @Test
    fun `empty input returns empty LongArray`() {
        val t = make()
        assertEquals(0, t.tokenize("").size)
    }

    @Test
    fun `space is a real vocab entry and is tokenised`() {
        val t = make()
        assertArrayEquals(longArrayOf(50, 16, 54), t.tokenize("h l"))
    }

    @Test
    fun `input longer than budget is truncated`() {
        // budget = maxLen - 2 = 5
        val t = make(maxLen = 7)
        val out = t.tokenize("hələləl")  // 7 chars
        // Only first 5 fit.
        assertEquals(5, out.size)
        assertArrayEquals(longArrayOf(50, 83, 54, 83, 54), out)
    }

    @Test
    fun `unknown chars still consume budget when truncating`() {
        val t = make(maxLen = 5)  // budget = 3
        val out = t.tokenize("ZZhəl")
        // First 3 chars: Z Z h → 0, 0, 50. The rest is dropped.
        assertEquals(3, out.size)
        assertArrayEquals(longArrayOf(0, 0, 50), out)
        assertEquals(2L, t.unkHits)
    }
}
