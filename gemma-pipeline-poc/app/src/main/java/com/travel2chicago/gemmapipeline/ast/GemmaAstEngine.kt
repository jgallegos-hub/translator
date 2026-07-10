package com.travel2chicago.gemmapipeline.ast

import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.flow.onCompletion
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
     *
     * @param audioAfterText if `true` (default, per Google's multimodal
     *   guidance) the underlying call is built as `Contents.of(Text(prompt),
     *   AudioBytes(wav))`. If `false`, the legacy Fase 0 order
     *   `AudioBytes` → `Text` is used. See [AstConfig.audioAfterText].
     */
    fun translate(
        wavBytes: ByteArray,
        prompt: String,
        audioAfterText: Boolean = true,
    ): AstResult

    /**
     * Streaming variant used by Fase 6 Stage A. Suspends until Gemma finishes
     * decoding the reply, but calls [onToken] for every partial [Message]
     * emitted by `Conversation.sendMessageAsync(...): Flow<Message>` — the
     * router uses those callbacks to detect sentence boundaries and emit
     * per-sentence `TranslationReady` events so Kokoro can start speaking
     * before Gemma is done.
     *
     * Each token passed to [onToken] is the delta emitted by the SDK (the
     * text NEWLY produced since the previous callback), not the accumulated
     * reply. See `LiteRtGemmaAstEngine.translateStreaming` for the exact
     * semantics.
     *
     * Returns the aggregate [AstResult] — [AstResult.text] is the fully
     * accumulated reply (already `.trim()`-ed) and [AstResult.latencyMs] the
     * total wall-clock spent inside the streaming call. Throws on failure;
     * the router treats a thrown exception the same as `translate()` (emit
     * `AstError`, keep the consumer alive).
     *
     * @param audioAfterText see [translate].
     */
    suspend fun translateStreaming(
        wavBytes: ByteArray,
        prompt: String,
        audioAfterText: Boolean = true,
        onToken: suspend (token: String) -> Unit,
    ): AstResult
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
 * **One active Conversation per Engine.** LiteRT-LM 0.12.0 errors with
 * `FAILED_PRECONDITION: A session already exists` if a second
 * `createConversation()` is called while a prior one is still open. We
 * keep a reference to the last conversation and `close()` it before
 * building the next, plus on engine `close()` so nothing leaks.
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

    /**
     * The Conversation opened by the most recent [translate] call. LiteRT-LM
     * only tolerates one open Conversation per Engine at a time
     * (`FAILED_PRECONDITION: A session already exists` otherwise), so we
     * close the prior one before opening the next. Also closed on engine
     * teardown.
     */
    @Volatile private var currentConversation: Conversation? = null

    override val isLoaded: Boolean get() = !closed

    override fun translate(
        wavBytes: ByteArray,
        prompt: String,
        audioAfterText: Boolean,
    ): AstResult {
        check(!closed) { "Engine is closed" }
        require(wavBytes.size > 44) { "WAV bytes too small (${wavBytes.size}); missing header?" }
        require(prompt.isNotBlank()) { "Prompt must not be blank" }

        // Content order matters for multimodal AST accuracy. Google's docs:
        // "For optimal performance with multimodal inputs, place audio content
        // AFTER the text in your prompt." Fase 0 shipped the reverse order
        // (audio first) because AI Edge Gallery seemed to reject text-first
        // silently at the time — later confirmed that both orders parse but
        // audio-after-text gives measurably better AST accuracy on device.
        // The [audioAfterText] flag lets us revert to Fase 0 order (false)
        // if a device regression appears on the new order.
        val contents = if (audioAfterText) {
            Contents.of(Content.Text(prompt), Content.AudioBytes(wavBytes))
        } else {
            Contents.of(Content.AudioBytes(wavBytes), Content.Text(prompt))
        }

        // Release the previous Conversation before opening a new one — only
        // one session per Engine is allowed (FAILED_PRECONDITION otherwise).
        closeCurrentConversation()

        // Fresh Conversation per call — see class doc for why reusing one
        // overflows the context after ~6 turns and bleeds prior translations
        // into the new output.
        val conv: Conversation = engine.createConversation(
            ConversationConfig(samplerConfig = samplerConfig),
        )
        currentConversation = conv

        val startNs = System.nanoTime()
        val response: Message = conv.sendMessage(contents)
        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000

        val text = response.toString().trim()
        Log.i(TAG, "translate: ${wavBytes.size} wav bytes → ${text.length} chars in ${elapsedMs}ms ($backendUsed)")
        return AstResult(text = text, latencyMs = elapsedMs, backendUsed = backendUsed)
    }

    override suspend fun translateStreaming(
        wavBytes: ByteArray,
        prompt: String,
        audioAfterText: Boolean,
        onToken: suspend (token: String) -> Unit,
    ): AstResult {
        check(!closed) { "Engine is closed" }
        require(wavBytes.size > 44) { "WAV bytes too small (${wavBytes.size}); missing header?" }
        require(prompt.isNotBlank()) { "Prompt must not be blank" }

        // Same content-order semantics as translate(). See there for rationale.
        val contents = if (audioAfterText) {
            Contents.of(Content.Text(prompt), Content.AudioBytes(wavBytes))
        } else {
            Contents.of(Content.AudioBytes(wavBytes), Content.Text(prompt))
        }

        // Same one-Conversation-per-Engine discipline as translate(). We MUST
        // close the prior Conversation before creating this one; we MUST NOT
        // close the new one until the streaming Flow has fully drained,
        // otherwise LiteRT-LM 0.12.0 throws `FAILED_PRECONDITION: A session
        // already exists` on the next translate/translateStreaming call.
        closeCurrentConversation()

        val conv: Conversation = engine.createConversation(
            ConversationConfig(samplerConfig = samplerConfig),
        )
        currentConversation = conv

        val fullText = StringBuilder()
        val startNs = System.nanoTime()

        // Flow<Message> is coroutine-native; `onCompletion` runs on both the
        // success and error branches, guaranteeing we release the
        // Conversation after the JNI decoder has drained. Closing inside
        // `collect` would race with the SDK's own finalisation and can leak
        // the native session.
        conv.sendMessageAsync(contents)
            .onCompletion { closeCurrentConversation() }
            .collect { msg ->
                // Assumption: each `Message` in the Flow is the DELTA that
                // was decoded since the previous callback (i.e. one or more
                // new tokens as text), not the accumulated reply. If a
                // future SDK revision changes this to "cumulative", the
                // router will double-count text — flip to a diff strategy
                // (msg.substring(fullText.length)) then. The first-boot
                // log line below prints the first delta so we can eyeball
                // it in device logcat.
                val delta = msg.toString()
                if (delta.isNotEmpty()) {
                    if (fullText.isEmpty()) {
                        Log.i(TAG, "translateStreaming: first delta '${delta.take(40)}' at ${(System.nanoTime() - startNs) / 1_000_000}ms")
                    }
                    fullText.append(delta)
                    onToken(delta)
                }
            }

        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000
        val text = fullText.toString().trim()
        Log.i(TAG, "translateStreaming: ${wavBytes.size} wav bytes → ${text.length} chars in ${elapsedMs}ms ($backendUsed)")
        return AstResult(text = text, latencyMs = elapsedMs, backendUsed = backendUsed)
    }

    override fun close() {
        if (closed) return
        closed = true
        closeCurrentConversation()
        try {
            engine.close()
        } catch (e: Exception) {
            Log.w(TAG, "engine.close() threw — ignored", e)
        }
    }

    private fun closeCurrentConversation() {
        val prev = currentConversation ?: return
        currentConversation = null
        try {
            prev.close()
        } catch (e: Exception) {
            Log.w(TAG, "conversation.close() threw — ignored", e)
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
        @OptIn(ExperimentalApi::class)
        fun load(config: AstConfig, cacheDir: String): LiteRtGemmaAstEngine {
            val modelFile = File(config.modelPath)
            check(modelFile.exists() && modelFile.isFile) {
                "Gemma model not found at ${config.modelPath} — copy from gemma-ast-poc"
            }
            Log.i(TAG, "Model file present: ${modelFile.length()} bytes")

            // Multi-Token Prediction / speculative decoding. When enabled, the
            // runtime uses the model's built-in MTP drafter to speculate on
            // the next N tokens and verify them in a single decode step — up
            // to ~2.2× decode speedup on translation-style workloads.
            //
            // Must be set BEFORE `engine.initialize()` — the flag is read
            // during the initialize() path when the runtime wires up the
            // drafter subgraph.
            //
            // Only touch the flag when explicitly enabled. Setting it to
            // `false` would override any user-set default outside this class;
            // leaving it null preserves SDK default (no speculation).
            //
            // Requires the .litertlm export to contain the MTP drafter (only
            // models built after 2026-05-05 have it). If the drafter is
            // missing, the flag is silently ignored — no crash, no speedup.
            if (config.mtpEnabled) {
                ExperimentalFlags.enableSpeculativeDecoding = true
                Log.i(TAG, "MTP/speculative decoding: enabled (via ExperimentalFlags)")
            } else {
                Log.i(TAG, "MTP/speculative decoding: disabled (SDK default path)")
            }

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
