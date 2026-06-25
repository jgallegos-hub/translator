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
}
