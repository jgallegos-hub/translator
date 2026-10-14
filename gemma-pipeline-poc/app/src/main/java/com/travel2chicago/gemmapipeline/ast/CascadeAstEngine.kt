package com.travel2chicago.gemmapipeline.ast

import android.util.Log
import com.travel2chicago.gemmapipeline.asr.MoonshineAsr
import com.travel2chicago.gemmapipeline.mt.TextTranslator
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val TAG = "CascadeAstEngine"

/**
 * ASR -> MT cascade behind the [GemmaAstEngine] interface, so [AstChunkRouter]
 * (queue, RMS gate, sentence streaming, meta/echo filters) and the TTS path
 * stay unchanged. The router's Gemma prompt is ignored.
 *
 * Per chunk: Moonshine transcribes the PCM, lines under [lineRmsThreshold]
 * (echo residue) are dropped, and the remaining Spanish text goes to
 * [translator]. An empty transcript yields an empty reply, which the router
 * treats as "nothing to say" - silence is never sent to the translator.
 */
class CascadeAstEngine(
    private val asr: MoonshineAsr,
    private val translator: TextTranslator,
    private val lineRmsThreshold: Double,
    /** Auto mode: transcripts with >= [longPhraseWords] words go here instead
     *  (Gemma text: better meaning on long conversational speech, and its
     *  punctuation lets Kokoro start on the first sentence). */
    private val longTranslator: TextTranslator? = null,
    private val longPhraseWords: Int = 10,
    /** Stricter residue gate while our own TTS is playing (validation round
     *  2026-10-14: residue lines reached RMS 1850-2200 during playback). */
    private val strictLineRmsThreshold: Double = lineRmsThreshold,
    private val isTtsActive: () -> Boolean = { false },
) : GemmaAstEngine {

    override val isLoaded: Boolean = true
    override val backendUsed: String =
        "Moonshine+${translator.name}" + (longTranslator?.let { "/${it.name} (≥$longPhraseWords words)" } ?: "")
    override val loadTimeMs: Long = 0

    override fun translate(wavBytes: ByteArray, prompt: String, audioAfterText: Boolean): AstResult =
        kotlinx.coroutines.runBlocking { translateStreaming(wavBytes, prompt, audioAfterText) {} }

    override suspend fun translateStreaming(
        wavBytes: ByteArray,
        prompt: String,
        audioAfterText: Boolean,
        onToken: suspend (token: String) -> Unit,
    ): AstResult {
        val t0 = System.nanoTime()
        val samples = pcmFromWav(wavBytes)
        val lines = asr.transcribe(samples)
        val asrMs = (System.nanoTime() - t0) / 1_000_000
        val ttsActive = isTtsActive()
        val gate = if (ttsActive) strictLineRmsThreshold else lineRmsThreshold
        val kept = lines.filter { it.rms >= gate }
        for (l in lines - kept.toSet()) {
            Log.i(TAG, "CASCADE drop echo-residue line rms=${"%.0f".format(l.rms)} gate=${"%.0f".format(gate)} ttsActive=$ttsActive text='${l.text}'")
        }
        val spanish = kept.joinToString(" ") { it.text }.trim()
        if (spanish.isEmpty()) {
            Log.i(TAG, "CASCADE asrMs=$asrMs empty transcript (lines=${lines.size}) — nothing to translate")
            return AstResult("", asrMs, backendUsed)
        }
        val words = spanish.split(WHITESPACE).size
        val mt = if (longTranslator != null && words >= longPhraseWords) longTranslator else translator
        // Kokoro needs a closing terminator: unpunctuated ASR text produced a
        // trailing "ahhh" on device (validation 2026-10-14), and the router
        // only splits sentences at . ! ? — so make sure the reply ends with one.
        var english: String
        val mtFirstMs: Long
        if (mt is com.travel2chicago.gemmapipeline.mt.MlKitTranslator) {
            val (raw, first) = mt.translate(finishSentence(spanish)) {}
            english = finishSentence(raw)
            mtFirstMs = first
            if (english.isNotEmpty()) onToken(english)
        } else {
            val (raw, first) = mt.translate(spanish, onToken)
            english = raw
            mtFirstMs = first
            if (english.isNotEmpty() && english.last() !in TERMINATORS) { onToken("."); english += "." }
        }
        val totalMs = (System.nanoTime() - t0) / 1_000_000
        Log.i(
            TAG,
            "CASCADE mt=${mt.name} words=$words asrMs=$asrMs mtFirstMs=$mtFirstMs totalMs=$totalMs " +
                "lineRms=${kept.joinToString(",") { "%.0f".format(it.rms) }} es='$spanish' en='$english'",
        )
        return AstResult(english, totalMs, backendUsed)
    }

    companion object {
        private val TERMINATORS = setOf('.', '!', '?')
        private val WHITESPACE = Regex("""\s+""")

        /** Capitalise and make sure [text] ends with . ! or ? (quotes allowed after it). */
        fun finishSentence(text: String): String {
            val t = text.trim()
            if (t.isEmpty()) return t
            val cap = t.replaceFirstChar { it.uppercaseChar() }
            val core = cap.trimEnd('"', '\'', '»', ')')
            return if (core.isNotEmpty() && core.last() in TERMINATORS) cap else "$cap."
        }
    }

    /** ASR and translator are owned by the ViewModel (reused across router restarts). */
    override fun close() = Unit

    private fun pcmFromWav(wav: ByteArray): ShortArray {
        // WavBuilder writes a canonical 44-byte header; find "data" to be safe.
        val bb = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 12
        while (pos + 8 <= wav.size) {
            val id = String(wav, pos, 4, Charsets.US_ASCII)
            val size = bb.getInt(pos + 4)
            if (id == "data") {
                val n = minOf(size, wav.size - pos - 8) / 2
                return ShortArray(n) { bb.getShort(pos + 8 + it * 2) }
            }
            pos += 8 + size
        }
        error("no data chunk in WAV")
    }
}
