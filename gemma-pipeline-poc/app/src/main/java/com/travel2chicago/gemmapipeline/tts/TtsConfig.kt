package com.travel2chicago.gemmapipeline.tts

/**
 * Tuning for the Kokoro TTS layer. Defaults track the Kokoro-82M v1.0 release
 * from `huggingface.co/onnx-community/Kokoro-82M-v1.0-ONNX` and the demo Android
 * implementation at `github.com/puff-dayo/Kokoro-82M-Android`.
 *
 * The two big binary files ([modelFilename], [voicesFilename]) live on
 * external storage at [modelDirPath] so the APK stays small and the model can
 * be swapped without a reinstall. The tokenizer config + dictionary are
 * bundled in `assets/` because they are small (<5 MB combined) and tightly
 * coupled to the model version.
 */
data class TtsConfig(
    /** Directory on the device holding the Kokoro ONNX model and voice file. */
    val modelDirPath: String = "/sdcard/Download/kokoro_model",
    /** Int8-quantized model (~88 MB) — the int8 build, not the 310 MB fp32 one. */
    val modelFilename: String = "kokoro-v1.0.int8.onnx",
    /** Voice-style embeddings, all voices concatenated (~5 MB). */
    val voicesFilename: String = "voices-v1.0.bin",
    /** Bundled phoneme→token-id map + voice index table. */
    val configAsset: String = "kokoro_config.json",
    /**
     * Bundled English pronunciation dictionary (CMU words → IPA).
     * Sourced from puff-dayo/Kokoro-82M-Android — ~125k entries, ~3 MB,
     * tab-separated, lines starting with `;;;` are comments.
     */
    val dictionaryAsset: String = "cmudict_ipa.dict",
    /**
     * Active Kokoro voice. The NPZ ships ~54 voices; UI currently exposes a
     * two-option toggle (Male = `am_puck`, Female = `af_heart`). Default
     * flipped to `am_puck` after post-fix device A/B (was `am_adam`,
     * previously `am_michael`) — `am_puck` came out of the wider male
     * catalogue as the clearest match for the travel user (natural
     * cadence, no reverb tail on the JBL, per-sentence Kokoro latency
     * comparable to the fastest of the male set). Full-voice selector
     * remains a post-POC follow-up if we need to expose the other
     * `am_*` / `af_*` voices again.
     */
    val voice: String = "am_puck",
    /** Kokoro outputs PCM at this rate (mono float32 → we convert to int16). */
    val sampleRate: Int = 24_000,
    /** Hard cap on token IDs per ONNX call. Long inputs are split by sentence. */
    val maxTokens: Int = 510,
    /**
     * Bounded queue for the TranslationReady → TTS bridge. ~1.5 s per sentence
     * × cap 4 ≈ 6 s of backlog before DROP_OLDEST kicks in.
     */
    val queueCapacity: Int = 4,

    /**
     * Fase 6 Stage B — streaming Kokoro TTS.
     *
     * When `true`, [TtsRouter] uses [KokoroTtsEngine.synthesizeStreaming]
     * and hands each per-sentence PCM directly to the [TtsPlayerSink] as
     * the ONNX inference completes it, so playback of sentence 1 starts
     * while sentence 2 is still being synthesised. Bookends the playback
     * with `beginUtterance` / `endUtterance` on the sink so the shared
     * `ttsPlaying` mute flag stays `true` across the whole utterance and
     * the VAD doesn't see spurious un-mute edges between sentences.
     *
     * When `false` the router uses the legacy full-utterance
     * `engine.synthesize` path and emits `TtsAudioReady` on the bus for
     * the ViewModel to play — one call, one begin/end (implicit in
     * `play()`), same as Fase 5.
     *
     * Flipped to `true` by default after Fase 6 device validation:
     * first-audio latency dropped from ~3700ms to ~3400ms (modest with
     * single-sentence utterances, significant with multi-sentence ones);
     * bookends held the mute flag steady across sentence boundaries.
     * The UI switch still allows disabling it at runtime.
     */
    val streamingEnabled: Boolean = true,

    /**
     * Fast/Quality TTS toggle.
     *
     * When `true`, [TtsRouter] routes each translation through
     * [AndroidTtsEngine] (system `android.speech.tts.TextToSpeech`) — the
     * OS speaks directly to the system audio output, bypassing our
     * per-sentence PCM handoff to [TtsAudioPlayer]. Typical latency
     * ~100–300 ms per utterance on the Xiaomi 15T Pro.
     *
     * When `false` (default), the router uses [KokoroTtsEngine] as in
     * Fase 5 — higher voice quality (~1500–2500 ms per sentence) with the
     * full streaming pipeline described above.
     *
     * Streaming ([streamingEnabled]) is a Kokoro-only concept — the Android
     * TTS path always speaks the whole translation in one call (its own
     * chunking is opaque to us). When [useFastMode] is on, the streaming
     * flag is ignored on the render side but its state is preserved so the
     * user can flip fast mode off and get the streaming behaviour back.
     */
    val useFastMode: Boolean = false,

    /**
     * Kokoro synthesis speed multiplier — passed through to the ONNX model's
     * `speed` input on every `synthesize` / `synthesizeStreaming` call.
     *
     * Range: `0.8` (slower, longer PCM) to `1.5` (faster, shorter PCM). The
     * UI slider clamps to this range with a `0.1` step. Values > 1.0 reduce
     * per-sentence latency proportionally (fewer output samples generated)
     * at the cost of a slightly clipped-sounding voice; values < 1.0 do the
     * opposite. Default 1.0 = no change vs the ONNX baseline.
     *
     * Applied per-call: [TtsRouter] reads `speed` off its own volatile field
     * (fed by [GemmaPipelineViewModel.setTtsSpeed]) rather than off a
     * captured config, so slider changes take effect on the NEXT sentence
     * without needing a router restart. See [TtsRouter.setSpeed].
     *
     * The two ONNX exports we support disagree on the speed tensor's dtype:
     * newer `Kokoro-82M-v1.0-ONNX` uses `float32`, older `kokoro-onnx ≤ 0.4`
     * uses `float32` too but some int-quantised exports appear as `int32`.
     * [KokoroOnnxEngine] introspects the input type at load time and picks
     * `FloatBuffer` / `IntBuffer` accordingly; when the model is `int32`
     * the slider rounds to the nearest integer and the effective range
     * collapses to `{1, 2}` — logged as a warning at load time.
     *
     * Default `1.2f` (was `1.0f`) after post-fix device A/B — the ~20 %
     * speed-up shaves noticeable time off each translated sentence
     * without audibly degrading `am_puck`'s cadence. Slider still lets
     * the user dial back to 1.0x (or 0.8x) if the faster voice sounds
     * off in a specific room.
     */
    val speed: Float = 1.2f,
) {
    companion object {
        const val MIN_SPEED = 0.8f
        const val MAX_SPEED = 1.5f
    }
    val modelPath: String get() = "$modelDirPath/$modelFilename"
    val voicesPath: String get() = "$modelDirPath/$voicesFilename"

    init {
        require(modelDirPath.isNotBlank()) { "modelDirPath must not be blank" }
        require(modelFilename.endsWith(".onnx")) {
            "modelFilename should end in .onnx, got '$modelFilename'"
        }
        require(voicesFilename.endsWith(".bin")) {
            "voicesFilename should end in .bin, got '$voicesFilename'"
        }
        require(sampleRate in 8_000..48_000) { "sampleRate out of range: $sampleRate" }
        require(maxTokens in 1..512) { "maxTokens out of range: $maxTokens" }
        require(queueCapacity in 1..32) { "queueCapacity out of range: $queueCapacity" }
        require(voice.isNotBlank()) { "voice must not be blank" }
        require(speed in MIN_SPEED..MAX_SPEED) {
            "speed out of range [$MIN_SPEED, $MAX_SPEED], got $speed"
        }
    }
}
