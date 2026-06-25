package com.travel2chicago.gemmapipeline.tts

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class VoicesNpzTest {

    @get:Rule val tmp = TemporaryFolder()

    // ---- NPY parser ----------------------------------------------------------

    @Test
    fun `parseNpyFloat32 returns the flat float array of a valid v1 npy`() {
        val data = floatArrayOf(1.0f, -2.5f, 3.25f, 0.0f)
        val parsed = VoicesNpz.parseNpyFloat32(buildNpy(data), "test")
        assertNotNull(parsed)
        assertArrayEquals(data, parsed!!, 0.0f)
    }

    @Test
    fun `parseNpyFloat32 returns null on bad magic`() {
        val bytes = ByteArray(64) { 'X'.code.toByte() }
        assertNull(VoicesNpz.parseNpyFloat32(bytes, "bad"))
    }

    @Test
    fun `parseNpyFloat32 rejects non float32 dtype`() {
        // descr '<i4' = int32 LE — must be refused
        val header = "{'descr': '<i4', 'fortran_order': False, 'shape': (4,), }"
        val bytes = assembleNpy(header, ByteArray(16))
        assertNull(VoicesNpz.parseNpyFloat32(bytes, "i4"))
    }

    @Test
    fun `parseNpyFloat32 rejects fortran order`() {
        val header = "{'descr': '<f4', 'fortran_order': True, 'shape': (2,2), }"
        val bytes = assembleNpy(header, ByteArray(16))
        assertNull(VoicesNpz.parseNpyFloat32(bytes, "fortran"))
    }

    @Test
    fun `parseNpyFloat32 returns null when data length is not divisible by 4`() {
        val header = "{'descr': '<f4', 'fortran_order': False, 'shape': (1,), }"
        // 3 bytes of payload — not a whole float
        val bytes = assembleNpy(header, ByteArray(3))
        assertNull(VoicesNpz.parseNpyFloat32(bytes, "odd"))
    }

    // ---- VoiceStyles ---------------------------------------------------------

    @Test
    fun `styleFor returns the correct 256-float slice at tokenCount offset`() {
        // Fake full array: 511 positions × 256 dims, where slot i is filled
        // with value `i.toFloat()` so we can spot-check the offset.
        val full = FloatArray(KOKORO_MAX_POSITIONS * KOKORO_EMBEDDING_DIM)
        for (pos in 0 until KOKORO_MAX_POSITIONS) {
            for (d in 0 until KOKORO_EMBEDDING_DIM) {
                full[pos * KOKORO_EMBEDDING_DIM + d] = (pos * 1000 + d).toFloat()
            }
        }
        val styles = VoiceStyles(mapOf("af_test" to full))

        val sliceAt7 = styles.styleFor("af_test", 7)
        assertEquals(KOKORO_EMBEDDING_DIM, sliceAt7.size)
        assertEquals(7_000.0f, sliceAt7[0], 0.0f)
        assertEquals(7_000.0f + 255f, sliceAt7[255], 0.0f)
    }

    @Test
    fun `styleFor clamps tokenCount to maxPositions-1`() {
        val full = FloatArray(KOKORO_MAX_POSITIONS * KOKORO_EMBEDDING_DIM)
        full[(KOKORO_MAX_POSITIONS - 1) * KOKORO_EMBEDDING_DIM] = 999.0f
        val styles = VoiceStyles(mapOf("v" to full))

        // Way past 510 → clamped to slot 510
        val s = styles.styleFor("v", 5_000)
        assertEquals(999.0f, s[0], 0.0f)
    }

    @Test
    fun `styleFor throws on unknown voice`() {
        val styles = VoiceStyles(mapOf("only" to FloatArray(KOKORO_MAX_POSITIONS * KOKORO_EMBEDDING_DIM)))
        assertThrows(IllegalArgumentException::class.java) {
            styles.styleFor("missing", 0)
        }
    }

    // ---- NPZ end-to-end ------------------------------------------------------

    @Test
    fun `load reads every npy entry of an in-memory NPZ archive`() {
        val voiceA = FloatArray(KOKORO_MAX_POSITIONS * KOKORO_EMBEDDING_DIM) { it.toFloat() }
        val voiceB = FloatArray(KOKORO_MAX_POSITIONS * KOKORO_EMBEDDING_DIM) { -it.toFloat() }
        val npz = tmp.newFile("voices.bin")
        ZipOutputStream(npz.outputStream()).use { zip ->
            writeNpzEntry(zip, "af_heart.npy", buildNpy(voiceA, shape = "(511, 1, 256)"))
            writeNpzEntry(zip, "am_michael.npy", buildNpy(voiceB, shape = "(511, 1, 256)"))
        }

        val styles = VoicesNpz.load(npz)
        assertEquals(setOf("af_heart", "am_michael"), styles.availableVoices)
        // slot 0 dim 0 of voiceA = 0.0; slot 1 dim 0 of voiceB = -256.0
        assertEquals(0.0f, styles.styleFor("af_heart", 0)[0], 0.0f)
        assertEquals(-256.0f, styles.styleFor("am_michael", 1)[0], 0.0f)
    }

    @Test
    fun `load skips non-npy entries silently`() {
        val voice = FloatArray(KOKORO_MAX_POSITIONS * KOKORO_EMBEDDING_DIM) { 1.0f }
        val npz = tmp.newFile("mixed.bin")
        ZipOutputStream(npz.outputStream()).use { zip ->
            writeNpzEntry(zip, "readme.txt", "hello".toByteArray())
            writeNpzEntry(zip, "af_heart.npy", buildNpy(voice, shape = "(511, 1, 256)"))
        }

        val styles = VoicesNpz.load(npz)
        assertEquals(setOf("af_heart"), styles.availableVoices)
    }

    // ---- helpers -------------------------------------------------------------

    /** Builds a valid NPY v1.0 byte stream containing `floats` little-endian. */
    private fun buildNpy(
        floats: FloatArray,
        shape: String = "(${floats.size},)",
    ): ByteArray {
        val header = "{'descr': '<f4', 'fortran_order': False, 'shape': $shape, }"
        val payload = ByteBuffer.allocate(floats.size * 4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply { for (f in floats) putFloat(f) }
            .array()
        return assembleNpy(header, payload)
    }

    /** Assemble an NPY v1.0 with the given header text and raw payload. */
    private fun assembleNpy(headerText: String, payload: ByteArray): ByteArray {
        // numpy pads the header so total `10 + headerLen` is a multiple of 64
        // and terminates it with '\n'. Replicate to be faithful, but the
        // parser doesn't require it.
        val withNewline = headerText + "\n"
        val baseLen = 10 + withNewline.length
        val pad = if (baseLen % 64 == 0) 0 else 64 - (baseLen % 64)
        val header = headerText + " ".repeat(pad) + "\n"
        val headerLen = header.length
        val out = ByteArrayOutputStream()
        out.write(
            byteArrayOf(
                0x93.toByte(),
                'N'.code.toByte(), 'U'.code.toByte(), 'M'.code.toByte(),
                'P'.code.toByte(), 'Y'.code.toByte(),
            ),
        )
        out.write(byteArrayOf(1, 0)) // version 1.0
        out.write(headerLen and 0xff)
        out.write((headerLen shr 8) and 0xff)
        out.write(header.toByteArray(Charsets.US_ASCII))
        out.write(payload)
        return out.toByteArray()
    }

    private fun writeNpzEntry(zip: ZipOutputStream, name: String, data: ByteArray) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(data)
        zip.closeEntry()
    }

    @Test
    fun `temporary folder rule exposes a real file`() {
        // Sanity: ensure the rule worked.
        val f: File = tmp.newFile("probe.txt")
        assertTrue(f.exists())
    }
}
