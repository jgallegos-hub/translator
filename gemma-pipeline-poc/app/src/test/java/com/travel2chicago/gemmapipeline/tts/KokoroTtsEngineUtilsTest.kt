package com.travel2chicago.gemmapipeline.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KokoroTtsEngineUtilsTest {

    @Test
    fun `splitIntoSentences keeps terminator with each sentence`() {
        val parts = KokoroOnnxEngine.splitIntoSentences("Hello world. How are you? Fine!")
        assertEquals(3, parts.size)
        assertTrue(parts[0].endsWith("."))
        assertTrue(parts[1].endsWith("?"))
        assertTrue(parts[2].endsWith("!"))
    }

    @Test
    fun `splitIntoSentences returns single entry when no terminator`() {
        val parts = KokoroOnnxEngine.splitIntoSentences("hello world")
        assertEquals(listOf("hello world"), parts)
    }

    @Test
    fun `concatToInt16 quantises float PCM to int16 and concatenates`() {
        val a = floatArrayOf(0.0f, 0.5f, -0.5f, 1.0f, -1.0f)
        val b = floatArrayOf(0.25f, -0.25f)
        val out = KokoroOnnxEngine.concatToInt16(listOf(a, b))
        assertEquals(a.size + b.size, out.size)
        assertEquals(0.toShort(), out[0])
        assertEquals((0.5f * 32767f).toInt().toShort(), out[1])
        assertEquals((-0.5f * 32767f).toInt().toShort(), out[2])
        assertEquals(32767.toShort(), out[3])
        // Clipping bound: float -1.0 maps to -32767, not -32768.
        assertEquals((-32767).toShort(), out[4])
    }

    @Test
    fun `concatToInt16 clips values beyond range`() {
        val a = floatArrayOf(2.0f, -2.0f, 1.5f, -1.5f)
        val out = KokoroOnnxEngine.concatToInt16(listOf(a))
        // All four values clipped to [-1, 1] before quantisation.
        assertEquals(32767.toShort(), out[0])
        assertEquals((-32767).toShort(), out[1])
        assertEquals(32767.toShort(), out[2])
        assertEquals((-32767).toShort(), out[3])
    }

    @Test
    fun `concatToInt16 of empty list returns empty array`() {
        val out = KokoroOnnxEngine.concatToInt16(emptyList())
        assertEquals(0, out.size)
    }

    @Test
    fun `splitForStreaming cuts at clause punctuation and keeps all words`() {
        val text = "Okay, I see that the transcription occurred, but the audio arrived late. Thanks!"
        val parts = KokoroOnnxEngine.splitForStreaming(text)
        assertEquals(
            listOf("Okay, I see that the transcription occurred,", "but the audio arrived late.", "Thanks!"),
            parts,
        )
        assertEquals(text.split(" ").filter { it.isNotBlank() }, parts.joinToString(" ").split(" "))
    }

    @Test
    fun `splitForStreaming caps long clauses at maxWords in near-equal parts`() {
        val text = (1..25).joinToString(" ") { "w$it" } + "."
        val parts = KokoroOnnxEngine.splitForStreaming(text, maxWords = 12)
        assertEquals(3, parts.size)
        assertTrue(parts.all { it.split(" ").size <= 12 })
        assertEquals(text, parts.joinToString(" "))
    }

    @Test
    fun `splitForStreaming leaves short sentences untouched`() {
        assertEquals(listOf("Hello, how are you?"), KokoroOnnxEngine.splitForStreaming("Hello, how are you?"))
        assertEquals(listOf("Stop."), KokoroOnnxEngine.splitForStreaming("Stop."))
    }
}
