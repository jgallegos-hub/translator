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

    /** Model file name inside [modelDirPath]. */
    val modelFilename: String = "gemma4_4b_v09_obfus_fix_all_modalities_thinking.litertlm",

    /**
     * Prompt sent alongside each audio chunk. Validated phrasing from Fase 0
     * — keep terse and explicit so Gemma does not add commentary.
     *
     * The "completely" + "without truncating" wording was added after Fase 4
     * device testing: with the original prompt, longer utterances (>5 s)
     * occasionally stopped mid-sentence (e.g. "start a new" instead of
     * "start a new shift"). The explicit completeness instruction nudges the
     * sampler to emit a full translation before EOS.
     */
    val prompt: String =
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
        "translation of",
        "the translation",
        "spanish audio",
        "translate the",
        "here is the",
        "the audio",
    ),
) {
    init {
        require(modelDirPath.isNotBlank()) { "modelDirPath must not be blank" }
        require(modelFilename.endsWith(".litertlm")) {
            "modelFilename should end in .litertlm, got '$modelFilename'"
        }
        require(maxNumTokens in 1..4096) { "maxNumTokens out of range: $maxNumTokens" }
        require(queueCapacity in 1..32) { "queueCapacity out of range: $queueCapacity" }
        require(prompt.isNotBlank()) { "prompt must not be blank" }
        require(rmsThreshold >= 0.0) { "rmsThreshold must be >= 0, got $rmsThreshold" }
    }

    val modelPath: String get() = "$modelDirPath/$modelFilename"
}
