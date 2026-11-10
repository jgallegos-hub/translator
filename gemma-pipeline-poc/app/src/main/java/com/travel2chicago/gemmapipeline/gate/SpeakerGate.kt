package com.travel2chicago.gemmapipeline.gate

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.util.Log
import org.json.JSONArray
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

private const val TAG = "SpeakerGate"

/**
 * "Is it Abraham?" gate before ASR (2026-11-10). CAM++ speaker embeddings (3D-Speaker zh_en advanced)
 * run on the app's own ONNX Runtime 1.23.2 — sherpa-onnx's Android build bundles ORT 1.28.2, which would
 * clash with ours — with a Kaldi fbank front end ported from `tools/campp_features.py`, whose embeddings
 * match sherpa-onnx's exactly (cosine 1.0000 on 66 real chunks).
 *
 * On Abraham's labelled set (tools/gate_eval.py): his chunks scored >= 0.48 against his enrolment,
 * everything else (English speakers, another Spanish voice, Eric's echo, his laughter) <= 0.35.
 */
class SpeakerGate private constructor(
    private val env: OrtEnvironment,
    private val session: OrtSession,
    private val profileFile: File,
) : AutoCloseable {

    private val profile = ArrayList<FloatArray>()
    val enrolledSeconds: Float get() = enrolledSamples / 16_000f
    private var enrolledSamples = 0L
    val hasProfile: Boolean get() = synchronized(profile) { profile.isNotEmpty() }

    init {
        loadProfile()
    }

    /** L2-normalised 192-dim embedding of 16 kHz PCM. */
    fun embed(pcm: ShortArray): FloatArray {
        val x = FloatArray(pcm.size) { pcm[it] / 32768f }
        val feats = Fbank.compute(x)
        val frames = feats.size / Fbank.NUM_MEL
        // feature_normalize_type: global-mean
        for (b in 0 until Fbank.NUM_MEL) {
            var mean = 0.0
            for (t in 0 until frames) mean += feats[t * Fbank.NUM_MEL + b]
            mean /= frames
            for (t in 0 until frames) feats[t * Fbank.NUM_MEL + b] -= mean.toFloat()
        }
        OnnxTensor.createTensor(env, FloatBuffer.wrap(feats), longArrayOf(1, frames.toLong(), Fbank.NUM_MEL.toLong())).use { input ->
            session.run(mapOf("x" to input)).use { out ->
                @Suppress("UNCHECKED_CAST")
                val e = (out[0].value as Array<FloatArray>)[0]
                return normalise(e)
            }
        }
    }

    /** Best cosine similarity to the enrolled profile, or null without a profile. */
    fun similarity(embedding: FloatArray): Float? = synchronized(profile) {
        if (profile.isEmpty()) null else profile.maxOf { dot(it, embedding) }
    }

    fun addToProfile(pcm: ShortArray, embedding: FloatArray = embed(pcm)) {
        synchronized(profile) {
            profile.add(embedding)
            enrolledSamples += pcm.size
        }
    }

    fun clearProfile() {
        synchronized(profile) { profile.clear(); enrolledSamples = 0 }
    }

    fun saveProfile() {
        val arr = JSONArray()
        synchronized(profile) { profile.forEach { e -> arr.put(JSONArray(e.map { it.toDouble() })) } }
        profileFile.parentFile?.mkdirs()
        profileFile.writeText(org.json.JSONObject().put("seconds", enrolledSeconds.toDouble()).put("embeddings", arr).toString())
        Log.i(TAG, "profile saved: ${arr.length()} embeddings, ${"%.1f".format(enrolledSeconds)} s -> $profileFile")
    }

    private fun loadProfile() {
        if (!profileFile.isFile) return
        runCatching {
            val json = org.json.JSONObject(profileFile.readText())
            val arr = json.getJSONArray("embeddings")
            synchronized(profile) {
                profile.clear()
                for (i in 0 until arr.length()) {
                    val e = arr.getJSONArray(i)
                    profile.add(FloatArray(e.length()) { e.getDouble(it).toFloat() })
                }
                enrolledSamples = (json.optDouble("seconds", 0.0) * 16_000).toLong()
            }
            Log.i(TAG, "profile loaded: ${arr.length()} embeddings, ${"%.1f".format(enrolledSeconds)} s")
        }.onFailure { Log.w(TAG, "profile load failed: ${it.message}") }
    }

    override fun close() {
        session.close()
    }

    companion object {
        const val MODEL_PATH = "/sdcard/Download/gate/3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced.onnx"

        fun load(profileFile: File, modelPath: String = MODEL_PATH, threads: Int = 2): SpeakerGate {
            val env = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(threads) }
            val session = env.createSession(File(modelPath).readBytes(), opts)
            return SpeakerGate(env, session, profileFile)
        }

        internal fun normalise(e: FloatArray): FloatArray {
            var n = 0.0
            for (v in e) n += v * v
            val inv = (1.0 / (sqrt(n) + 1e-9)).toFloat()
            return FloatArray(e.size) { e[it] * inv }
        }

        internal fun dot(a: FloatArray, b: FloatArray): Float {
            var s = 0f
            for (i in a.indices) s += a[i] * b[i]
            return s
        }
    }
}

