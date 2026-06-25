package com.travel2chicago.gemmapipeline.ast

import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import java.io.File

private const val TAG = "GemmaAstEngine"

/**
 * Result of a single translation call.
 *
 * @property text raw model output, already `.trim()`-ed.
 * @property latencyMs wall-clock time spent inside `Conversation.sendMessage`.
 * @property backendUsed "GPU" or "CPU" — useful for the UI to surface fallbacks.
 */
data class AstResult(
    val text: String,
    val latencyMs: Long,
    val backendUsed: String,
)

/**
 * What [AstChunkRouter] consumes. An interface so the router can be tested
 * with a deterministic stub without touching LiteRT-LM.
 */
interface GemmaAstEngine : AutoCloseable {
    val isLoaded: Boolean
    /** "GPU" / "CPU" once [isLoaded] becomes true; null before then. */
    val backendUsed: String?
    /** Wall-clock cost of `engine.initialize()` in ms. -1 before load. */
    val loadTimeMs: Long

    /**
     * Run one translation. Synchronous — the caller is responsible for
     * dispatching this off the main thread (the router uses Dispatchers.IO).
     * Throws if the engine is not loaded, the WAV is malformed, or the
     * underlying LiteRT-LM call fails.
     */
    fun translate(wavBytes: ByteArray, prompt: String): AstResult
}

/**
 * Production wrapper around LiteRT-LM 0.12.0 for Gemma 4 E4B AST.
 *
 * Mirrors the exact configuration validated in Fase 0 (`gemma-ast-poc/
 * GemmaTestViewModel.kt` lines 305–346):
 *   - `EngineConfig(modelPath, backend=GPU|CPU, audioBackend=Backend.CPU(),
 *     maxNumTokens=1024, cacheDir=app.externalFilesDir)`
 *   - Low-temperature sampler for translation-style determinism
 *     (temp=0.1, topK=10, topP=0.95).
 *   - `Contents.of(Content.AudioBytes(wav), Content.Text(prompt))` — audio
 *     FIRST, text LAST.
 *   - Synchronous `conv.sendMessage(contents): Message` → `.toString().trim()`.
 *
 * **Conversation lifetime is per-call, not per-engine.** Reusing one
 * `Conversation` for many translations made it accumulate every prior
 * (audio + text) message in its KV cache. After ~6 multi-modal turns the
 * context window overflowed and `sendMessage` started throwing
 * `LiteRtLmJniException("Failed to invoke the compiled model")`. Before
 * that, the model also started echoing earlier translations into new ones
 * because old turns were still "in scope". LiteRT-LM 0.12.0 has no
 * `conversation.reset()`, so we instead build a fresh `Conversation` for
 * each `translate()` — the create cost is ~ms vs ~2s of inference.
 *
 * GPU → CPU fallback: if [config.preferGpu] is true we try GPU first; on any
 * exception during `Engine(...).initialize()` we tear down and retry with
 * `Backend.CPU()`. CPU is slower (~3–5×) but always works.
 *
 * Lifecycle: hold one instance for the lifetime of the screen. `close()` is
 * idempotent and quenches LiteRT-LM exceptions so it's safe in `onCleared`.
 */
