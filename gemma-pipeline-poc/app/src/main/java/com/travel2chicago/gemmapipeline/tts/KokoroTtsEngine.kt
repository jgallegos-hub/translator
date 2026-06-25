package com.travel2chicago.gemmapipeline.tts

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer

private const val TAG = "KokoroTtsEngine"

/**
 * Outcome of a single synthesize() call.
 *
 * @property pcm int16 little-endian PCM samples, mono, at [sampleRate] Hz.
 * @property latencyMs total wall-clock time spent inside the engine (includes
 *   phonemizer + tokenizer + every ONNX inference for sentence splits).
 */
data class TtsResult(
    val pcm: ShortArray,
    val sampleRate: Int,
    val latencyMs: Long,
)

interface KokoroTtsEngine : AutoCloseable {
    val isLoaded: Boolean
    val loadTimeMs: Long
    /** Voice names this engine can synthesize. */
    val availableVoices: Set<String>

    /**
     * Synthesize an English text string into PCM audio. Sentences over the
     * tokenizer's max length are split on sentence terminators and the
     * resulting PCM is concatenated.
     *
     * Synchronous from the caller's perspective; meant to run off the main
     * thread (the router uses Dispatchers.IO).
     */
    fun synthesize(text: String, voice: String): TtsResult
}

/**
 * Production engine wired against Kokoro-82M v1.0 ONNX.
 *
 * Layout mirrors [com.travel2chicago.gemmapipeline.vad.SileroVadOnnxModel]:
 *   - one [OrtSession] held for the lifetime of the engine,
 *   - all tensors built with explicit shapes via FloatBuffer / LongBuffer
 *     (ORT Android 1.19 does not accept nested arrays),
 *   - outputs always retrieved BY NAME, never by index (Fase 3 bug).
 *
 * The model + voices files live on `/sdcard/Download/kokoro_model/` per
 * Fase 5 D2; the tokenizer config + dictionary are bundled in assets so the
 * APK does not need to read from external storage to do its lookups.
 *
 * Input/output names assumed from the public ONNX export:
 *   - inputs:  "input_ids" (int64 [1, n_tokens])
 *              "style"     (float32 [1, embedding_size])
 *              "speed"     (float32 [1])
 *   - output:  "audio"     (float32 [1, n_samples])
 *
 * `loadFromDisk` dumps `session.inputInfo` / `outputInfo` at load time so a
 * release with different names is visible in logcat — adjust the constants
 * below if a future Kokoro export renames them.
 */
