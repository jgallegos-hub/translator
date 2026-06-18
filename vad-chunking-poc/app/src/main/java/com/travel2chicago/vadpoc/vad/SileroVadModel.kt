package com.travel2chicago.vadpoc.vad

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import java.io.ByteArrayOutputStream
import java.nio.FloatBuffer
import java.nio.LongBuffer

private const val TAG = "SileroVadModel"

/**
 * Inference interface for Silero VAD. Single-responsibility wrapper: turn a
 * 512-sample int16 frame into a speech probability, carry state across calls,
 * reset on demand.
 *
 * Keeping this as an interface lets unit tests inject a deterministic stub
 * without touching ONNX Runtime — see [SileroVadProcessorTest].
 */
interface SileroVadModel : AutoCloseable {
    /**
     * Run inference on one frame and return Silero's speech probability in
     * [0, 1]. Internal state is carried across consecutive calls so the model
     * sees the frame as part of a temporal sequence.
     */
    fun probability(frame: ShortArray): Float

    /** Reset the internal LSTM state — call between separate audio sessions. */
    fun reset()
}

/**
 * Production Silero VAD wrapper backed by ONNX Runtime Android.
 *
 * Silero v5 inputs / outputs:
 *   - input  "input": float32 [1, 512]      — normalized PCM samples
 *   - input  "sr":    int64    [1]          — sample rate (16000)
 *   - input  "state": float32 [2, 1, 128]   — LSTM state carry-over
 *   - output "output": float32 [1, 1]       — speech probability
 *   - output "stateN": float32 [2, 1, 128]  — next state
 *
 * Tensors are built via the explicit-shape `OnnxTensor.createTensor(env,
 * Buffer, long[])` overload — the generic `Object` overload of ORT Android
 * 1.19 does not accept nested Java arrays for multi-dim float tensors. The
 * state is kept as a flat [FloatArray] of length 2*1*128 and re-shaped per
 * call.
 */
