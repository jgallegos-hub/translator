package com.travel2chicago.gemmapipeline.tts

import android.content.Context
import android.util.Log
import org.json.JSONObject

private const val TAG = "Tokenizer"

/**
 * Maps a phoneme sequence to the LongArray of token IDs that the Kokoro ONNX
 * model expects as input. The phoneme→ID vocabulary, the special-token IDs,
 * and the maximum sequence length all come from the model's `config.json`
 * (bundled in assets).
 *
 * Format expected from `kokoro_config.json`:
 *
 * ```json
 * {
 *   "vocab": { "ə": 1, "h": 2, ... },
 *   "max_position": 512,
 *   "bos_token_id": 0,
 *   "eos_token_id": 0,
 *   "unk_token_id": 0
 * }
 * ```
 *
 * Notes:
 *   - In the reference Kokoro models BOS and EOS are commonly the same token
 *     (id=0). We accept distinct values via the config if a future variant
 *     splits them.
 *   - Unknown phonemes are mapped to [unkId] rather than crashing; loggers
 *     warn so a missing vocab entry is visible during QA.
 */
class Tokenizer(
    private val phonemeToId: Map<String, Int>,
    val bosId: Int,
    val eosId: Int,
    val unkId: Int,
    val maxLen: Int,
) {
    @Volatile var unkHits: Long = 0
        private set

    fun tokenize(phonemes: List<String>): LongArray {
        val budget = maxLen - 2  // reserve for BOS + EOS
        val ids = ArrayList<Long>(minOf(phonemes.size, budget) + 2)
        ids += bosId.toLong()
        var truncated = 0
        for (p in phonemes) {
            if (ids.size - 1 >= budget) { truncated += 1; continue }
            val id = phonemeToId[p]
            if (id == null) {
                unkHits += 1
                ids += unkId.toLong()
            } else {
                ids += id.toLong()
            }
        }
        ids += eosId.toLong()
        if (truncated > 0) {
            Log.w(TAG, "tokenize: input was ${phonemes.size} phonemes; truncated $truncated to fit maxLen=$maxLen")
        }
        return ids.toLongArray()
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
            val bos = json.optInt("bos_token_id", 0)
            val eos = json.optInt("eos_token_id", 0)
            val unk = json.optInt("unk_token_id", 0)
            val maxLen = json.optInt("max_position", 512)
            Log.i(TAG, "Loaded tokenizer: ${vocab.size} phonemes, max=$maxLen, bos=$bos eos=$eos unk=$unk")
            return Tokenizer(
                phonemeToId = vocab,
                bosId = bos,
                eosId = eos,
                unkId = unk,
                maxLen = maxLen,
            )
        }
    }
}