/**
 * Kaldi-style log mel filterbank exactly as sherpa-onnx configures it for speaker embeddings
 * (kaldi-native-fbank): 16 kHz, 25 ms frames every 10 ms, snip_edges=false (centred frames, reflected
 * edges), DC removal, pre-emphasis 0.97, Povey window, 512-point FFT power spectrum, 80 mel bins from
 * 20 Hz to 7600 Hz, natural log. Reference: tools/campp_features.py.
 */
internal object Fbank {
    const val NUM_MEL = 80
    private const val SR = 16_000
    private const val FRAME = 400
    private const val SHIFT = 160
    private const val NFFT = 512
    private const val LOW_HZ = 20.0
    private const val HIGH_HZ = 7_600.0
    private const val PREEMPH = 0.97
    private val EPS = Math.ulp(1.0f).toDouble() // numpy float32 eps

    private val window = DoubleArray(FRAME) { (0.5 - 0.5 * cos(2 * PI * it / (FRAME - 1))).pow(0.85) }

    /** Per mel bin: first FFT bin and its weights (sparse triangular filters). */
    private val bankStart = IntArray(NUM_MEL)
    private val bankWeights = arrayOfNulls<DoubleArray>(NUM_MEL)

    init {
        fun mel(f: Double) = 1127.0 * ln(1.0 + f / 700.0)
        val fftBins = NFFT / 2
        val fftFreq = SR.toDouble() / NFFT
        val mLow = mel(LOW_HZ)
        val delta = (mel(HIGH_HZ) - mLow) / (NUM_MEL + 1)
        for (b in 0 until NUM_MEL) {
            val left = mLow + b * delta
            val center = mLow + (b + 1) * delta
            val right = mLow + (b + 2) * delta
            val w = DoubleArray(fftBins + 1)
            var first = -1
            var last = -1
            for (i in 0 until fftBins) {
                val m = mel(fftFreq * i)
                if (m > left && m < right) {
                    w[i] = if (m <= center) (m - left) / (center - left) else (right - m) / (right - center)
                    if (first < 0) first = i
                    last = i
                }
            }
            bankStart[b] = max(first, 0)
            bankWeights[b] = if (first < 0) DoubleArray(0) else w.copyOfRange(first, last + 1)
        }
    }

    /** [frames * NUM_MEL] row-major log mel energies of samples in [-1, 1]. */
    fun compute(x: FloatArray): FloatArray {
        val n = x.size
        val frames = (n + SHIFT / 2) / SHIFT
        val out = FloatArray(frames * NUM_MEL)
        val frame = DoubleArray(FRAME)
        val re = DoubleArray(NFFT)
        val im = DoubleArray(NFFT)
        val power = DoubleArray(NFFT / 2 + 1)
        for (t in 0 until frames) {
            val start = t * SHIFT + SHIFT / 2 - FRAME / 2
            var mean = 0.0
            for (j in 0 until FRAME) {
                var idx = start + j
                if (idx < 0) idx = -idx - 1
                if (idx >= n) idx = 2 * n - 1 - idx
                frame[j] = x[idx.coerceIn(0, n - 1)].toDouble()
                mean += frame[j]
            }
            mean /= FRAME
            for (j in 0 until FRAME) frame[j] -= mean
            for (j in FRAME - 1 downTo 1) frame[j] -= PREEMPH * frame[j - 1]
            frame[0] -= PREEMPH * frame[0]
            java.util.Arrays.fill(re, 0.0)
            java.util.Arrays.fill(im, 0.0)
            for (j in 0 until FRAME) re[j] = frame[j] * window[j]
            fft(re, im)
            for (k in 0..NFFT / 2) power[k] = re[k] * re[k] + im[k] * im[k]
            for (b in 0 until NUM_MEL) {
                val w = bankWeights[b]!!
                val s0 = bankStart[b]
                var e = 0.0
                for (k in w.indices) e += w[k] * power[s0 + k]
                out[t * NUM_MEL + b] = ln(max(e, EPS)).toFloat()
            }
        }
        return out
    }

    /** In-place iterative radix-2 complex FFT (size must be a power of two). */
    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            val wr = cos(ang)
            val wi = kotlin.math.sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0
                var ci = 0.0
                for (k in 0 until len / 2) {
                    val ar = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                    val ai = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                    re[i + k + len / 2] = re[i + k] - ar
                    im[i + k + len / 2] = im[i + k] - ai
                    re[i + k] += ar
                    im[i + k] += ai
                    val ncr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = ncr
                }
                i += len
            }
            len = len shl 1
        }
    }
}
