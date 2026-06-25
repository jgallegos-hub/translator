package com.travel2chicago.gemmapipeline.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhonemizerTest {

    private fun dict() = DictionaryPhonemizer(
        mapOf(
            "HELLO" to "həloʊ",
            "WORLD" to "wɜrld",
            "DON'T" to "doʊnt",
        )
    )

    @Test
    fun `single known word emits its phonemes`() {
        val p = dict()
        assertEquals("həloʊ", p.phonemize("hello"))
    }

    @Test
    fun `case is normalised to uppercase before lookup`() {
        val p = dict()
        assertEquals(p.phonemize("hello"), p.phonemize("HELLO"))
        assertEquals(p.phonemize("hello"), p.phonemize("Hello"))
    }

    @Test
    fun `multiple words are joined with a single space`() {
        val p = dict()
        // "həloʊ" + " " + "wɜrld" = "həloʊ wɜrld"
        assertEquals("həloʊ wɜrld", p.phonemize("hello world"))
    }

    @Test
    fun `punctuation is stripped`() {
        val p = dict()
        // "hello, world!" → same phonemes as "hello world"
        assertEquals(p.phonemize("hello world"), p.phonemize("hello, world!"))
    }

    @Test
    fun `smart quotes are normalised to plain apostrophes`() {
        val p = dict()
        // "don’t" (right single quote) should match "DON'T" in the dict
        assertEquals("doʊnt", p.phonemize("don’t"))
    }

    @Test
    fun `out-of-vocabulary word triggers schwa fallback and increments counter`() {
        val p = dict()
        val before = p.oovHits
        val out = p.phonemize("xyzzy")
        // 5 chars → 5 schwa fallback chars
        assertEquals("əəəəə", out)
        assertEquals(before + 1, p.oovHits)
    }

    @Test
    fun `empty or whitespace-only input returns empty string`() {
        val p = dict()
        assertEquals("", p.phonemize(""))
        assertEquals("", p.phonemize("   "))
        assertEquals("", p.phonemize("\t\n"))
    }

    @Test
    fun `apostrophe fallback strips apostrophe before second lookup`() {
        // Dict has "DONT" without apostrophe — phonemizer should find it on the second try.
        val p = DictionaryPhonemizer(mapOf("DONT" to "doʊnt"))
        val out = p.phonemize("don't")
        assertEquals("doʊnt", out)
        assertEquals(0L, p.oovHits)
    }

    @Test
    fun `mixed known and OOV words produce concatenated output and increment OOV counter`() {
        val p = dict()
        val out = p.phonemize("hello xyzzy world")
        // "həloʊ" + " " + "əəəəə" + " " + "wɜrld"
        assertEquals("həloʊ əəəəə wɜrld", out)
        assertEquals(1L, p.oovHits)
    }

    @Test
    fun `normalize converts em dash and en dash to hyphen`() {
        // Direct unit test on the normalize helper.
        assertEquals("a-b", DictionaryPhonemizer.normalize("a—b"))
        assertEquals("a-b", DictionaryPhonemizer.normalize("a–b"))
        assertTrue(!DictionaryPhonemizer.normalize("  hello  ").startsWith(" "))
    }
}
