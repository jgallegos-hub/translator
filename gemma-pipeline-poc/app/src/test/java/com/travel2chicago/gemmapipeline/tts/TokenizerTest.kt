package com.travel2chicago.gemmapipeline.tts

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class TokenizerTest {

    private fun make(maxLen: Int = 10): Tokenizer = Tokenizer(
        phonemeToId = mapOf("h" to 3, "ə" to 2, "l" to 4, "oʊ" to 5),
        bosId = 0,
        eosId = 0,
        unkId = 1,
        maxLen = maxLen,
    )

    @Test
    fun `known phonemes round-trip with BOS and EOS`() {
        val t = make()
        // h ə l oʊ → BOS, 3, 2, 4, 5, EOS
        assertArrayEquals(longArrayOf(0, 3, 2, 4, 5, 0), t.tokenize(listOf("h", "ə", "l", "oʊ")))
    }

    @Test
    fun `unknown phoneme maps to unkId and increments counter`() {
        val t = make()
        val out = t.tokenize(listOf("h", "Z"))  // Z is unknown
        assertArrayEquals(longArrayOf(0, 3, 1, 0), out)
        assertEquals(1L, t.unkHits)
    }

    @Test
    fun `empty input still yields BOS and EOS`() {
        val t = make()
        assertArrayEquals(longArrayOf(0, 0), t.tokenize(emptyList()))
    }

    @Test
    fun `input is truncated to maxLen with BOS plus EOS reserved`() {
        val t = make(maxLen = 5)  // budget for phonemes = 3
        // Feed 6 phonemes — only first 3 should make it in.
        val out = t.tokenize(listOf("h", "ə", "l", "oʊ", "h", "ə"))
        assertEquals(5, out.size)
        assertEquals(0L, out[0])           // BOS
        assertEquals(3L, out[1])           // h
        assertEquals(2L, out[2])           // ə
        assertEquals(4L, out[3])           // l
        assertEquals(0L, out[4])           // EOS
    }

    @Test
    fun `unknown phonemes still consume budget when truncating`() {
        val t = make(maxLen = 4)  // budget = 2
        val out = t.tokenize(listOf("Z", "Y", "h", "ə"))
        assertEquals(4, out.size)
        assertEquals(0L, out[0])           // BOS
        assertEquals(1L, out[1])           // UNK
        assertEquals(1L, out[2])           // UNK
        assertEquals(0L, out[3])           // EOS
        assertEquals(2L, t.unkHits)
    }
}
