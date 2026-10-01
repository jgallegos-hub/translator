package com.travel2chicago.gemmapipeline.ast

/**
 * Capa 2 anti-eco — rolling history of sentences the TTS has spoken.
 *
 * With full-duplex ON and external hardware (USB mic + BT speaker) the mic
 * re-captures Kokoro's output. If Gemma "translates" that English echo back
 * into English, the text it produces is a near-copy of something we just
 * spoke. [AstChunkRouter] asks this history for a match before emitting a
 * `TranslationReady`; a hit means the chunk was our own echo and gets
 * dropped instead of being spoken again (which is what closes the feedback
 * loop).
 *
 * Owned by the ViewModel (not the router) so the history survives router
 * restarts triggered by UI toggles. The ViewModel calls [record] on every
 * `TtsAudioReady` — i.e. when a sentence actually reaches the speaker, for
 * Kokoro one-shot, Kokoro streaming and Android Fast TTS alike.
 *
 * Similarity is token-based on normalised text (lower-case, letters/digits
 * only): `max(Jaccard, containment)` where containment = |A∩B| / |A| with A
 * the candidate. Containment catches the common echo case where the chunk
 * boundary cut the spoken sentence in half; it only applies when the
 * candidate has at least [MIN_CONTAINMENT_TOKENS] tokens so that a short
 * legitimate reply ("Yes.") is not swallowed by any spoken sentence that
 * happens to contain the same word.
 *
 * Thread-safe: [record] runs on the bus collector, [findMatch] on the
 * router's consumer thread.
 *
 * @param clockMs monotonic millisecond clock, injectable for tests.
 */
class EchoTextHistory(
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val maxEntries: Int = 32,
) {
    data class Entry(val text: String, val tokens: Set<String>, val timestampMs: Long)
    data class Match(val entry: Entry, val similarity: Double)

    private val lock = Any()
    private val entries = ArrayDeque<Entry>()

    val size: Int get() = synchronized(lock) { entries.size }

    /**
     * Records a sentence the TTS just spoke. Re-recording the same text
     * (Kokoro streaming emits one `TtsAudioReady` per sub-sentence PCM with
     * the same source text) refreshes its timestamp instead of duplicating.
     */
    fun record(text: String) {
        val tokens = tokenize(text)
        if (tokens.isEmpty()) return
        val now = clockMs()
        synchronized(lock) {
            entries.removeAll { it.tokens == tokens }
            entries.addLast(Entry(text, tokens, now))
            while (entries.size > maxEntries) entries.removeFirst()
        }
    }

    /**
     * Returns the best-matching entry spoken within the last [windowMs] whose
     * similarity to [text] is `>= threshold`, or `null`. Expired entries are
     * pruned as a side effect.
     */
    fun findMatch(text: String, threshold: Double, windowMs: Long): Match? {
        val candidate = tokenize(text)
        if (candidate.isEmpty()) return null
        val cutoff = clockMs() - windowMs
        synchronized(lock) {
            while (entries.isNotEmpty() && entries.first().timestampMs < cutoff) entries.removeFirst()
            var best: Match? = null
            for (e in entries) {
                if (e.timestampMs < cutoff) continue
                val sim = similarity(candidate, e.tokens)
                if (sim >= threshold && (best == null || sim > best.similarity)) best = Match(e, sim)
            }
            return best
        }
    }

    fun clear() = synchronized(lock) { entries.clear() }

    companion object {
        const val MIN_CONTAINMENT_TOKENS = 4

        fun tokenize(text: String): Set<String> =
            text.lowercase()
                .split(Regex("[^\\p{L}\\p{N}']+"))
                .map { it.trim('\'') }
                .filter { it.isNotEmpty() }
                .toSet()

        fun similarity(candidate: Set<String>, spoken: Set<String>): Double {
            if (candidate.isEmpty() || spoken.isEmpty()) return 0.0
            val inter = candidate.count { it in spoken }
            val union = candidate.size + spoken.size - inter
            val jaccard = inter.toDouble() / union
            val containment =
                if (candidate.size >= MIN_CONTAINMENT_TOKENS) inter.toDouble() / candidate.size else 0.0
            return maxOf(jaccard, containment)
        }
    }
}
