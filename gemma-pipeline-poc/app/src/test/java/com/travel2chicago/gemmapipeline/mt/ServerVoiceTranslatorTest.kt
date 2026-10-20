package com.travel2chicago.gemmapipeline.mt

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerVoiceTranslatorTest {

    @Test fun `little-endian PCM bytes become samples`() {
        val (s, carry) = ServerVoiceTranslator.toShorts(byteArrayOf(0x01, 0x00, 0xFF.toByte(), 0x7F), null)
        assertArrayEquals(shortArrayOf(1, 32767), s)
        assertNull(carry)
    }

    @Test fun `odd byte is carried into the next frame`() {
        val (a, carry) = ServerVoiceTranslator.toShorts(byteArrayOf(0x02, 0x00, 0x03), null)
        assertArrayEquals(shortArrayOf(2), a)
        assertEquals(0x03.toByte(), carry)
        val (b, carry2) = ServerVoiceTranslator.toShorts(byteArrayOf(0x00), carry)
        assertArrayEquals(shortArrayOf(3), b)
        assertNull(carry2)
    }
}