class LiteRtGemmaAstEngine private constructor(
    private val engine: Engine,
    private val samplerConfig: SamplerConfig,
    override val backendUsed: String,
    override val loadTimeMs: Long,
) : GemmaAstEngine {

    @Volatile private var closed: Boolean = false

    override val isLoaded: Boolean get() = !closed

    override fun translate(wavBytes: ByteArray, prompt: String): AstResult {
        check(!closed) { "Engine is closed" }
        require(wavBytes.size > 44) { "WAV bytes too small (${wavBytes.size}); missing header?" }
        require(prompt.isNotBlank()) { "Prompt must not be blank" }

        // Order matters — audio FIRST, text LAST. See Fase 0 (Fase 0 docs
        // mention reversed order causes silent failures from AI Edge Gallery).
        val contents = Contents.of(
            Content.AudioBytes(wavBytes),
            Content.Text(prompt),
        )

        // Fresh Conversation per call — see class doc for why reusing one
        // overflows the context after ~6 turns and bleeds prior translations
        // into the new output.
        val conv: Conversation = engine.createConversation(
            ConversationConfig(samplerConfig = samplerConfig),
        )

        val startNs = System.nanoTime()
        val response: Message = conv.sendMessage(contents)
        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000

        val text = response.toString().trim()
        Log.i(TAG, "translate: ${wavBytes.size} wav bytes → ${text.length} chars in ${elapsedMs}ms ($backendUsed)")
        return AstResult(text = text, latencyMs = elapsedMs, backendUsed = backendUsed)
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            engine.close()
        } catch (e: Exception) {
            Log.w(TAG, "engine.close() threw — ignored", e)
        }
    }

    companion object {
        /**
         * Load Gemma from disk. Blocks for ~10–30s on GPU init the first time
         * (shorter on subsequent app starts because of the xnnpack cache).
         * MUST be called from a coroutine on Dispatchers.IO — runs blocking
         * file + native work.
         *
         * Throws [IllegalStateException] if the model file is missing.
         * Throws [RuntimeException] if both GPU and CPU init fail.
         */
        fun load(config: AstConfig, cacheDir: String): LiteRtGemmaAstEngine {
            val modelFile = File(config.modelPath)
            check(modelFile.exists() && modelFile.isFile) {
                "Gemma model not found at ${config.modelPath} — copy from gemma-ast-poc"
            }
            Log.i(TAG, "Model file present: ${modelFile.length()} bytes")

            // GPU → CPU strategy. Each attempt is fully isolated so a failed
            // GPU init can't leak resources into the CPU attempt.
            val attempts: List<Pair<String, () -> Backend>> = if (config.preferGpu) {
                listOf("GPU" to { Backend.GPU() }, "CPU" to { Backend.CPU() })
            } else {
                listOf("CPU" to { Backend.CPU() })
            }

            var lastError: Throwable? = null
            for ((label, makeBackend) in attempts) {
                val backend = try {
                    makeBackend()
                } catch (t: Throwable) {
                    Log.w(TAG, "Backend.$label() factory threw — skipping", t)
                    lastError = t
                    continue
                }

                Log.i(TAG, "Attempting init with $label backend…")
                // Known noise during initialize(): LiteRT-LM v0.12.0 emits
                //   [litert_dispatch.cc:113] No dispatch library found in <modelDirPath>
                // once per subgraph op (~hundreds of lines). This is harmless —
                // the dispatch library is an OPTIONAL accelerator; when absent
                // LiteRT-LM falls back to the declared backend (GPU/CPU) without
                // any functional degradation. EngineConfig in 0.12.0 exposes
                // only {modelPath, backend, audioBackend, maxNumTokens, cacheDir}
                // — there is no `dispatchLibDir` / `litert_dispatch_lib_dir`
                // field to redirect or silence this path. The log originates
                // from native code (__android_log_print) so it cannot be
                // filtered from the Kotlin side. If a future SDK release adds
                // a dispatch-lib setting, wire it in HERE.
                val engineConfig = EngineConfig(
                    modelPath = modelFile.absolutePath,
                    backend = backend,
                    audioBackend = Backend.CPU(),  // audio path is CPU-only — Fase 0 confirmed
                    maxNumTokens = config.maxNumTokens,
                    cacheDir = cacheDir,
                )

                val eng = Engine(engineConfig)
                val startedAt = System.nanoTime()
                try {
                    eng.initialize()
                } catch (t: Throwable) {
                    Log.w(TAG, "$label initialize() failed", t)
                    runCatching { eng.close() }
                    lastError = t
                    continue
                }
                val loadMs = (System.nanoTime() - startedAt) / 1_000_000
                Log.i(TAG, "$label backend ready in ${loadMs}ms")

                // Pin the sampler so every per-call Conversation uses the
                // same translation-tuned config (low temperature for
                // deterministic-ish output).
                val samplerConfig = SamplerConfig(
                    temperature = 0.1,
                    topK = 10,
                    topP = 0.95,
                )

                return LiteRtGemmaAstEngine(
                    engine = eng,
                    samplerConfig = samplerConfig,
                    backendUsed = label,
                    loadTimeMs = loadMs,
                )
            }

            throw RuntimeException(
                "Gemma engine init failed on all backends (${attempts.joinToString { it.first }})",
                lastError,
            )
        }
    }
}
