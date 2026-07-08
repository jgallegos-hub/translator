package com.travel2chicago.gemmapipeline.tts

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import java.io.File
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.nio.LongBuffer

private const val TAG = "KokoroTtsEngine"

/**
 * Outcome of a single synthesize() call.
 *
 * @property pcm int16 little-endian PCM samples, mono, at [sampleRate] Hz.
 * @property latencyMs total wall-clock time spent inside the engine
 *   (phonemizer + tokenizer + every ONNX inference for sentence splits).
 */
data class TtsResult(
    val pcm: ShortArray,
    val sampleRate: Int,
    val latencyMs: Long,
)

interface KokoroTtsEngine : AutoCloseable {
    val isLoaded: Boolean
    val loadTimeMs: Long
    val availableVoices: Set<String>
    fun synthesize(text: String, voice: String): TtsResult

    /**
     * Streaming variant used by Fase 6 Stage B. Splits [text] into sentences,
     * runs ONNX inference on each, and invokes [onSentence] with the per-
     * sentence int16 PCM as soon as it's ready — so the audio player can
     * start emitting sentence 1 while the model is still decoding sentence
     * 2.
     *
     * Aggregate [TtsResult.pcm] is intentionally EMPTY on this path: the
     * caller consumed samples via the callback. `latencyMs` on the return
     * value is the wall-clock across the entire streaming call (useful for
     * router metrics).
     */
    suspend fun synthesizeStreaming(
        text: String,
        voice: String,
        onSentence: suspend (pcm: ShortArray, sampleRate: Int, sentenceIndex: Int) -> Unit,
    ): TtsResult
}

/**
 * Production engine wired against Kokoro-82M v1.0 (int8 ONNX export).
 *
 * The ONNX export ships in two flavours that we detect at load time:
 *
 *   - **Newer** (`input_ids` + `style` + `speed:int32`):
 *     [Kokoro-82M-v1.0-ONNX](https://huggingface.co/onnx-community/Kokoro-82M-v1.0-ONNX)
 *   - **Older** (`tokens` + `style` + `speed:float32`):
 *     the original export bundled with `kokoro-onnx` ≤ v0.4.
 *
 * `loadFromDisk` introspects `session.inputInfo` and remembers which
 * convention this session uses; [synthesize] picks the right tensor types
 * accordingly. Output is read by name (`audio`) with a fallback to index 0
 * because the public exports access by index — robust to either layout.
 *
 * Tokens are wrapped with the model's PAD ID at both ends — Python reference
 * is `tokens = [[0, *tokens, 0]]`, we do the equivalent in Kotlin.
 */
