package com.travel2chicago.gemmapipeline.ast

/**
 * Tuning for the Gemma AST layer. Defaults are the configuration validated
 * end-to-end in Fase 0 (`gemma-ast-poc/`) — do not change without re-running
 * that POC's audio AST smoke test.
 */
data class AstConfig(
    /**
     * Directory on the device that holds the Gemma `.litertlm` model and its
     * required companion files (xnnpack caches, weight shards). The default
     * matches the location used by `gemma-ast-poc/` so the model copied
     * there in Fase 0 is reused as-is.
     */
    val modelDirPath: String = "/sdcard/Download/gemma_model",

    /**
     * Model file name inside [modelDirPath].
     *
     * We briefly bumped this to the official post-2026-05-05 `gemma-4-E4B-it.
     * litertlm` export (which embeds the MTP drafter) and turned MTP on by
     * default. Device testing showed:
     *   - Translations were noticeably WORSE — replies came out in Spanish
     *     more often, English translations were garbled, latency was
     *     actually higher.
     *   - MTP did NOT move first-token latency in either direction: our
     *     replies are ~5–10 tokens long, and MTP accelerates DECODE, not
     *     prefill. On short outputs the drafter's win is negligible while
     *     the model swap ate all the latency budget.
     *
     * Reverted to the Fase 0 export. See [mtpEnabled] for the MTP-off
     * default rationale.
     */
    val modelFilename: String = "gemma4_4b_v09_obfus_fix_all_modalities_thinking.litertlm",

    /**
     * Official Google-recommended AST prompt for Gemma multimodal audio.
     *
     * From Google's docs: an AST prompt should ask the model to (a) transcribe
     * the source-language audio, and (b) translate it, with an explicit
     * separator between the two so a downstream parser can extract the
     * translation deterministically.
     *
     * The `AstChunkRouter` extracts everything after `"English: "` before
     * emitting `TranslationReady` — see [useOfficialAstPrompt] and the
     * router's `extractEnglishTranslation` for the exact parse. If Gemma
     * ignores the format (no marker in the reply), the router falls back
     * to using the whole reply as-is and logs a warning.
     *
     * Active when [useOfficialAstPrompt] is `true` (default). To A/B against
     * the legacy prompt, flip [useOfficialAstPrompt] off — [legacyPrompt]
     * takes over.
     */
    val prompt: String =
        "Transcribe the following speech segment in Spanish, then translate " +
            "it into English. When formatting the answer, first output the " +
            "transcription in Spanish, then one newline, then output the " +
            "string 'English: ', then the translation in English.",

    /**
     * Fallback prompt kept for reverting if [useOfficialAstPrompt] is flipped
     * off after a device regression. This is the Fase 4 → Fase 6 prompt that
     * shipped and passed device validation with ~2.7 s avg latency and no
     * Spanish echoes. It asks Gemma to output ONLY the English translation,
     * so the router does not run the English-marker extraction on this path.
     *
     * The "completely" + "without truncating" wording came out of Fase 4
     * device testing: longer utterances (>5 s) occasionally stopped
     * mid-sentence (e.g. "start a new" instead of "start a new shift"). The
     * explicit completeness instruction nudges the sampler to emit a full
     * translation before EOS.
     */
    val legacyPrompt: String =
        "Translate the following Spanish audio to English completely. " +
            "Output the full translated sentence without truncating. " +
            "Respond with only the English translation, no other text or commentary.",

    /** Maximum tokens per response — matches the EngineConfig validated in Fase 0. */
    val maxNumTokens: Int = 1024,

    /**
     * Try GPU backend first. If it fails (typical reasons: missing companion
     * files, OpenCL not available on the device), the engine falls back to
     * CPU automatically. The CPU path is slower (~3–5× per inference) but
     * always works.
     */
    val preferGpu: Boolean = true,

    /**
     * Bounded queue capacity for the chunk → Gemma channel. One inference
     * takes ~2 s; a typical chunk is 3–6 s of audio. With capacity 4 we
     * tolerate ~16 s of conversational backlog before the channel drops
     * the oldest queued chunk (DROP_OLDEST policy in the router).
     */
    val queueCapacity: Int = 4,

    /**
     * Pre-inference RMS gate. Chunks with a per-sample RMS **below** this
     * value are dropped by the router before being handed to Gemma —
     * roughly "there's not enough energy for real speech to be inside".
     *
     * Without the gate, near-silent chunks (someone breathes into the mic,
     * or the chunker emits a max-size chunk during a long pause) reach
     * Gemma and the model responds with meta-text like "audio not
     * provided" — wasting ~2.8 s of GPU per empty chunk. RMS on int16 PCM
     * has a natural scale of ~0..32 767; empirically 500 sits below
     * normal quiet-speech (~800–2 000) but above room noise (~150).
     *
     * Set to `0.0` to disable the gate (useful in tests).
     */
    val rmsThreshold: Double = 500.0,

    /**
     * Post-inference meta-text filter. If Gemma's reply (lower-cased,
     * trimmed) contains ANY of these substrings, the translation is
     * discarded — Kokoro never speaks "please provide the audio" or
     * "no audio" through the speaker.
     *
     * Substring match (not exact) so variants ("no audio detected",
     * "no audio was provided", …) all hit the same rule. Kept
     * intentionally short — every entry here is a lever the model can
     * dodge with a rephrase, so we only list phrases observed in device.
     */
    /**
     * Fase 6 Stage A — token streaming from Gemma.
     *
     * When `true`, `AstChunkRouter` invokes `GemmaAstEngine.translateStreaming`
     * and emits ONE `TranslationReady` per sentence as tokens arrive, so
     * `TtsRouter` / `KokoroTtsEngine` can begin synthesising sentence 1 while
     * Gemma is still decoding sentence 2. Each event carries `sentenceIndex`
     * (0-based) and `isFinal` (true on the last one).
     *
     * When `false` the router uses the legacy full-utterance
     * `engine.translate(...)` path — one `ChunkReady` → one `TranslationReady`
     * with `sentenceIndex = null`, `isFinal = true`. Flipped to `true` by
     * default after Fase 6 device validation (3-round protocol: 0 crashes,
     * first-token latency dropped from ~2000ms to ~1170ms). The UI switch
     * still allows disabling it at runtime if a regression appears.
     */
    val streamingEnabled: Boolean = true,

    /**
     * Multi-Token Prediction (MTP) / speculative decoding for Gemma decode.
     *
     * When `true`, [LiteRtGemmaAstEngine.load] sets
     * `ExperimentalFlags.enableSpeculativeDecoding = true` **before**
     * `engine.initialize()`. The runtime uses the model's built-in MTP
     * drafter to speculate on the next N tokens and verify them in a single
     * decode step — advertised as ~2.2× speedup on decode-heavy workloads.
     *
     * **Default is `false`** after device testing:
     *   - Translation outputs are short (~5–10 tokens). MTP accelerates
     *     DECODE, not prefill. On short outputs the drafter's win is
     *     negligible while it adds runtime overhead.
     *   - Our Fase 0 model export (see [modelFilename]) does not embed the
     *     MTP drafter subgraph — the flag would be a silent no-op even if
     *     it did produce a gain.
     *
     * The UI toggle is preserved as a kill-switch / experiment lever for
     * future model swaps or long-form workloads where decode dominates.
     * When `false` we DO NOT touch [ExperimentalFlags] at all — SDK default.
     *
     * Caveat: to actually benefit from MTP the `.litertlm` model file must
     * have been produced **after 2026-05-05** to embed the drafter; older
     * exports (including our current one) ignore the flag with no crash.
     */
    val mtpEnabled: Boolean = false,

    val metaTextPatterns: List<String> = listOf(
        // Silence / no-input replies
        "not provided",
        "no audio",
        "please provide",
        "no spanish",
        "cannot translate",
        "no speech",
        // Assistant-style preambles that leak into the reply and would be
        // spoken by Kokoro if we let them through. All observed in device.
        // Examples:
        //   "The translation of the Spanish audio is: 'I'm going to the store.'"
        //   "Here is the translation: hello"
        //   "The audio is: hello"
        //
        // Post-device-validation tuning: patterns were narrowed to avoid
        // false positives on legitimate translations that happen to contain
        // the word "translation" or "audio" (e.g. "This is the translation
        // test number one." or "Turn off the audio."). Two-word substrings
        // like "the translation" and "the audio" matched real content; we
        // now require the assistant-preamble verb ("is" / "was" / "of").
        "translation of",
        "the translation of",
        "the translation is",
        "spanish audio",
        "translate the",
        "here is the",
        "the audio is",
        "the audio was",
    ),

    /**
     * Google's multimodal Gemma docs: "For optimal performance with multimodal
     * inputs, place audio content **after** the text in your prompt." A
     * Google implementation article puts it more bluntly: "Getting this order
     * wrong will reduce accuracy."
     *
     * When `true` (default), `LiteRtGemmaAstEngine` builds
     * `Contents.of(Content.Text(prompt), Content.AudioBytes(wav))` — text
     * first, audio last, per official guidance.
     *
     * When `false`, the engine uses the legacy Fase 0 order
     * `Contents.of(Content.AudioBytes(wav), Content.Text(prompt))` that
     * shipped through Fase 5. Left as a runtime flag so a device regression
     * on the new order is a single-toggle revert.
     */
    val audioAfterText: Boolean = true,

    /**
     * Selects between [prompt] (Google's official AST prompt asking for
     * transcription + translation with an `English: ` marker) and
     * [legacyPrompt] (the Fase 4 prompt asking for English translation only).
     *
     * When `true` (default), `AstChunkRouter` uses [prompt] AND runs the
     * English-marker extraction on Gemma's reply. When `false`, the router
     * uses [legacyPrompt] and treats the whole reply as English (no
     * extraction). Kept as a runtime flag to A/B and to revert cleanly if
     * the official format regresses on device.
     */
    val useOfficialAstPrompt: Boolean = true,
) {
    init {
        require(modelDirPath.isNotBlank()) { "modelDirPath must not be blank" }
        require(modelFilename.endsWith(".litertlm")) {
            "modelFilename should end in .litertlm, got '$modelFilename'"
        }
        require(maxNumTokens in 1..4096) { "maxNumTokens out of range: $maxNumTokens" }
        require(queueCapacity in 1..32) { "queueCapacity out of range: $queueCapacity" }
        require(prompt.isNotBlank()) { "prompt must not be blank" }
        require(legacyPrompt.isNotBlank()) { "legacyPrompt must not be blank" }
        require(rmsThreshold >= 0.0) { "rmsThreshold must be >= 0, got $rmsThreshold" }
    }

    val modelPath: String get() = "$modelDirPath/$modelFilename"

    /** The prompt string actually sent to Gemma, resolved from [useOfficialAstPrompt]. */
    val activePrompt: String get() = if (useOfficialAstPrompt) prompt else legacyPrompt
}
