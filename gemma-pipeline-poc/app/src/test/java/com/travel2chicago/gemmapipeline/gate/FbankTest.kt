package com.travel2chicago.gemmapipeline.gate

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/** Kotlin fbank vs the Python reference (tools/campp_features.py), which matches sherpa-onnx exactly. */
class FbankTest {

    private fun golden() = JSONObject(
        javaClass.classLoader!!.getResource("campp_fbank_golden.json")!!.readText(),
    )

    private fun signal() = FloatArray(32_000) {
        val t = it / 16_000.0
        (0.3 * sin(2 * PI * 220 * t) + 0.2 * sin(2 * PI * 1330 * t) * sin(2 * PI * 3 * t)).toFloat()
    }

    @Test fun `frame count matches kaldi snip_edges false`() {
        val feats = Fbank.compute(signal())
        assertEquals(golden().getInt("frames"), feats.size / Fbank.NUM_MEL)
    }

    @Test fun `log mel energies match the python reference`() {
        val g = golden()
        val feats = Fbank.compute(signal())
        val frame10 = g.getJSONArray("fbank_frame10")
        for (b in 0 until Fbank.NUM_MEL) {
            assertEquals("bin $b", frame10.getDouble(b), feats[10 * Fbank.NUM_MEL + b].toDouble(), 2e-3)
        }
        assertEquals(g.getDouble("fbank_mean"), feats.average(), 2e-3)
    }

    @Test fun `similarity helpers`() {
        val a = SpeakerGate.normalise(floatArrayOf(3f, 4f))
        assertEquals(1.0, SpeakerGate.dot(a, a).toDouble(), 1e-6)
        assertEquals(0.6f, a[0], 1e-6f)
    }
}
