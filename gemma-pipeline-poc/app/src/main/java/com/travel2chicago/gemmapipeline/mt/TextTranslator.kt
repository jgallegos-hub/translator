package com.travel2chicago.gemmapipeline.mt

import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.travel2chicago.gemmapipeline.ast.LiteRtGemmaAstEngine

/** Spanish -> English text translation step of the cascade. */
interface TextTranslator : AutoCloseable {
    val name: String

    /**
     * Translate [spanish]. [onToken] receives the output as it is produced
     * (deltas; a single call for non-streaming engines). Returns the full text
     * and the time to the first output in ms.
     */
    suspend fun translate(spanish: String, onToken: suspend (String) -> Unit): Pair<String, Long>
}

/** ML Kit on-device NMT (~16 ms/phrase on device, literal on slang). */
class MlKitTranslator private constructor(private val translator: Translator) : TextTranslator {
    override val name = "ML Kit"

    override suspend fun translate(spanish: String, onToken: suspend (String) -> Unit): Pair<String, Long> {
        val t0 = System.nanoTime()
        val out = Tasks.await(translator.translate(spanish)).trim()
        val ms = (System.nanoTime() - t0) / 1_000_000
        if (out.isNotEmpty()) onToken(out)
        return out to ms
    }

    override fun close() = translator.close()

    companion object {
        /** Blocking: downloads the es->en model on first use (~30 MB). Call off the main thread. */
        fun load(): MlKitTranslator {
            val t = Translation.getClient(
                TranslatorOptions.Builder()
                    .setSourceLanguage(TranslateLanguage.SPANISH)
                    .setTargetLanguage(TranslateLanguage.ENGLISH)
                    .build()
            )
            Tasks.await(t.downloadModelIfNeeded(DownloadConditions.Builder().build()))
            return MlKitTranslator(t)
        }
    }
}

/** Gemma 4 E4B fed the transcript as text (best slang quality, ~0.84 s first token). */
class GemmaTextTranslator(private val engine: LiteRtGemmaAstEngine) : TextTranslator {
    override val name = "Gemma text"

    override suspend fun translate(spanish: String, onToken: suspend (String) -> Unit): Pair<String, Long> {
        val (text, first, _) = engine.translateText(GemmaTextBench.PROMPT + spanish, onToken)
        return text to first
    }

    /** The engine is owned by the ViewModel. */
    override fun close() = Unit
}