class KokoroOnnxEngine private constructor(
    private val env: OrtEnvironment,
    private val session: OrtSession,
    private val phonemizer: Phonemizer,
    private val tokenizer: Tokenizer,
    private val voices: VoiceStyles,
    private val sampleRate: Int,
    private val isNewExport: Boolean,
    override val loadTimeMs: Long,
) : KokoroTtsEngine {

    @Volatile private var closed: Boolean = false

    override val isLoaded: Boolean get() = !closed
    override val availableVoices: Set<String> get() = voices.availableVoices

    override fun synthesize(text: String, voice: String): TtsResult {
        check(!closed) { "Engine is closed" }
        require(text.isNotBlank()) { "text must not be blank" }
        if (!voices.has(voice)) {
            throw IllegalArgumentException(
                "Unknown voice '$voice'. Available: ${voices.availableVoices}",
            )
        }

        val startedNs = System.nanoTime()
        val sentences = splitIntoSentences(text)
        val pcmChunks = ArrayList<FloatArray>(sentences.size)
        for (sentence in sentences) {
            val pcm = synthesizeOne(sentence, voice) ?: continue
            pcmChunks += pcm
        }
        val merged = concatToInt16(pcmChunks)
        val elapsedMs = (System.nanoTime() - startedNs) / 1_000_000
        Log.i(TAG, "synthesize: '${text.take(60)}' → ${merged.size} samples in ${elapsedMs}ms ($voice, ${sentences.size} sentence(s))")
        return TtsResult(pcm = merged, sampleRate = sampleRate, latencyMs = elapsedMs)
    }

    override suspend fun synthesizeStreaming(
        text: String,
        voice: String,
        onSentence: suspend (pcm: ShortArray, sampleRate: Int, sentenceIndex: Int) -> Unit,
    ): TtsResult {
        check(!closed) { "Engine is closed" }
        require(text.isNotBlank()) { "text must not be blank" }
        if (!voices.has(voice)) {
            throw IllegalArgumentException(
                "Unknown voice '$voice'. Available: ${voices.availableVoices}",
            )
        }

        val startedNs = System.nanoTime()
        val sentences = splitIntoSentences(text)
        var totalSamples = 0
        for ((idx, sentence) in sentences.withIndex()) {
            val floatPcm = synthesizeOne(sentence, voice) ?: continue
            val int16 = concatToInt16(listOf(floatPcm))
            totalSamples += int16.size
            onSentence(int16, sampleRate, idx)
        }
        val elapsedMs = (System.nanoTime() - startedNs) / 1_000_000
        Log.i(TAG, "synthesizeStreaming: '${text.take(60)}' → $totalSamples samples across ${sentences.size} sentence(s) in ${elapsedMs}ms ($voice)")
        // Empty aggregate — the caller consumed samples via [onSentence]
        // and the router uses only [latencyMs] here.
        return TtsResult(pcm = ShortArray(0), sampleRate = sampleRate, latencyMs = elapsedMs)
    }

    private fun synthesizeOne(sentence: String, voice: String): FloatArray? {
        val phonemes = phonemizer.phonemize(sentence)
        if (phonemes.isEmpty()) return null
        val rawTokens = tokenizer.tokenize(phonemes)
        if (rawTokens.isEmpty()) return null

        // Select the style vector for THIS sentence — Python does
        //   voice = voice[len(tokens)]
        // BEFORE wrapping with PAD, so we pass the unwrapped count.
        val voiceVec = voices.styleFor(voice, rawTokens.size)

        // Wrap with PAD at both ends. Python reference: [[0, *tokens, 0]].
        val padded = LongArray(rawTokens.size + 2).also { arr ->
            arr[0] = tokenizer.padId.toLong()
            System.arraycopy(rawTokens, 0, arr, 1, rawTokens.size)
            arr[arr.size - 1] = tokenizer.padId.toLong()
        }

        var tokensTensor: OnnxTensor? = null
        var styleTensor: OnnxTensor? = null
        var speedTensor: OnnxTensor? = null
        var results: OrtSession.Result? = null
        try {
            tokensTensor = OnnxTensor.createTensor(
                env,
                LongBuffer.wrap(padded),
                longArrayOf(1L, padded.size.toLong()),
            )
            styleTensor = OnnxTensor.createTensor(
                env,
                FloatBuffer.wrap(voiceVec),
                longArrayOf(1L, voiceVec.size.toLong()),
            )
            // Speed type differs across exports — see class doc.
            speedTensor = if (isNewExport) {
                OnnxTensor.createTensor(
                    env,
                    IntBuffer.wrap(intArrayOf(1)),
                    longArrayOf(1L),
                )
            } else {
                OnnxTensor.createTensor(
                    env,
                    FloatBuffer.wrap(floatArrayOf(1.0f)),
                    longArrayOf(1L),
                )
            }
            val tokensInputName = if (isNewExport) INPUT_IDS_NEW else INPUT_TOKENS_OLD
            val inputs = mapOf(
                tokensInputName to tokensTensor,
                INPUT_STYLE to styleTensor,
                INPUT_SPEED to speedTensor,
            )
            results = session.run(inputs)
            // Read by name first; fall back to index 0 (Python reference uses
            // [0]). Either path returns the float32 audio tensor.
            val audioTensor = (results.get(OUTPUT_AUDIO).orElse(null) as? OnnxTensor)
                ?: (results.get(0) as? OnnxTensor)
                ?: throw IllegalStateException("Session result has no usable audio tensor")
            // Kokoro returns float32, shape either [1, n_samples] or [n_samples].
            val raw = audioTensor.value
            @Suppress("UNCHECKED_CAST")
            return when (raw) {
                is FloatArray -> raw
                is Array<*> -> (raw as Array<FloatArray>)[0]
                else -> throw IllegalStateException("Unexpected audio tensor type: ${raw::class.java}")
            }
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
        // Do NOT close env — process-wide singleton shared with Silero.
    }

    companion object {
        const val INPUT_IDS_NEW = "input_ids"   // newer Kokoro v1.0 ONNX export
        const val INPUT_TOKENS_OLD = "tokens"   // older export (kokoro-onnx ≤ 0.4)
        const val INPUT_STYLE = "style"
        const val INPUT_SPEED = "speed"
        const val OUTPUT_AUDIO = "audio"

        fun load(context: Context, config: TtsConfig): KokoroOnnxEngine {
            Log.i(TAG, "Loading Kokoro TTS engine…")
            val startedNs = System.nanoTime()

            val modelFile = File(config.modelPath)
            check(modelFile.isFile) {
                "Kokoro model not found at ${config.modelPath} — download " +
                    "kokoro-v1.0.int8.onnx from huggingface.co/onnx-community/Kokoro-82M-v1.0-ONNX"
            }
            val voicesFile = File(config.voicesPath)
            check(voicesFile.isFile) {
                "Kokoro voices file not found at ${config.voicesPath} — " +
                    "download voices-v1.0.bin from the same HuggingFace release"
            }
            Log.i(TAG, "Model file: ${modelFile.length()} bytes; voices: ${voicesFile.length()} bytes")

            val phonemizer = DictionaryPhonemizer.loadFromAssets(context, config.dictionaryAsset)
            val tokenizer = Tokenizer.loadFromAsset(context, config.configAsset)

            // voices-v1.0.bin is an NPZ (ZIP of .npy float32 arrays, one per
            // voice). VoicesNpz parses every entry so af_heart, af_bella,
            // am_michael, etc. are all loaded at once.
            val voices = VoicesNpz.load(voicesFile)
            check(voices.has(config.voice)) {
                "Requested voice '${config.voice}' not present in ${voicesFile.name}. " +
                    "Found: ${voices.availableVoices}"
            }

            val modelBytes = modelFile.readBytes()
            val env = OrtEnvironment.getEnvironment()
            val options = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(2)
            }
            val session = env.createSession(modelBytes, options)

            // Inspect input names to pick the right convention. Older exports
            // call the tokens input "tokens"; newer ones "input_ids" and use
            // int32 for "speed". Log the decision so it's visible if a
            // future export changes the naming.
            var isNewExport = true
            try {
                val ins = session.inputInfo
                Log.i(TAG, "Session INPUTS (${ins.size}):")
                for ((name, info) in ins) Log.i(TAG, "  '$name' → ${info.info}")
                isNewExport = ins.containsKey(INPUT_IDS_NEW)
                val outs = session.outputInfo
                Log.i(TAG, "Session OUTPUTS (${outs.size}):")
                for ((name, info) in outs) Log.i(TAG, "  '$name' → ${info.info}")
            } catch (t: Throwable) {
                Log.w(TAG, "Could not enumerate session inputs/outputs — assuming new export", t)
            }
            Log.i(TAG, "Export type: ${if (isNewExport) "NEW (input_ids + speed:int32)" else "OLD (tokens + speed:float32)"}")

            val loadMs = (System.nanoTime() - startedNs) / 1_000_000
            Log.i(TAG, "Kokoro engine ready in ${loadMs}ms (${voices.availableVoices.size} voices)")
            return KokoroOnnxEngine(
                env = env,
                session = session,
                phonemizer = phonemizer,
                tokenizer = tokenizer,
                voices = voices,
                sampleRate = config.sampleRate,
                isNewExport = isNewExport,
                loadTimeMs = loadMs,
            )
        }

        internal fun splitIntoSentences(text: String): List<String> {
            val out = ArrayList<String>(4)
            val matcher = Regex("[^.!?]+[.!?]?")
            for (match in matcher.findAll(text)) {
                val s = match.value.trim()
                if (s.isNotEmpty()) out += s
            }
            return if (out.isEmpty()) listOf(text.trim()) else out
        }

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