class KokoroOnnxEngine private constructor(
    private val env: OrtEnvironment,
    private val session: OrtSession,
    private val phonemizer: Phonemizer,
    private val tokenizer: Tokenizer,
    private val voices: Map<String, FloatArray>,
    private val sampleRate: Int,
    override val loadTimeMs: Long,
) : KokoroTtsEngine {

    @Volatile private var closed: Boolean = false

    override val isLoaded: Boolean get() = !closed
    override val availableVoices: Set<String> get() = voices.keys

    override fun synthesize(text: String, voice: String): TtsResult {
        check(!closed) { "Engine is closed" }
        require(text.isNotBlank()) { "text must not be blank" }
        val voiceVec = voices[voice]
            ?: throw IllegalArgumentException("Unknown voice '$voice'. Available: ${voices.keys}")

        val startedNs = System.nanoTime()
        val sentences = splitIntoSentences(text)
        val pcmChunks = ArrayList<FloatArray>(sentences.size)
        for (sentence in sentences) {
            val pcm = synthesizeOne(sentence, voiceVec) ?: continue
            pcmChunks += pcm
        }
        val merged = concatToInt16(pcmChunks)
        val elapsedMs = (System.nanoTime() - startedNs) / 1_000_000
        Log.i(TAG, "synthesize: '${text.take(60)}' → ${merged.size} samples in ${elapsedMs}ms ($voice, ${sentences.size} sentence(s))")
        return TtsResult(pcm = merged, sampleRate = sampleRate, latencyMs = elapsedMs)
    }

    private fun synthesizeOne(sentence: String, voiceVec: FloatArray): FloatArray? {
        val phonemes = phonemizer.phonemize(sentence)
        if (phonemes.isEmpty()) return null
        val tokenIds = tokenizer.tokenize(phonemes)
        if (tokenIds.size <= 2) return null  // only BOS+EOS — nothing to say

        var tokensTensor: OnnxTensor? = null
        var styleTensor: OnnxTensor? = null
        var speedTensor: OnnxTensor? = null
        var results: OrtSession.Result? = null
        try {
            tokensTensor = OnnxTensor.createTensor(
                env,
                LongBuffer.wrap(tokenIds),
                longArrayOf(1L, tokenIds.size.toLong()),
            )
            styleTensor = OnnxTensor.createTensor(
                env,
                FloatBuffer.wrap(voiceVec),
                longArrayOf(1L, voiceVec.size.toLong()),
            )
            speedTensor = OnnxTensor.createTensor(
                env,
                FloatBuffer.wrap(floatArrayOf(1.0f)),
                longArrayOf(1L),
            )
            val inputs = mapOf(
                INPUT_TOKENS to tokensTensor,
                INPUT_STYLE to styleTensor,
                INPUT_SPEED to speedTensor,
            )
            results = session.run(inputs)
            val audioTensor = results.get(OUTPUT_AUDIO).orElseThrow {
                IllegalStateException("Output '$OUTPUT_AUDIO' missing from session result")
            } as OnnxTensor
            // Kokoro returns float32 of shape [1, n_samples]
            @Suppress("UNCHECKED_CAST")
            val arr = audioTensor.value as Array<FloatArray>
            return arr[0]
        } finally {
            runCatching { results?.close() }
            runCatching { tokensTensor?.close() }
            runCatching { styleTensor?.close() }
            runCatching { speedTensor?.close() }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { session.close() }
        // Do NOT close env — it's a process-wide singleton shared with Silero.
    }

    companion object {
        // Asset / file convention constants. If a future Kokoro export
        // renames these, change here and rebuild — the engine logs
        // session.inputInfo at load time so the mismatch is visible.
        const val INPUT_TOKENS = "input_ids"
        const val INPUT_STYLE = "style"
        const val INPUT_SPEED = "speed"
        const val OUTPUT_AUDIO = "audio"

        /**
         * Default voice ordering used by the bundled stub voices loader. The
         * REAL voices ordering comes from the published config of the model
         * version you ship — replace this list when swapping in the
         * production `voices-v1.0.bin`.
         */
        val STUB_VOICE_NAMES = listOf("af_heart")

        /**
         * Load Kokoro from disk + bundled assets. Blocks for a few seconds
         * on the first run; meant to be called from Dispatchers.IO.
         *
         * @throws IllegalStateException if the model or voices file is missing.
         * @throws RuntimeException if ONNX session creation fails.
         */
        fun load(context: Context, config: TtsConfig): KokoroOnnxEngine {
            Log.i(TAG, "Loading Kokoro TTS engine…")
            val startedNs = System.nanoTime()

            val modelFile = File(config.modelPath)
            check(modelFile.isFile) {
                "Kokoro model not found at ${config.modelPath} — download " +
                    "kokoro-v1.0.onnx from huggingface.co/onnx-community/Kokoro-82M-v1.0-ONNX"
            }
            val voicesFile = File(config.voicesPath)
            check(voicesFile.isFile) {
                "Kokoro voices file not found at ${config.voicesPath} — " +
                    "download voices-v1.0.bin from the same HuggingFace release"
            }
            Log.i(TAG, "Model file: ${modelFile.length()} bytes; voices: ${voicesFile.length()} bytes")

            val phonemizer = DictionaryPhonemizer.loadFromAssets(context, config.dictionaryAsset)
            val tokenizer = Tokenizer.loadFromAsset(context, config.configAsset)

            // Voice file layout is release-dependent — fall back to a
            // tolerant loader that reads what it can and logs mismatches.
            // For Fase 5 v1 we assume the published per-voice stride of
            // 511 × 1 × 256 floats = 130 816 floats, and use a 256-float
            // slice (the [0,:] row) as the style embedding per inference.
            val voicesLoader = VoicesLoader(
                voiceNames = STUB_VOICE_NAMES,
                voiceFloatStride = 511 * 1 * 256,
                sliceLen = 256,
            )
            val voices = voicesLoader.loadFromFile(voicesFile)
            check(voices.isNotEmpty()) {
                "No voices loaded from ${voicesFile.name} — check binary layout"
            }

            val modelBytes = modelFile.readBytes()
            val env = OrtEnvironment.getEnvironment()
            val options = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(2)
            }
            val session = env.createSession(modelBytes, options)

            try {
                val ins = session.inputInfo
                Log.i(TAG, "Session INPUTS (${ins.size}):")
                for ((name, info) in ins) Log.i(TAG, "  '$name' → ${info.info}")
                val outs = session.outputInfo
                Log.i(TAG, "Session OUTPUTS (${outs.size}):")
                for ((name, info) in outs) Log.i(TAG, "  '$name' → ${info.info}")
            } catch (t: Throwable) {
                Log.w(TAG, "Could not enumerate session inputs/outputs", t)
            }

            val loadMs = (System.nanoTime() - startedNs) / 1_000_000
            Log.i(TAG, "Kokoro engine ready in ${loadMs}ms (${voices.size} voices)")
            return KokoroOnnxEngine(
                env = env,
                session = session,
                phonemizer = phonemizer,
                tokenizer = tokenizer,
                voices = voices,
                sampleRate = config.sampleRate,
                loadTimeMs = loadMs,
            )
        }

        /**
         * Public for testing — splits on sentence terminators while
         * preserving the punctuation that triggered the split.
         */
        internal fun splitIntoSentences(text: String): List<String> {
            val out = ArrayList<String>(4)
            val matcher = Regex("[^.!?]+[.!?]?")
            for (match in matcher.findAll(text)) {
                val s = match.value.trim()
                if (s.isNotEmpty()) out += s
            }
            return if (out.isEmpty()) listOf(text.trim()) else out
        }

        /** Concatenate float32 PCM chunks and quantize to int16. */
        internal fun concatToInt16(chunks: List<FloatArray>): ShortArray {
            val total = chunks.sumOf { it.size }
            val out = ShortArray(total)
            var off = 0
            for (chunk in chunks) {
                for (i in chunk.indices) {
                    val s = chunk[i].coerceIn(-1.0f, 1.0f) * 32767.0f
                    out[off + i] = s.toInt().toShort()
                }
                off += chunk.size
            }
            return out
        }
    }
}
