package com.travel2chicago.gemmapipeline.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class EchoDatasetRecorderTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun readWav(f: File): ShortArray {
        val bytes = f.readBytes()
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(bytes, 0, 4))
        assertEquals(16_000, bb.getInt(24))
        val dataLen = bb.getInt(40)
        assertEquals(bytes.size - 44, dataLen)
        return ShortArray(dataLen / 2) { bb.getShort(44 + it * 2) }
    }

    @Test
    fun `reference is placed on the mic timeline and padded to equal length`() {
        val dir = tmp.newFolder()
        val rec = EchoDatasetRecorder(dir)
        rec.onMic(ShortArray(1_600) { 1 })              // 100 ms of mic
        rec.onRef(ShortArray(2_400) { 1_000 }, 24_000)  // 100 ms of TTS @24k → 1600 @16k at sample 1600
        rec.onRef(ShortArray(2_400) { 2_000 }, 24_000)  // queued → contiguous at 3200
        rec.onMic(ShortArray(8_000) { 1 })              // mic advances to 9600
        rec.mark("chirp_1")
        rec.close()

        val mic = readWav(File(dir, "mic.wav"))
        val ref = readWav(File(dir, "ref.wav"))
        assertEquals(9_600, mic.size)
        assertEquals(mic.size, ref.size)
        assertEquals(0, ref[1_599].toInt())
        assertEquals(1_000, ref[1_600].toInt())
        assertEquals(2_000, ref[3_200].toInt())
        assertEquals(0, ref[4_800].toInt())

        val events = File(dir, "events.csv").readLines()
        assertEquals(listOf("ref,1600,1600,", "ref,3200,1600,", "mark,9600,0,chirp_1"), events.drop(1))
    }

    @Test
    fun `reference after an underrun starts at the current mic position`() {
        val dir = tmp.newFolder()
        val rec = EchoDatasetRecorder(dir)
        rec.onRef(ShortArray(2_400) { 5 }, 24_000)      // at 0, ends 1600
        rec.onMic(ShortArray(16_000))                   // 1 s later, track drained
        rec.onRef(ShortArray(2_400) { 7 }, 24_000)      // must start at 16000, not 1600
        rec.close()
        val ref = readWav(File(dir, "ref.wav"))
        assertEquals(7, ref[16_000].toInt())
        assertEquals(0, ref[15_999].toInt())
    }

    @Test
    fun `chirp has the requested duration and stays within -6 dBFS`() {
        val c = EchoDatasetRecorder.chirp(24_000, durationMs = 300)
        assertEquals(7_200, c.size)
        assertTrue(c.all { kotlin.math.abs(it.toInt()) <= 16_000 })
        assertTrue(c.maxOf { it.toInt() } > 15_000)
    }
}
