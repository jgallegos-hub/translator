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
) {
    init {
        require(modelDirPath.isNotBlank()) { "modelDirPath must not be blank" }
        require(modelFilename.endsWith(".litertlm")) {
            "modelFilename should end in .litertlm, got '$modelFilename'"
        }
        require(maxNumTokens in 1..4096) { "maxNumTokens out of range: $maxNumTokens" }
        require(queueCapacity in 1..32) { "queueCapacity out of range: $queueCapacity" }
        require(prompt.isNotBlank()) { "prompt must not be blank" }
    }

    val modelPath: String get() = "$modelDirPath/$modelFilename"
}
