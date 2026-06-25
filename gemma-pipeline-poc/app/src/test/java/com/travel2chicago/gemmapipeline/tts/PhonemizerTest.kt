package com.travel2chicago.gemmapipeline.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhonemizerTest {

    private fun dict() = DictionaryPhonemizer(
        mapOf(
            "hello" to listOf("h", "ə", "l", "oʊ"),
            "world" to listOf("w", "ɜ", "r", "l", "d"),
            "don't" to listOf("d", "oʊ", "n", "t"),
        )
    )

    @Test
    fun `single known word emits its phonemes`() {
        val p = dict()
        assertEquals(listOf("h", "ə", "l", "oʊ"), p.phonemize("hello"))
    }

    @Test
    fun `case is normalised before lookup`() {
        val p = dict()
        assertEquals(p.phonemize("hello"), p.phonemize("HELLO"))
        assertEquals(p.phonemize("hello"), p.phonemize("Hello"))
    }

    @Test
    fun `multiple words concatenate phonemes in order`() {
        val p = dict()
        val out = p.phonemize("hello world")
        // h ə l oʊ + w ɜ r l d = 9 phonemes
        assertEquals(9, out.size)
        assertEquals("h", out[0])
        assertEquals("w", out[4])
    }

    @Test
    fun `punctuation is stripped`() {
        val p = dict()
        // "hello, world!" → same as "hello world"
        val out = p.phonemize("hello, world!")
        assertEquals(p.phonemize("hello world"), out)
    }

    @Test
    fun `smart quotes are normalised to plain apostrophes`() {
        val p = dict()
        // "don’t" (right single quote) should match "don't" in the dict
        assertEquals(listOf("d", "oʊ", "n", "t"), p.phonemize("don’t"))
    }

    @Test
    fun `out-of-vocabulary word triggers LTS fallback and increments counter`() {
        val p = dict()
        val before = p.oovHits
        val out = p.phonemize("xyzzy")
        // 5 chars → 5 schwa fallback phonemes
        assertEquals(5, out.size)
        assertTrue(out.all { it == "ə" })
        assertEquals(before + 1, p.oovHits)
    }

    @Test
    fun `empty or whitespace-only input returns empty list`() {
        val p = dict()
        assertEquals(emptyList<String>(), p.phonemize(""))
        assertEquals(emptyList<String>(), p.phonemize("   "))
        assertEquals(emptyList<String>(), p.phonemize("\t\n"))
    }

    @Test
    fun `apostrophe fallback strips apostrophe before second lookup`() {
        // Dict has "dont" without apostrophe — phonemizer should find it.
        val p = DictionaryPhonemizer(
            mapOf("dont" to listOf("d", "oʊ", "n", "t"))
        )
        val out = p.phonemize("don't")
        // Should NOT take the OOV path — should hit the stripped-apostrophe lookup.
        assertEquals(listOf("d", "oʊ", "n", "t"), out)
        assertEquals(0L, p.oovHits)
    }
}
