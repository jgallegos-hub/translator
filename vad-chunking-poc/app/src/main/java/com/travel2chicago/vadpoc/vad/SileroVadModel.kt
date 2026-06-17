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

    override fun probability(frame: ShortArray): Float {
        require(frame.size == FRAME_SIZE) {
            "Silero v5 @ 16 kHz expects exactly $FRAME_SIZE samples per frame, got ${frame.size}"
        }

        // int16 → float32 normalized to [-1, 1].
        val floatFrame = FloatArray(FRAME_SIZE)
        for (i in 0 until FRAME_SIZE) floatFrame[i] = frame[i] / 32768f

        // All tensor allocations go inside the try so the finally closes
        // whatever was allocated even if a later createTensor() throws.
        var inputTensor: OnnxTensor? = null
        var srTensor: OnnxTensor? = null
        var stateTensor: OnnxTensor? = null
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
                // Output "output" shape [1, 1] — ORT boxes float tensors as
                // nested arrays even when input was buffer-backed.
                @Suppress("UNCHECKED_CAST")
                val probArr = results[0].value as Array<FloatArray>
                val prob = probArr[0][0]

                // Output "stateN" shape [2, 1, 128] — flatten back for next call.
                @Suppress("UNCHECKED_CAST")
                val nextRaw = results[1].value as Array<Array<FloatArray>>
                val flat = FloatArray(STATE_SIZE)
                var idx = 0
                for (layer in nextRaw) {
                    for (batch in layer) {
                        System.arraycopy(batch, 0, flat, idx, batch.size)
                        idx += batch.size
                    }
                }
                stateData = flat
                return prob
            }
        } catch (e: OrtException) {
            Log.e(TAG, "ONNX inference failed", e)
            throw e
        } finally {
            inputTensor?.close()
            srTensor?.close()
            stateTensor?.close()
        }
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
            return SileroVadOnnxModel(env, session, sampleRate)
        }
    }
}
