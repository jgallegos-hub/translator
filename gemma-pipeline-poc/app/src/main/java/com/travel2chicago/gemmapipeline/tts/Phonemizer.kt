package com.travel2chicago.gemmapipeline.tts

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader

private const val TAG = "Phonemizer"

/** Strategy interface so the engine can swap phonemization implementations. */
interface Phonemizer {
    /** Convert plain English text to an ordered list of phoneme tokens. */
    fun phonemize(text: String): List<String>
}

/**
 * Dictionary-based English phonemizer. Loads a `word PHONEME PHONEME ...`
 * text file (CMU-style) from the APK assets, then looks up words at runtime.
 * Unknown words fall through a tiny letter-to-sound rule set rather than
 * crashing — quality is best-effort for OOV; the dictionary should cover the
 * common-case vocabulary.
 *
 * The reference dictionary for v1 is the one shipped with
 * `github.com/puff-dayo/Kokoro-82M-Android`. The bundled stub in this POC
 * contains only a handful of test words — replace it with the full dictionary
 * before relying on real translations.
 */
class DictionaryPhonemizer(
    private val dict: Map<String, List<String>>,
) : Phonemizer {

    @Volatile var oovHits: Long = 0
        private set

    override fun phonemize(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val normalized = normalize(text)
        val words = normalized.split(Regex("\\s+")).filter { it.isNotEmpty() }
        return words.flatMap { word -> lookup(word) }
    }

    private fun lookup(word: String): List<String> {
        // First strip leading/trailing punctuation, but preserve internal
        // apostrophes ("don't") — those usually have dictionary entries.
        val clean = word.trim(*PUNCT)
        if (clean.isEmpty()) return emptyList()
        dict[clean]?.let { return it }
        // Try a couple of common normalisations before falling through.
        val noApostrophe = clean.replace("'", "")
        dict[noApostrophe]?.let { return it }
        oovHits += 1
        Log.w(TAG, "OOV: '$clean' — applying LTS fallback")
        return ltsFallback(clean)
    }

    /**
     * Minimal letter-to-sound fallback for words missing from the dictionary.
     * NOT a serious LTS implementation — its only job is to keep the pipeline
     * alive so a missing dictionary entry doesn't kill the whole utterance.
     * Quality of OOV words will be poor until the full dictionary is present.
     */
    private fun ltsFallback(word: String): List<String> {
        if (word.isEmpty()) return emptyList()
        // Emit one neutral schwa per character. Crude on purpose — the caller
        // gets SOMETHING that the tokenizer can hand to Kokoro without
        // crashing, and the WARN log makes the dictionary gap visible.
        return List(word.length) { "ə" }
    }

    companion object {
        private val PUNCT = charArrayOf(
            '.', ',', '!', '?', ';', ':', '"', '(', ')', '[', ']', '{', '}',
            '“', '”', '‘', '’', // smart quotes
        )

        /** Normalize unicode quirks before dictionary lookup. */
        fun normalize(text: String): String {
            return text.lowercase()
                .replace('’', '\'')   // right single quote → apostrophe
                .replace('‘', '\'')   // left single quote  → apostrophe
                .replace('“', '"')    // left double quote
                .replace('”', '"')    // right double quote
                .replace('—', '-')    // em dash
                .replace('–', '-')    // en dash
                .trim()
        }

        /**
         * Load the dictionary from an APK asset. Format per line:
         *
         *   word PHONEME1 PHONEME2 ...
         *
         * Blank lines and lines beginning with '#' are ignored. Words are
         * stored lowercased — lookups are case-insensitive.
         */
        fun loadFromAssets(context: Context, assetName: String): DictionaryPhonemizer {
            val map = HashMap<String, List<String>>(64 * 1024)
            context.assets.open(assetName).use { input ->
                BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { reader ->
                    reader.lineSequence().forEach { rawLine ->
                        val line = rawLine.trim()
                        if (line.isEmpty() || line.startsWith("#")) return@forEach
                        val parts = line.split(Regex("\\s+"))
                        if (parts.size < 2) return@forEach
                        val word = parts[0].lowercase()
                        val phonemes = parts.drop(1)
                        map[word] = phonemes
                    }
                }
            }
            Log.i(TAG, "Loaded ${map.size} dictionary entries from $assetName")
            return DictionaryPhonemizer(map)
        }
    }
}
