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
     * Translation-only prompt with the `English: ` marker that the router's
     * extraction depends on.
     *
     * The Google-recommended AST format asks for transcription-then-translation
     * ("Transcribe the following speech segment in Spanish, then translate…")
     * — device testing showed Gemma dutifully emitted 22–72 characters of
     * Spanish text before the `English:` marker on every chunk, adding
     * ~1–2 s of wasted decode per translation. We keep the marker (so the
     * router's `extractEnglishTranslation` still works) but explicitly
     * instruct the model NOT to include the Spanish transcription, cutting
     * the entire wasted prefix.
     *
     * Active when [useOfficialAstPrompt] is `true` (default). If Gemma
     * still emits Spanish somehow, the router's `English:` extraction plus
     * marker-missing fallback keep the pipeline safe.
     */
    val prompt: String =
        "Translate the following Spanish speech into English. " +
            "Output only the string 'English: ' followed by the translation " +
            "in English. Do not include the original Spanish text.",

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
     * Use GPU for the audio encoder path (`EngineConfig.audioBackend`).
     *
     * **Requires LiteRT-LM ≥ 0.14 AND a model export whose audio graph is
     * GPU-compatible.** Fase 7 attempted the 0.15 upgrade to unlock this
     * — device testing showed GPU audio never activated with our current
     * model (silent fallback to CPU) AND first-token latency regressed
     * from ~1170 ms to ~2000 ms AND the app crashed twice under memory
     * pressure. Reverted to 0.12.
     *
     * On 0.12 the audio backend factory can still be constructed as
     * `Backend.GPU()` but `Engine.initialize()` will throw for the audio
     * subgraph; the load path's `(main, audio)` fallback chain handles it
     * gracefully and drops to CPU-audio. Net effect: with 0.12 + our
     * current model the flag is functionally a no-op — kept as `false`
     * default so the first attempt in the fallback chain is `GPU main +
     * CPU audio`, saving one wasted initialize() call.
     *
     * Set `true` when re-attempting the SDK upgrade with a compatible
     * model — the flag flip alone will re-enable the GPU-audio attempt.
     */
    val audioBackendGpu: Boolean = false,

    /**
     * Full-duplex mode. When `true`, the mic is NEVER muted while Kokoro /
     * Android TTS is speaking — the chunker keeps collecting audio and
     * emitting new chunks while translations play back. This lets the user
     * speak continuously (OpenAI-style barge-in / conversational mode)
     * without waiting for the previous translation to finish.
     *
     * Wired at the [com.travel2chicago.gemmapipeline.pipeline.VadChunkingPipeline]
     * level:
     *   - `handleAudioData` bypasses the `ttsPlaying` check when this is on.
     *   - `handleMuteRisingEdge` is NOT called at start-of-TTS (no chunker
     *     flush + no VAD/reassembler reset).
     *   - `TtsAudioPlayer` bookends (`beginUtterance` / `endUtterance`)
     *     still fire — the shared `ttsPlaying` flag still tracks playback
     *     state for the UI, we just stop acting on it in the mic path.
     *
     * **Physical constraint**: with an omnidirectional mic + a speaker in
     * the same room, the mic WILL re-capture the TTS output and feed it
     * back into Gemma as "speech". Full-duplex mode is only safe with:
     *   (a) a unidirectional / cardioid mic pointing away from the speaker
     *       (production hardware for viaje use case), or
     *   (b) headphones / an isolated speaker (device testing).
     *
     * Default flipped to `true` post-cierre after device validation
     * (14+ traducciones, 0 errores, sessions of 10+ min): with hardware
     * echo cancellation now on by default via [aecEnabled] (see below),
     * the feedback risk that used to keep this off is materially
     * mitigated. Toggle in the UI to revert to half-duplex if a specific
     * room / speaker combo produces feedback loops the AEC can't
     * suppress.
     */
    val fullDuplexMode: Boolean = true,

    /**
     * Hardware acoustic echo cancellation (AEC) on the mic capture path.
     *
     * When `true` (default), the Oboe capture stream opens with
     * `InputPreset::VoiceCommunication` — the AAudio HAL-level
     * equivalent of `MediaRecorder.AudioSource.VOICE_COMMUNICATION` —
     * which engages the device's AEC processor to subtract the
     * loopback of what our own speaker is playing (Kokoro / Android
     * TTS output) from the mic signal. As a defence-in-depth layer,
     * [com.travel2chicago.gemmapipeline.audio.AudioCaptureManager]
     * also attaches an `android.media.audiofx.AcousticEchoCanceler`
     * to the Oboe stream's session id when the framework reports the
     * effect is available on this device.
     *
     * When `false`, the capture stream opens with the Fase 2 preset
     * `InputPreset::Unprocessed` — no AEC, no noise suppression, no
     * AGC. This was the default up to the POC closure so the pipeline
     * saw pristine mic samples; useful when validating VAD/chunker
     * tuning without HAL processing interference, or when the user
     * observes that AEC clips their voice.
     *
     * AEC introduces ~10–20 ms of processing latency (negligible
     * relative to Gemma / Kokoro), and it can subtly change the
     * amplitude envelope of the mic signal. If the RMS pre-filter
     * ([com.travel2chicago.gemmapipeline.ast.AstConfig.rmsThreshold])
     * starts dropping legitimate chunks after AEC is enabled, lower
     * the threshold before disabling AEC — the trade-off usually
     * favours keeping AEC on.
     *
     * Toggle takes effect on the NEXT capture start (the input preset
     * is passed at `openStream` time; changing it live requires
     * closing + reopening the Oboe stream — the ViewModel handles
     * that by restarting capture when the toggle flips).
     */
    val aecEnabled: Boolean = true,

    /**
     * Software WebRTC AECM applied on top of the HAL AEC ([aecEnabled]).
     *
     * The HAL AEC only reaches the built-in mic + built-in speaker path.
     * External hardware (USB mic like the Saramonic, BT speaker like the
     * JBL Go 4) bypasses that AEC — its echo reaches the mic unreduced.
     * With this flag ON, [com.travel2chicago.gemmapipeline.audio.AecProcessor]
     * runs a software echo canceller that:
     *   - takes each Kokoro PCM buffer as far-end reference (from
     *     [com.travel2chicago.gemmapipeline.tts.TtsAudioPlayer.play],
     *     resampled 24 → 16 kHz), and
     *   - filters the mic near-end capture (from
     *     [com.travel2chicago.gemmapipeline.pipeline.VadChunkingPipeline],
     *     already at 16 kHz post-decimation).
     *
     * The two AEC layers are complementary: keep [aecEnabled] on for
     * the built-in path, keep this on for the external path. Both can
     * be independently toggled.
     *
     * ## POC caveats (default `false`)
     *
     *   - **Delay hint is hardcoded** at 200 ms (BT A2DP typical). If
     *     device measurement shows a different value, tune
     *     `AecProcessor.DEFAULT_DELAY_MS` — auto-estimation is out of
     *     scope for the POC.
     *   - **Fast (Android system) TTS is NOT instrumented** — the OS
     *     speaks directly to the audio stack, we have no PCM handle to
     *     feed as far-end reference. Software AEC is Kokoro-only in
     *     this iteration.
     *   - **WebRTC AECM (not AEC3)** — AECM tolerates ~10 ms delay
     *     jitter well; A2DP jitters ±50 ms. Expected cancellation over
     *     BT: 30–50 %; over wired USB DAC: 80–95 %.
     *   - **Adds ~10 ms of processing latency** per 10 ms frame,
     *     negligible relative to Gemma / Kokoro.
     *
     * Off by default because the layer is unproven on device. Flip on
     * from the UI, measure, then decide whether to promote to default
     * or invest in AEC3 for better BT tolerance.
     */
    val webrtcAecEnabled: Boolean = false,

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
        // Narrative-wrapping preambles observed on device — Gemma sometimes
        // narrates the utterance ("It says 'hello'.", "He says 'good morning'.",
        // "She says 'thank you'.") instead of translating it directly. The
        // prefix window filter catches these when they lead the reply; the
        // extra patterns are needed because the plain "says" phrasing doesn't
        // hit any of the existing "translation of" / "audio was" rules.
        "it says",
        "he says",
        "she says",
    ),

    /**
     * Meta-text patterns only match when they appear inside the FIRST
     * [metaTextPrefixChars] of the reply (after English marker extraction).
     * Rationale: assistant preambles like "The translation of the Spanish
     * audio is: ..." always come at the start of the reply. Matching
     * anywhere in the text produces false positives — e.g. `"the
     * translation is"` blocked the legitimate reply "They tell me how
     * the translation is going". A short prefix window keeps preamble
     * detection but excludes mid-sentence mentions of the same words.
     *
     * 60 chars = ~10 words, which comfortably covers every preamble
     * observed on device without slicing into normal sentence content.
     * Set to `Int.MAX_VALUE` to restore full-text matching (legacy
     * behaviour before the false-positive fix).
     */
    val metaTextPrefixChars: Int = 60,

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

    /**
     * Capa 1 anti-eco — discard audio that is not Spanish.
     *
     * With full-duplex ON and external hardware (ModMic USB + JBL BT) the
     * mic re-captures Kokoro's English output. Neither HAL AEC (internal
     * path only) nor WebRTC AECM (BT delay jitter) stopped the loop on
     * device. When `true`, [activePrompt] appends [skipNonSpanishInstruction]
     * so Gemma answers `English: SKIP` for non-Spanish audio, and
     * `AstChunkRouter` drops any reply whose normalised text (trim, no
     * punctuation, upper-case) is `SKIP` — in one-shot AND streaming, where
     * the router holds sentence emission while the buffer is still an
     * unambiguous prefix of `SKIP` so the token never reaches TTS.
     *
     * With `false`, the prompt and router behaviour are exactly the
     * pre-anti-eco ones.
     */
    val skipNonSpanish: Boolean = true,

    /** Appended to the active prompt when [skipNonSpanish] is on. */
    val skipNonSpanishInstruction: String =
        "If the audio is not in Spanish (for example, it is English speech), " +
            "output exactly 'English: SKIP' and nothing else.",

    /**
     * Capa 2 anti-eco — drop translations that are textually similar to a
     * sentence the TTS spoke within the last [echoWindowMs]. See
     * [EchoTextHistory] for the similarity metric.
     */
    val echoTextFilterEnabled: Boolean = true,

    /** Similarity (0..1) at or above which a translation counts as echo. */
    val echoSimilarityThreshold: Double = 0.6,

    /** How long a spoken TTS sentence stays eligible as an echo source. */
    val echoWindowMs: Long = 15_000L,
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
        require(echoSimilarityThreshold in 0.0..1.0) {
            "echoSimilarityThreshold must be in 0..1, got $echoSimilarityThreshold"
        }
        require(echoWindowMs >= 0L) { "echoWindowMs must be >= 0, got $echoWindowMs" }
    }

    val modelPath: String get() = "$modelDirPath/$modelFilename"

    /**
     * The prompt string actually sent to Gemma, resolved from
     * [useOfficialAstPrompt], plus [skipNonSpanishInstruction] when
     * [skipNonSpanish] is on.
     */
    val activePrompt: String get() {
        val base = if (useOfficialAstPrompt) prompt else legacyPrompt
        return if (skipNonSpanish) "$base $skipNonSpanishInstruction" else base
    }

    companion object {
        /** UI slider bounds for [rmsThreshold] (Capa 3). */
        const val RMS_SLIDER_MIN = 200.0
        const val RMS_SLIDER_MAX = 4000.0
    }
}
