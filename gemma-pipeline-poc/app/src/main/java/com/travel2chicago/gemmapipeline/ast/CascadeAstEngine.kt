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
    /** Was our own TTS audible during the last `chunkMs` (the chunk being transcribed)? */
    private val isTtsActive: (chunkMs: Long) -> Boolean = { false },
    /** Gate over our own TTS while AEC3 is still learning the speaker (see [isEchoConverging]). */
    private val convergingLineRmsThreshold: Double = strictLineRmsThreshold,
    /** Device r25: echo lines reached RMS 4020 and 3195 in the first seconds of speech, then
     *  stayed <= 2453 once the canceller had heard ~6 s of it. */
    private val isEchoConverging: () -> Boolean = { false },
    /** Diagnostics: saves each chunk with its outcome (see [ChunkDump]). */
    private val chunkDump: ChunkDump? = null,
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
        val ttsActive = isTtsActive(samples.size * 1000L / 16_000)
        val gate = when {
            !ttsActive -> lineRmsThreshold
            isEchoConverging() -> convergingLineRmsThreshold
            else -> strictLineRmsThreshold
        }
        val kept = lines.filter { it.rms >= gate }
        for (l in lines - kept.toSet()) {
            Log.i(TAG, "CASCADE drop echo-residue line rms=${"%.0f".format(l.rms)} gate=${"%.0f".format(gate)} ttsActive=$ttsActive text='${l.text}'")
        }
        val spanish = collapseDuplicatedSentence(
            kept.joinToString(" ") { it.text }.trim(),
            samples.size * 1000L / 16_000,
        )
        val chunkMs = samples.size * 1000L / 16_000
        val lineInfo = lines.map { "${"%.0f".format(it.rms)}:${it.text}" }
        if (spanish.isEmpty()) {
            Log.i(TAG, "CASCADE asrMs=$asrMs empty transcript (lines=${lines.size}) — nothing to translate")
            chunkDump?.record(wavBytes, chunkMs, ttsActive, gate, lineInfo, "", "",
                if (lines.isEmpty()) "no_speech" else "below_gate")
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
            "CASCADE mt=${(mt as? com.travel2chicago.gemmapipeline.mt.FallbackTranslator)?.lastUsed ?: mt.name} words=$words asrMs=$asrMs mtFirstMs=$mtFirstMs totalMs=$totalMs " +
                "lineRms=${kept.joinToString(",") { "%.0f".format(it.rms) }} es='$spanish' en='$english'",
        )
        chunkDump?.record(wavBytes, chunkMs, ttsActive, gate, lineInfo, spanish, english,
            if (english.isBlank()) "silenced" else "spoken")
        return AstResult(english, totalMs, backendUsed)
    }

    companion object {
        private val TERMINATORS = setOf('.', '!', '?')
        private val WHITESPACE = Regex("""\s+""")
        private val SENTENCE = Regex("""[^.!?]+[.!?]*""")
        private const val DUPLICATE_MAX_CHUNK_MS = 2_500L
        private const val DUPLICATE_MIN_WORDS = 3

        /**
         * Moonshine sometimes doubles a short chunk ("Hola, buenos días. Hola, buenos días." in a
         * 1.8 s chunk, device 2026-11-05), so it was translated and spoken twice. Two identical
         * sentences of 3+ words in under 2.5 s become one; anything else is left untouched.
         */
        fun collapseDuplicatedSentence(text: String, chunkMs: Long): String {
            if (chunkMs >= DUPLICATE_MAX_CHUNK_MS) return text
            val parts = SENTENCE.findAll(text).map { it.value.trim() }.filter { it.isNotEmpty() }.toList()
            if (parts.size != 2) return text
            val key = { s: String -> s.lowercase().filter { it.isLetterOrDigit() || it.isWhitespace() }.trim() }
            val same = key(parts[0]) == key(parts[1])
            return if (same && parts[0].split(WHITESPACE).size >= DUPLICATE_MIN_WORDS) parts[0] else text
        }

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
