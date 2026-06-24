package com.travel2chicago.gemmapipeline.vad

import com.travel2chicago.gemmapipeline.audio.AudioFormat
import com.travel2chicago.gemmapipeline.audio.VadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Port of `tests/test_vad_processor.py` to Kotlin. Mocks the model with a
 * canned probability sequence so the state machine is exercised without
 * loading the real ONNX file. Each test class in Python becomes a `@Test`
 * group here.
 */
class SileroVadProcessorTest {

    /** Trivial fake — returns probabilities from the supplied iterator, 0f after exhaustion. */
    private class FakeModel(probabilities: List<Float>) : SileroVadModel {
        private val iter = probabilities.iterator()
        var resetCalls: Int = 0
            private set
        override fun probability(frame: ShortArray): Float =
            if (iter.hasNext()) iter.next() else 0f
        override fun reset() { resetCalls += 1 }
        override fun close() {}
    }

    private val format = AudioFormat()
    private val frame = ShortArray(512)
    // 32 ms @ 16 kHz = 1 frame; 64 ms = 2 frames.
    private fun config() = VadConfig(threshold = 0.5f, minSpeechMs = 32, minSilenceMs = 64)

    // ── State machine ──────────────────────────────────────────────────────

    @Test
    fun `initial state is SILENCE`() {
        val vad = SileroVadProcessor(config(), format, FakeModel(emptyList()))
        assertEquals(VadState.SILENCE, vad.state)
    }

    @Test
    fun `single high-probability frame flips SILENCE to SPEECH`() {
        val vad = SileroVadProcessor(config(), format, FakeModel(listOf(0.9f, 0.9f)))
        val t1 = vad.processFrame(frame)
        assertNotNull(t1)
        assertEquals(VadState.SPEECH, t1!!.state)
        assertEquals(VadState.SPEECH, vad.state)
    }

    @Test
    fun `flips back to SILENCE after enough silent frames`() {
        // probs: speech speech | silence x3 (need 2 silent for 64 ms threshold)
        val vad = SileroVadProcessor(config(), format, FakeModel(listOf(0.9f, 0.9f, 0.1f, 0.1f, 0.1f)))
        vad.processFrame(frame)
        vad.processFrame(frame)
        assertEquals(VadState.SPEECH, vad.state)

        // 1st silence frame: counter=1 < 2 → still SPEECH
        vad.processFrame(frame)
        assertEquals(VadState.SPEECH, vad.state)

        // 2nd silence frame: counter=2 ≥ 2 → flip
        vad.processFrame(frame)
        assertEquals(VadState.SILENCE, vad.state)
    }

    @Test
    fun `single low frame inside speech does NOT flip state (anti-flicker)`() {
        val vad = SileroVadProcessor(config(), format, FakeModel(listOf(0.9f, 0.9f, 0.1f, 0.9f, 0.9f)))
        vad.processFrame(frame)
        vad.processFrame(frame)
        assertEquals(VadState.SPEECH, vad.state)

        // One low-prob frame: silence counter=1 < 2, stays SPEECH
        vad.processFrame(frame)
        assertEquals(VadState.SPEECH, vad.state)

        // Two more high frames — still SPEECH (counter reset)
        vad.processFrame(frame)
        vad.processFrame(frame)
        assertEquals(VadState.SPEECH, vad.state)
    }

    @Test
    fun `low probability sequence keeps state at SILENCE`() {
        val vad = SileroVadProcessor(config(), format, FakeModel(listOf(0.1f, 0.1f, 0.1f)))
        for (i in 0 until 3) {
            val t = vad.processFrame(frame)
            assertNull("frame $i should not transition", t)
        }
        assertEquals(VadState.SILENCE, vad.state)
    }

    // ── Reset ──────────────────────────────────────────────────────────────

    @Test
    fun `reset clears state machine and delegates to model`() {
        val model = FakeModel(listOf(0.9f, 0.9f, 0.1f))
        val vad = SileroVadProcessor(config(), format, model)
        vad.processFrame(frame)
        vad.processFrame(frame)
        assertEquals(VadState.SPEECH, vad.state)

        vad.reset()
        assertEquals(VadState.SILENCE, vad.state)
        assertNull(vad.lastTransition)
        assertEquals(1, model.resetCalls)
    }

    // ── Properties ─────────────────────────────────────────────────────────

    @Test
    fun `lastTransition is null before any frames`() {
        val vad = SileroVadProcessor(VadConfig(), format, FakeModel(emptyList()))
        assertNull(vad.lastTransition)
    }

    @Test
    fun `lastTransition is populated after a transition`() {
        val vad = SileroVadProcessor(config(), format, FakeModel(listOf(0.9f, 0.9f)))
        vad.processFrame(frame)
        vad.processFrame(frame)
        val lt = vad.lastTransition
        assertNotNull(lt)
        assertEquals(VadState.SPEECH, lt!!.state)
    }

    @Test
    fun `lastTransition includes the probability that caused the flip`() {
        val vad = SileroVadProcessor(config(), format, FakeModel(listOf(0.77f, 0.78f)))
        vad.processFrame(frame)
        vad.processFrame(frame)
        assertEquals(0.78f, vad.lastTransition!!.probability, 1e-6f)
    }

    // ── Edge cases ─────────────────────────────────────────────────────────

    @Test
    fun `probability exactly at threshold counts as speech`() {
        val cfg = VadConfig(threshold = 0.5f, minSpeechMs = 32)
        val vad = SileroVadProcessor(cfg, format, FakeModel(listOf(0.5f)))
        val t = vad.processFrame(frame)
        assertNotNull(t)
        assertEquals(VadState.SPEECH, vad.state)
    }

    @Test
    fun `probability just below threshold stays at SILENCE`() {
        val cfg = VadConfig(threshold = 0.5f, minSpeechMs = 32)
        val vad = SileroVadProcessor(cfg, format, FakeModel(listOf(0.49f, 0.49f)))
        vad.processFrame(frame)
        vad.processFrame(frame)
        assertEquals(VadState.SILENCE, vad.state)
        assertFalse(vad.lastTransition != null)
    }
}