class SileroVadOnnxModel private constructor(
    private val env: OrtEnvironment,
    private val session: OrtSession,
    private val sampleRate: Int,
) : SileroVadModel {

    /** Flat LSTM state, length 2 * 1 * 128 = 256. Carried across calls; zeroed by [reset]. */
    private var stateData: FloatArray = FloatArray(STATE_SIZE)

    /** First-frame conversion diagnostics — fires exactly once per session. */
    private var loggedFirstFrameDiag: Boolean = false

    /** Counts inferences for the per-call diagnostic log. */
    private var inferenceCount: Long = 0

    override fun probability(frame: ShortArray): Float {
        require(frame.size == FRAME_SIZE) {
            "Silero v5 @ 16 kHz expects exactly $FRAME_SIZE samples per frame, got ${frame.size}"
        }

        // int16 → float32 normalized to [-1, 1].
        // `.toFloat()` is explicit because `Short / Float` in Kotlin goes
        // Short → Int → Float, which is correct but easy to misread.
        val floatFrame = FloatArray(FRAME_SIZE)
        for (i in 0 until FRAME_SIZE) floatFrame[i] = frame[i].toFloat() / 32768f

        if (!loggedFirstFrameDiag) {
            loggedFirstFrameDiag = true
            var shortPeak = 0
            for (s in frame) {
                val a = if (s.toInt() == Short.MIN_VALUE.toInt()) Short.MAX_VALUE.toInt() else kotlin.math.abs(s.toInt())
                if (a > shortPeak) shortPeak = a
            }
            var fMin = Float.POSITIVE_INFINITY
            var fMax = Float.NEGATIVE_INFINITY
            for (f in floatFrame) {
                if (f < fMin) fMin = f
                if (f > fMax) fMax = f
            }
            Log.i(TAG, "First-frame conversion: int16 peak=$shortPeak, " +
                "float range=[${"%.4f".format(fMin)}, ${"%.4f".format(fMax)}], " +
                "frame size=${frame.size}, declared sampleRate=$sampleRate Hz")
        }

        // All tensor allocations go inside the try so the finally closes
        // whatever was allocated even if a later createTensor() throws.
        var inputTensor: OnnxTensor? = null
        var srTensor: OnnxTensor? = null
        var stateTensor: OnnxTensor? = null
        // Snapshot state-in checksum for diagnostic — useful to confirm the
        // state IS mutating across calls (a stuck state explains p=0 forever).
        val stateInPeak = if (inferenceCount < 10) absMax(stateData) else 0f
        try {
            inputTensor = OnnxTensor.createTensor(
                env, FloatBuffer.wrap(floatFrame), longArrayOf(1L, FRAME_SIZE.toLong()))
            srTensor = OnnxTensor.createTensor(
                env, LongBuffer.wrap(longArrayOf(sampleRate.toLong())), longArrayOf(1L))
            stateTensor = OnnxTensor.createTensor(
                env, FloatBuffer.wrap(stateData), longArrayOf(2L, 1L, 128L))

            val inputs = mapOf(
                "input" to inputTensor,
                "sr" to srTensor,
                "state" to stateTensor,
            )
            session.run(inputs).use { results ->
                // Access by NAME so we don't depend on ORT iteration order —
                // if [0] is the state and [1] is the prob we'd misread the
                // probability as a giant float and silently get garbage.
                val probOnnx = results.get("output").orElseThrow {
                    OrtException("Model output 'output' missing — names=" +
                        results.map { it.key }.joinToString())
                }
                val stateOnnx = results.get("stateN").orElseThrow {
                    OrtException("Model output 'stateN' missing — names=" +
                        results.map { it.key }.joinToString())
                }

                @Suppress("UNCHECKED_CAST")
                val probArr = probOnnx.value as Array<FloatArray>
                val prob = probArr[0][0]

                @Suppress("UNCHECKED_CAST")
                val nextRaw = stateOnnx.value as Array<Array<FloatArray>>
                val flat = FloatArray(STATE_SIZE)
                var idx = 0
                for (layer in nextRaw) {
                    for (batch in layer) {
                        System.arraycopy(batch, 0, flat, idx, batch.size)
                        idx += batch.size
                    }
                }
                stateData = flat

                inferenceCount += 1
                // First 10 inferences get a detailed log so we can SEE the
                // probability stream + verify the state is actually mutating.
                if (inferenceCount <= 10) {
                    val stateOutPeak = absMax(stateData)
                    Log.i(TAG, "Inference #$inferenceCount: prob=${"%.6f".format(prob)} " +
                        "probShape=[${probArr.size}, ${probArr[0].size}] " +
                        "stateIn|peak|=${"%.4f".format(stateInPeak)} " +
                        "stateOut|peak|=${"%.4f".format(stateOutPeak)}")
                }
                return prob
            }
        } catch (e: OrtException) {
            Log.e(TAG, "ONNX inference failed (#${inferenceCount + 1})", e)
            throw e
        } finally {
            inputTensor?.close()
            srTensor?.close()
            stateTensor?.close()
        }
    }

    private fun absMax(a: FloatArray): Float {
        var m = 0f
        for (v in a) {
            val av = if (v < 0) -v else v
            if (av > m) m = av
        }
        return m
    }

    override fun reset() {
        stateData = FloatArray(STATE_SIZE)
    }

    override fun close() {
        try { session.close() } catch (_: Exception) {}
        // OrtEnvironment is a process-wide singleton via getEnvironment().
        // We do not close it — other consumers in the same process may need it.
    }

    companion object {
        /** Silero v5 @ 16 kHz: exactly 512 samples per inference call. */
        const val FRAME_SIZE = 512
        private const val STATE_SIZE = 2 * 1 * 128

        /** Asset path inside the APK. Place the file at `app/src/main/assets/silero_vad.onnx`. */
        const val DEFAULT_ASSET_NAME = "silero_vad.onnx"

        /**
         * Load the Silero model from APK [assetName]. Throws if the asset is
         * missing or ONNX session creation fails — caller decides how to
         * surface that (typically a one-shot error in the UI).
         */
        fun loadFromAssets(
            context: Context,
            assetName: String = DEFAULT_ASSET_NAME,
            sampleRate: Int = 16_000,
        ): SileroVadOnnxModel {
            Log.i(TAG, "Loading Silero VAD model from assets/$assetName")
            val modelBytes = context.assets.open(assetName).use { input ->
                val buffer = ByteArrayOutputStream(2 shl 20)
                input.copyTo(buffer)
                buffer.toByteArray()
            }
            Log.i(TAG, "Model bytes loaded: ${modelBytes.size}")

            val env = OrtEnvironment.getEnvironment()
            val options = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(2)
            }
            val session = env.createSession(modelBytes, options)

            // Dump session metadata so we can confirm the model has the
            // input/output names we're calling it with. Mismatches here
            // would make session.run() throw — but we want it visible up
            // front, before the first inference.
            try {
                val ins = session.inputInfo
                Log.i(TAG, "Session INPUTS (${ins.size}):")
                for ((name, info) in ins) {
                    Log.i(TAG, "  '$name' → ${info.info}")
                }
                val outs = session.outputInfo
                Log.i(TAG, "Session OUTPUTS (${outs.size}):")
                for ((name, info) in outs) {
                    Log.i(TAG, "  '$name' → ${info.info}")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Could not enumerate session inputs/outputs", t)
            }

            val instance = SileroVadOnnxModel(env, session, sampleRate)
            instance.selfTest()
            return instance
        }
    }

    /**
     * Synthetic smoke test: feeds 3 silence frames followed by 3 frames of
     * loud white noise. If the model is healthy:
     *   - silence probs should stay near 0
     *   - noise probs should rise (Silero treats sustained energy as
     *     potential speech with some probability > 0.05)
     * If both stay at 0.0000 forever, the model + inputs are mis-wired in
     * a way that doesn't depend on the live microphone.
     */
    fun selfTest() {
        Log.i(TAG, "── Self-test ────────────────────────────────────────")
        val silence = ShortArray(FRAME_SIZE)
        repeat(3) {
            val p = probability(silence)
            Log.i(TAG, "  silence frame → p=${"%.6f".format(p)}")
        }
        // Pseudo-random noise around ±8000 (≈ 24% peak) — fixed seed so
        // every run is comparable.
        val rng = java.util.Random(12345L)
        val noise = ShortArray(FRAME_SIZE) { (rng.nextInt(16000) - 8000).toShort() }
        repeat(3) {
            val p = probability(noise)
            Log.i(TAG, "  loud noise frame → p=${"%.6f".format(p)}")
        }
        // Restore clean state so the real session starts from zeros.
        reset()
        inferenceCount = 0
        loggedFirstFrameDiag = false
        Log.i(TAG, "── Self-test complete (state reset) ─────────────────")
    }
}
