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
    /** Hardcoded voice for v1; selector UI is a post-POC follow-up. */
    val voice: String = "af_heart",
    /** Kokoro outputs PCM at this rate (mono float32 → we convert to int16). */
    val sampleRate: Int = 24_000,
    /** Hard cap on token IDs per ONNX call. Long inputs are split by sentence. */
    val maxTokens: Int = 510,
    /**
     * Bounded queue for the TranslationReady → TTS bridge. ~1.5 s per sentence
     * × cap 4 ≈ 6 s of backlog before DROP_OLDEST kicks in.
     */
    val queueCapacity: Int = 4,
) {
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
    }
}
