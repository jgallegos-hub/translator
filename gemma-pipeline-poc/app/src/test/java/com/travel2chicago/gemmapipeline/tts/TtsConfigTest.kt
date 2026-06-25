package com.travel2chicago.gemmapipeline.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsConfigTest {

    @Test
    fun `defaults are sensible`() {
        val c = TtsConfig()
        assertEquals(24_000, c.sampleRate)
        assertEquals(510, c.maxTokens)
        assertEquals(4, c.queueCapacity)
        assertEquals("af_heart", c.voice)
        assertTrue(c.modelPath.endsWith("kokoro-v1.0.onnx"))
        assertTrue(c.voicesPath.endsWith("voices-v1.0.bin"))
    }

    @Test
    fun `modelPath joins dir and filename without double slashes`() {
        val c = TtsConfig(modelDirPath = "/sdcard/Download/kokoro_model")
        assertEquals("/sdcard/Download/kokoro_model/kokoro-v1.0.onnx", c.modelPath)
        assertEquals("/sdcard/Download/kokoro_model/voices-v1.0.bin", c.voicesPath)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `maxTokens out of range throws`() {
        TtsConfig(maxTokens = 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `voice blank throws`() {
        TtsConfig(voice = "  ")
    }
}
