package com.travel2chicago.gemmapipeline.ast

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Wrap PCM int16 samples in a minimal RIFF/WAVE PCM header so the bytes can
 * be passed to `Content.AudioBytes(...)` for Gemma 4 E4B AST.
 *
 * Format is the one validated in Fase 0 (`gemma-ast-poc/GemmaTestViewModel.kt
 * generateSyntheticWav`): little-endian, PCM (`audioFormat = 1`), 16-bit
 * samples, 44-byte header. Header layout:
 *
 *   offset 0   : "RIFF"
 *   offset 4   : chunk size (uint32 LE) = 36 + dataSize
 *   offset 8   : "WAVE"
 *   offset 12  : "fmt "
 *   offset 16  : subchunk1 size (uint32 LE) = 16
 *   offset 20  : audio format (uint16 LE)   = 1  (PCM)
 *   offset 22  : channels (uint16 LE)
 *   offset 24  : sample rate (uint32 LE)
 *   offset 28  : byte rate (uint32 LE) = sampleRate * channels * 2
 *   offset 32  : block align (uint16 LE) = channels * 2
 *   offset 34  : bits per sample (uint16 LE) = 16
 *   offset 36  : "data"
 *   offset 40  : subchunk2 size (uint32 LE) = dataSize
 *   offset 44  : payload (int16 LE samples)
 */
object WavBuilder {
    private const val HEADER_SIZE = 44
    private const val BITS_PER_SAMPLE = 16
    private const val BYTES_PER_SAMPLE = BITS_PER_SAMPLE / 8
    private const val AUDIO_FORMAT_PCM: Short = 1

    /**
     * Build a complete WAV byte array from [samples].
     * Requires non-empty samples, positive sample rate, and 1 or 2 channels.
     * For stereo inputs [samples] must be interleaved (L, R, L, R, ...).
     */
    fun build(
        samples: ShortArray,
        sampleRate: Int = 16_000,
        channels: Int = 1,
    ): ByteArray {
        require(samples.isNotEmpty()) { "samples must not be empty" }
        require(sampleRate > 0) { "sampleRate must be positive, got $sampleRate" }
        require(channels in 1..2) { "channels must be 1 or 2, got $channels" }

        val dataSize = samples.size * BYTES_PER_SAMPLE
        val totalSize = HEADER_SIZE + dataSize
        val byteRate = sampleRate * channels * BYTES_PER_SAMPLE
        val blockAlign = (channels * BYTES_PER_SAMPLE).toShort()

        val buf = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)

        buf.put(RIFF)
        buf.putInt(36 + dataSize)
        buf.put(WAVE)
        buf.put(FMT)
        buf.putInt(16)
        buf.putShort(AUDIO_FORMAT_PCM)
        buf.putShort(channels.toShort())
        buf.putInt(sampleRate)
        buf.putInt(byteRate)
        buf.putShort(blockAlign)
        buf.putShort(BITS_PER_SAMPLE.toShort())
        buf.put(DATA)
        buf.putInt(dataSize)
        for (s in samples) buf.putShort(s)

        return buf.array()
    }

    private val RIFF = "RIFF".toByteArray(Charsets.US_ASCII)
    private val WAVE = "WAVE".toByteArray(Charsets.US_ASCII)
    private val FMT = "fmt ".toByteArray(Charsets.US_ASCII)
    private val DATA = "data".toByteArray(Charsets.US_ASCII)
}
