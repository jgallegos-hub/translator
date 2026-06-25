package com.travel2chicago.gemmapipeline.tts

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader

private const val TAG = "Phonemizer"

/**
 * Strategy interface for the text → IPA conversion step. Returns a single
 * string of IPA characters (not a token list); the downstream tokeniser
 * iterates char-by-char to map each character to a vocab ID — this matches
 * the Kokoro reference tokeniser (puff-dayo/Kokoro-82M-Android Tokenizer.kt).
 */
interface Phonemizer {
    fun phonemize(text: String): String
}

/**
 * Dictionary-based English phonemizer. Loads a CMU-style word→IPA dictionary
 * from APK assets (default `cmudict_ipa.dict` from puff-dayo/Kokoro-82M-Android,
 * ~125 000 entries, ~3 MB) and looks up each word at runtime.
 *
 * File format (tab-separated, one entry per line):
 *
 *     WORD\tIPAPHONEMES
 *
 * Words are uppercase. Lines starting with `;;;` are comments. Empty lines
 * are skipped. Entries with multiple pronunciations (the upstream CMU dict
 * uses `WORD(2)`, `WORD(3)` parenthesised variants) are kept; the *first*
 * pronunciation seen for a given canonical form wins — alternate forms are
 * dropped at load time.
 *
 * OOV words fall through to a tiny letter→schwa fallback. This is on purpose
 * a best-effort stub: real fallback would need a language-model-driven LTS
 * engine (espeak-ng or `ipa_transcribers` in puff-dayo). The schwa fallback
 * keeps the pipeline alive and logs WARN so OOV gaps in the dict are visible.
 */
class DictionaryPhonemizer(
    private val dict: Map<String, String>,
) : Phonemizer {

    @Volatile var oovHits: Long = 0
        private set

    override fun phonemize(text: String): String {
        if (text.isBlank()) return ""
        val normalized = normalize(text)
        // Tokenise on whitespace; keep a single space between words so the
        // tokeniser can emit the vocab id for ' ' between phoneme groups.
        val words = normalized.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val out = StringBuilder(words.size * 6)
        for ((i, word) in words.withIndex()) {
            if (i > 0) out.append(' ')
            out.append(lookup(word))
        }
        return out.toString()
    }

    private fun lookup(word: String): String {
        val clean = word.trim(*PUNCT)
        if (clean.isEmpty()) return ""
        // Dict words are uppercase (CMU convention).
        dict[clean.uppercase()]?.let { return it }
        // Try once more without internal apostrophes.
        val noApostrophe = clean.replace("'", "")
        dict[noApostrophe.uppercase()]?.let { return it }
        oovHits += 1
        Log.w(TAG, "OOV: '$clean' — applying schwa fallback")
        return ltsFallback(clean)
    }

    /**
     * Crude OOV escape hatch — emit one schwa per character. NOT a real LTS
     * implementation; its job is to keep the pipeline alive so a missing
     * dictionary entry doesn't kill the whole utterance. The WARN log above
     * makes the gap visible for QA so the dict can be extended.
     */
    private fun ltsFallback(word: String): String = "ə".repeat(word.length)

    companion object {
        private val PUNCT = charArrayOf(
            '.', ',', '!', '?', ';', ':', '"', '(', ')', '[', ']', '{', '}',
            '“', '”', '‘', '’',
        )

        fun normalize(text: String): String {
            return text
                .replace('’', '\'')
                .replace('‘', '\'')
                .replace('“', '"')
                .replace('”', '"')
                .replace('—', '-')
                .replace('–', '-')
                .trim()
        }

        /**
         * Load the CMU IPA dictionary from an APK asset.
         *
         * The puff-dayo file has ~125 000 lines and is ~3 MB. We keep only
         * the first pronunciation for each canonical word (CMU's `WORD(2)`,
         * `WORD(3)` variants are dropped) to keep the in-memory map size
         * predictable and the lookup deterministic.
         */
        fun loadFromAssets(context: Context, assetName: String): DictionaryPhonemizer {
            val map = HashMap<String, String>(128 * 1024)
            var lines = 0
            var dropped = 0
            context.assets.open(assetName).use { input ->
                BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { reader ->
                    reader.lineSequence().forEach { rawLine ->
                        lines += 1
                        val line = rawLine.trim()
                        if (line.isEmpty() || line.startsWith(";;;")) return@forEach
                        val tab = line.indexOf('\t')
                        if (tab <= 0 || tab >= line.length - 1) { dropped += 1; return@forEach }
                        val rawWord = line.substring(0, tab)
                        val phonemes = line.substring(tab + 1).trim()
                        if (phonemes.isEmpty()) { dropped += 1; return@forEach }
                        // Strip the `(2)`, `(3)` variant suffix from the word — keep first only.
                        val canonical = rawWord.substringBefore('(').uppercase()
                        map.putIfAbsent(canonical, phonemes)
                    }
                }
            }
            Log.i(TAG, "Loaded ${map.size} dictionary entries from $assetName ($lines lines, $dropped dropped)")
            return DictionaryPhonemizer(map)
        }
    }
}
