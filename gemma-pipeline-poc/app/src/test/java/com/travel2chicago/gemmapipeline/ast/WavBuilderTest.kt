package com.travel2chicago.gemmapipeline.ast

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Roundtrip tests for [WavBuilder]. The strategy is: build a WAV, then parse
 * the header back out byte-by-byte (without any third-party WAV library) and
 * assert that every field matches what we asked for.
 */
class WavBuilderTest {

    private fun parseHeader(wav: ByteArray): Header {
        require(wav.size >= 44) { "WAV too short: ${wav.size} bytes" }
        val buf = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        val riff = ByteArray(4).also { buf.get(it) }
        val chunkSize = buf.int
        val wave = ByteArray(4).also { buf.get(it) }
        val fmt = ByteArray(4).also { buf.get(it) }
        val subchunk1 = buf.int
        val audioFormat = buf.short
        val channels = buf.short
        val sampleRate = buf.int
        val byteRate = buf.int
        val blockAlign = buf.short
        val bitsPerSample = buf.short
        val data = ByteArray(4).also { buf.get(it) }
        val dataSize = buf.int
        return Header(
            riff = String(riff, Charsets.US_ASCII),
            chunkSize = chunkSize,
            wave = String(wave, Charsets.US_ASCII),
            fmt = String(fmt, Charsets.US_ASCII),
            subchunk1 = subchunk1,
            audioFormat = audioFormat,
            channels = channels,
            sampleRate = sampleRate,
            byteRate = byteRate,
            blockAlign = blockAlign,
            bitsPerSample = bitsPerSample,
            data = String(data, Charsets.US_ASCII),
            dataSize = dataSize,
        )
    }

    private data class Header(
        val riff: String, val chunkSize: Int, val wave: String,
        val fmt: String, val subchunk1: Int, val audioFormat: Short,
        val channels: Short, val sampleRate: Int, val byteRate: Int,
        val blockAlign: Short, val bitsPerSample: Short,
        val data: String, val dataSize: Int,
    )

    @Test
    fun `header magic numbers and constants match RIFF WAVE PCM`() {
        val samples = ShortArray(100) { it.toShort() }
        val wav = WavBuilder.build(samples)
        val h = parseHeader(wav)

        assertEquals("RIFF", h.riff)
        assertEquals("WAVE", h.wave)
        assertEquals("fmt ", h.fmt)
        assertEquals("data", h.data)
        assertEquals(16, h.subchunk1)               // PCM subchunk1 size
        assertEquals(1, h.audioFormat.toInt())      // 1 = PCM
        assertEquals(16, h.bitsPerSample.toInt())
    }

    @Test
    fun `sample rate, channel count, derived fields roundtrip correctly`() {
        val samples = ShortArray(1600) // 100 ms @ 16 kHz mono
        val wav = WavBuilder.build(samples, sampleRate = 16_000, channels = 1)
        val h = parseHeader(wav)

        assertEquals(16_000, h.sampleRate)
        assertEquals(1, h.channels.toInt())
        assertEquals(16_000 * 1 * 2, h.byteRate)    // sr * ch * bytesPerSample
        assertEquals(1 * 2, h.blockAlign.toInt())   // ch * bytesPerSample
    }

    @Test
    fun `chunk sizes match payload exactly`() {
        val n = 32_000   // 1 s @ 16 kHz, mono
        val wav = WavBuilder.build(ShortArray(n), sampleRate = 16_000)
        val h = parseHeader(wav)

        assertEquals(n * 2, h.dataSize)             // bytes of PCM
        assertEquals(36 + n * 2, h.chunkSize)       // RIFF chunk size
        assertEquals(44 + n * 2, wav.size)          // total file size
    }

    @Test
    fun `payload samples are written little-endian and preserve peak`() {
        // Pick a peak that's NOT symmetric in bytes so endianness mistakes
        // would be obvious. 0x1234 → LSB 0x34, MSB 0x12.
        val peak: Short = 0x1234
        val samples = shortArrayOf(0, peak, -peak, 0)
        val wav = WavBuilder.build(samples)
        // Re-extract PCM from offset 44.
        val pcm = ShortArray(samples.size)
        ByteBuffer.wrap(wav, 44, samples.size * 2)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()
            .get(pcm)
        assertArrayEquals(samples, pcm)
    }

    @Test
    fun `stereo doubles the byte rate and block align`() {
        val samples = ShortArray(200) // 100 frames stereo (L,R interleaved)
        val wav = WavBuilder.build(samples, sampleRate = 48_000, channels = 2)
        val h = parseHeader(wav)

        assertEquals(2, h.channels.toInt())
        assertEquals(48_000 * 2 * 2, h.byteRate)
        assertEquals(2 * 2, h.blockAlign.toInt())
        assertEquals(200 * 2, h.dataSize)
    }

    @Test
    fun `empty samples throws`() {
        assertThrows(IllegalArgumentException::class.java) {
            WavBuilder.build(ShortArray(0))
        }
    }

    @Test
    fun `non-positive sample rate throws`() {
        assertThrows(IllegalArgumentException::class.java) {
            WavBuilder.build(ShortArray(10), sampleRate = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            WavBuilder.build(ShortArray(10), sampleRate = -1)
        }
    }

    @Test
    fun `unsupported channel count throws`() {
        assertThrows(IllegalArgumentException::class.java) {
            WavBuilder.build(ShortArray(10), channels = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            WavBuilder.build(ShortArray(10), channels = 3)
        }
    }
}
