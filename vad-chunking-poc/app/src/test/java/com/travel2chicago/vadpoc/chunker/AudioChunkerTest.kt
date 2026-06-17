package com.travel2chicago.vadpoc.chunker

import com.travel2chicago.vadpoc.audio.AudioEvent
import com.travel2chicago.vadpoc.audio.AudioFormat
import com.travel2chicago.vadpoc.audio.VadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Port of `tests/test_audio_chunker.py`. The Python tests use small ms values
 * (32–64 ms) to exercise transitions quickly; same approach here. Unlike the
 * Python prototype the Kotlin chunker returns the chunk from `feed`/`flush`
 * directly, so the "callback exception doesn't crash" test from Python is
 * replaced with a chunk-content assertion of equivalent value.
 */
class AudioChunkerTest {

    private val format = AudioFormat()
    private val BLOCK = format.blockSize  // 512
    private val SR = format.sampleRate    // 16_000

    private fun chunker(
        minChunkMs: Int = 100,
        maxChunkMs: Int = 2000,
        silenceEndMs: Int = 64,
        preRollMs: Int = 64,
    ) = AudioChunker(
        ChunkerConfig(minChunkMs = minChunkMs, maxChunkMs = maxChunkMs,
            silenceEndMs = silenceEndMs, preRollMs = preRollMs),
        format,
    )

    private fun speech(value: Int = 1): ShortArray = ShortArray(BLOCK) { value.toShort() }
    private fun silence(): ShortArray = ShortArray(BLOCK)

    // ── Basic ──────────────────────────────────────────────────────────────

    @Test
    fun `no emission on continuous silence`() {
        val c = chunker()
        var emitted = 0
        repeat(10) { if (c.feed(silence(), VadState.SILENCE) != null) emitted++ }
        assertEquals(0, emitted)
        assertFalse(c.isCollecting)
    }

    @Test
    fun `starts collecting on first SPEECH frame`() {
        val c = chunker()
        c.feed(speech(), VadState.SPEECH)
        assertTrue(c.isCollecting)
    }

    @Test
    fun `emits a chunk after speech then silence`() {
        val c = chunker(minChunkMs = 32, silenceEndMs = 64)
        val chunks = mutableListOf<AudioEvent.ChunkReady>()

        repeat(5) { c.feed(speech(), VadState.SPEECH)?.let(chunks::add) }
        repeat(5) { c.feed(silence(), VadState.SILENCE)?.let(chunks::add) }

        assertEquals(1, chunks.size)
        assertTrue(chunks[0].durationMs > 0)
        assertTrue(chunks[0].samples.isNotEmpty())
    }

    @Test
    fun `no emission before min-chunk is reached`() {
        val c = chunker(minChunkMs = 1000, silenceEndMs = 32)
        var emitted = 0

        c.feed(speech(), VadState.SPEECH)
        repeat(3) { if (c.feed(silence(), VadState.SILENCE) != null) emitted++ }

        assertEquals(0, emitted)
        assertTrue(c.isCollecting)
    }

    // ── Pre-roll ───────────────────────────────────────────────────────────

    @Test
    fun `pre-roll frames captured before SPEECH are prepended to the chunk`() {
        val c = chunker(minChunkMs = 32, silenceEndMs = 64, preRollMs = 64)
        var emitted: AudioEvent.ChunkReady? = null

        c.feed(ShortArray(BLOCK) { 99 }, VadState.SILENCE)
        c.feed(ShortArray(BLOCK) { 100 }, VadState.SILENCE)

        repeat(3) {
            val r = c.feed(speech(200), VadState.SPEECH)
            if (r != null) emitted = r
        }
        repeat(5) {
            val r = c.feed(silence(), VadState.SILENCE)
            if (r != null) emitted = r
        }

        assertNotNull(emitted)
        // Pre-roll (2 frames) + 3 speech + some silence ≥ 3 * BLOCK
        assertTrue("chunk should be longer than 3 blocks", emitted!!.samples.size > 3 * BLOCK)
    }

    // ── Max-duration trigger ───────────────────────────────────────────────

    @Test
    fun `forced split at max-chunk size`() {
        val c = chunker(minChunkMs = 32, maxChunkMs = 100)
        val maxSamples = (0.1 * SR).toInt()
        val framesNeeded = (maxSamples / BLOCK) + 2

        val chunks = mutableListOf<AudioEvent.ChunkReady>()
        repeat(framesNeeded) {
            c.feed(speech(), VadState.SPEECH)?.let(chunks::add)
        }

        assertTrue("expected ≥ 1 chunk, got ${chunks.size}", chunks.size >= 1)
        // First chunk should be close to maxChunkMs, never wildly larger.
        assertTrue(chunks[0].durationMs <= 200)
    }

    // ── Flush ──────────────────────────────────────────────────────────────

    @Test
    fun `flush returns the buffered chunk`() {
        val c = chunker()
        repeat(3) { c.feed(speech(), VadState.SPEECH) }
        val r = c.flush()
        assertNotNull(r)
        assertTrue(r!!.samples.isNotEmpty())
        assertFalse(c.isCollecting)
    }

    @Test
    fun `flush returns null when nothing buffered`() {
        val c = chunker()
        assertNull(c.flush())
    }

    @Test
    fun `flush returns null after a normal emission`() {
        val c = chunker(minChunkMs = 32, silenceEndMs = 32)
        c.feed(speech(), VadState.SPEECH)
        c.feed(speech(), VadState.SPEECH)
        var emitted = false
        repeat(3) { if (c.feed(silence(), VadState.SILENCE) != null) emitted = true }
        assertTrue(emitted)
        assertNull(c.flush())
    }

    // ── Multiple segments ──────────────────────────────────────────────────

    @Test
    fun `two utterances produce two chunks`() {
        val c = chunker(minChunkMs = 32, silenceEndMs = 64)
        val chunks = mutableListOf<AudioEvent.ChunkReady>()

        repeat(3) { c.feed(speech(1), VadState.SPEECH)?.let(chunks::add) }
        repeat(5) { c.feed(silence(), VadState.SILENCE)?.let(chunks::add) }

        repeat(3) { c.feed(speech(2), VadState.SPEECH)?.let(chunks::add) }
        repeat(5) { c.feed(silence(), VadState.SILENCE)?.let(chunks::add) }

        assertEquals(2, chunks.size)
    }

    // ── ChunkReady details ─────────────────────────────────────────────────

    @Test
    fun `emitted chunk reports correct peak value`() {
        val c = chunker(minChunkMs = 32, silenceEndMs = 32)
        // Speech frame with peak 1000.
        val loud = ShortArray(BLOCK) { 1000 }
        c.feed(loud, VadState.SPEECH)
        c.feed(loud, VadState.SPEECH)
        var emitted: AudioEvent.ChunkReady? = null
        repeat(3) { c.feed(silence(), VadState.SILENCE)?.let { emitted = it } }
        assertNotNull(emitted)
        assertEquals(1000, emitted!!.peak)
    }

    @Test
    fun `emitted chunk duration matches sample count divided by sample rate`() {
        val c = chunker(minChunkMs = 32, silenceEndMs = 32)
        c.feed(speech(), VadState.SPEECH)
        var emitted: AudioEvent.ChunkReady? = null
        repeat(3) { c.feed(silence(), VadState.SILENCE)?.let { emitted = it } }
        assertNotNull(emitted)
        val expectedMs = (emitted!!.samples.size.toLong() * 1000L / SR).toInt()
        assertEquals(expectedMs, emitted!!.durationMs)
    }
}
