package com.travel2chicago.gemmapipeline.tts

import android.content.Context
import android.util.Log
import org.json.JSONObject

private const val TAG = "Tokenizer"

/**
 * Maps a single string of IPA characters (output of [Phonemizer]) to the
 * `LongArray` of token IDs Kokoro's ONNX model expects.
 *
 * **One token per CHARACTER**, not per phoneme group — this matches the
 * Kokoro reference tokeniser
 * (see `thewh1teagle/kokoro-onnx/tokenizer.py` and the puff-dayo Android
 * port). The vocab in `kokoro_config.json` (178 entries) is the canonical
 * mapping the model was trained against; do not reorder.
 *
 * **No BOS/EOS at this layer.** The Python reference wraps the IDs as
 * `[0, ...tokens, 0]` right before `session.run`. We replicate that wrap in
 * [KokoroOnnxEngine.synthesize] rather than here so this class stays a pure
 * "phoneme → ID" function with no implicit state.
 *
 * Unknown characters map to [padId] and are counted in [unkHits] so a
 * missing vocab entry is visible during QA.
 */
class Tokenizer(
    private val charToId: Map<String, Int>,
    val padId: Int,
    val maxLen: Int,
) {
    @Volatile var unkHits: Long = 0
        private set

    /**
     * Returns the model-ready token sequence (without the 0/PAD wrapping —
     * see [KokoroOnnxEngine.synthesize] for that). Input longer than
     * [maxLen] − 2 is truncated so the engine's wrap still fits inside the
     * model's max_position window.
     */
    fun tokenize(phonemes: String): LongArray {
        if (phonemes.isEmpty()) return LongArray(0)
        val budget = (maxLen - 2).coerceAtLeast(0)
        val limit = minOf(phonemes.length, budget)
        val out = LongArray(limit)
        var truncated = 0
        for (i in 0 until limit) {
            val ch = phonemes[i].toString()
            val id = charToId[ch]
            if (id == null) {
                unkHits += 1
                out[i] = padId.toLong()
            } else {
                out[i] = id.toLong()
            }
        }
        if (phonemes.length > budget) {
            truncated = phonemes.length - budget
            Log.w(TAG, "tokenize: input was ${phonemes.length} chars; truncated $truncated to fit maxLen=$maxLen")
        }
        return out
    }

    companion object {
        fun loadFromAsset(context: Context, assetName: String): Tokenizer {
            val raw = context.assets.open(assetName).bufferedReader(Charsets.UTF_8)
                .use { it.readText() }
            val json = JSONObject(raw)
            val vocabJson = json.getJSONObject("vocab")
            val vocab = HashMap<String, Int>(vocabJson.length() * 2)
            val it = vocabJson.keys()
            while (it.hasNext()) {
                val key = it.next()
                vocab[key] = vocabJson.getInt(key)
            }
            val pad = json.optInt("pad_token_id", 0)
            val maxLen = json.optInt("max_position", 512)
            Log.i(TAG, "Loaded tokenizer: ${vocab.size} chars, max=$maxLen, pad=$pad")
            return Tokenizer(charToId = vocab, padId = pad, maxLen = maxLen)
        }
    }
}
