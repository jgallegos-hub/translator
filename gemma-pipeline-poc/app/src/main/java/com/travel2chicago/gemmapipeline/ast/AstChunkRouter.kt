package com.travel2chicago.gemmapipeline.ast

import android.util.Log
import com.travel2chicago.gemmapipeline.audio.AudioEvent
import com.travel2chicago.gemmapipeline.audio.AudioEventBus
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

private const val TAG = "AstChunkRouter"

/**
 * Bridges [AudioChunker]'s output to [GemmaAstEngine].
 *
 * Topology:
 *   - PRODUCER (Dispatchers.Default): subscribes to `bus.events` and filters
 *     [AudioEvent.ChunkReady]. Submits each chunk to a bounded internal
 *     channel with DROP_OLDEST semantics. Because Gemma's inference is much
 *     slower (~2 s) than the chunker can emit (every ~3–6 s of speech, but
 *     possibly burstier), the queue exists to absorb short bursts while
 *     preserving the most recent context — older chunks get dropped first.
 *
 *   - CONSUMER (ioDispatcher, default Dispatchers.IO, single-threaded by
 *     design): pulls one chunk at a time, builds a WAV via [WavBuilder],
 *     calls [GemmaAstEngine.translate], and publishes either
 *     [AudioEvent.TranslationReady] or [AudioEvent.AstError] back to the bus.
 *
 * Single-threaded consumption is intentional: one `Conversation` runs one
 * inference at a time. Parallelism would require a pool of engines, which
 * is out of scope for the POC.
 *
 * @param sampleRate samples-per-second of the chunks coming from the chunker.
 *   The chunker always produces 16 kHz mono — exposed as a constructor arg
 *   only so tests can construct chunks at any rate without ceremony.
 */
class AstChunkRouter(
    private val bus: AudioEventBus,
    private val engine: GemmaAstEngine,
    private val config: AstConfig,
    private val sampleRate: Int = 16_000,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val channel: Channel<AudioEvent.ChunkReady> =
        Channel(capacity = config.queueCapacity)

    @Volatile private var producerJob: Job? = null
    @Volatile private var consumerJob: Job? = null

    // Metrics — atomic so the UI thread can read them without locking.
    private val queueDepth = AtomicInteger(0)
    private val translatedCount = AtomicLong(0)
    private val droppedCount = AtomicLong(0)
    private val totalLatencyMs = AtomicLong(0)
    private val errorCount = AtomicLong(0)
    private val lowEnergyDiscardCount = AtomicLong(0)
    private val metaTextDiscardCount = AtomicLong(0)
    /**
     * Count of Gemma replies where the router had to fall back to using the
     * full text because the `English: ` marker required by the official AST
     * prompt was missing. Non-zero means the model is not honouring the
     * requested output format — worth logging if it climbs over time.
     */
    private val englishMarkerMissingCount = AtomicLong(0)
    /**
     * Wall-clock ms from `ChunkReady.timestampNs` to Gemma's first output —
     * the first token in the streaming path, or the return of `translate()`
     * in the one-shot path. Last-value semantics (updated per chunk). Zero
     * means "no chunk processed since start / restart".
     */
    private val firstTokenLatencyMsAtomic = AtomicLong(0)

    val isRunning: Boolean
        get() = producerJob?.isActive == true && consumerJob?.isActive == true

    val queueSize: Int get() = queueDepth.get()
    val totalTranslated: Long get() = translatedCount.get()
    val totalDropped: Long get() = droppedCount.get()
    val totalErrors: Long get() = errorCount.get()
    /** Chunks skipped BEFORE Gemma because their RMS was under [AstConfig.rmsThreshold]. */
    val totalDiscardedLowEnergy: Long get() = lowEnergyDiscardCount.get()
    /** Translations dropped AFTER Gemma because the reply matched a meta-text pattern. */
    val totalDiscardedMeta: Long get() = metaTextDiscardCount.get()
    /** Times the router fell back to full-reply because the `English: ` marker
     *  was missing (only meaningful when [AstConfig.useOfficialAstPrompt] is on). */
    val totalEnglishMarkerMissing: Long get() = englishMarkerMissingCount.get()
    /** Latency from `ChunkReady.timestampNs` to Gemma's first output for the
     *  MOST RECENT processed chunk. Meaningful across both streaming and
     *  one-shot paths — in one-shot mode it's ~= totalGemmaLatencyMs of the
     *  chunk; in streaming it's the time to the first token. */
    val firstTokenLatencyMs: Long get() = firstTokenLatencyMsAtomic.get()
    val averageLatencyMs: Double get() {
        val n = translatedCount.get()
        return if (n > 0) totalLatencyMs.get().toDouble() / n else 0.0
    }

    fun start(scope: CoroutineScope) {
        if (isRunning) {
            Log.w(TAG, "start: already running, ignoring")
            return
        }
        Log.i(TAG, "Starting router (queueCapacity=${config.queueCapacity}, backend=${engine.backendUsed})")
        bus.emit(AudioEvent.EngineStatus(
            "AST router subscribed (backend=${engine.backendUsed}, queueCap=${config.queueCapacity})"))

        producerJob = scope.launch(Dispatchers.Default) {
            bus.events
                .filterIsInstance<AudioEvent.ChunkReady>()
                .collect { chunk -> submitChunk(chunk) }
        }

        consumerJob = scope.launch(ioDispatcher) {
            // consumeEach handles cancellation + cleanup correctly.
            channel.consumeEach { chunk ->
                queueDepth.decrementAndGet()
                processChunk(chunk)
            }
        }
    }

    /**
     * Hard-cancel. Both producer and consumer jobs are cancelled immediately;
     * any chunk queued or in-flight is lost. Safe to call from non-suspend
     * contexts like `ViewModel.onCleared()` or tests that just need to tear
     * down quickly.
     *
     * Use [stopGracefully] from a coroutine when you want pending chunks to
     * finish translating before shutdown (e.g. user-initiated Stop button).
     */
    fun cancel() {
        producerJob?.cancel()
        consumerJob?.cancel()
        producerJob = null
        consumerJob = null
        // Close the channel so any items still buffered are released and
        // the consumer's consumeEach exits cleanly. The router is one-shot:
        // the caller (ViewModel) constructs a fresh router on each start.
        channel.close()
        Log.i(TAG, "Router cancelled. translated=$totalTranslated dropped=$totalDropped errors=$totalErrors")
    }

    /**
     * Graceful shutdown. Stops accepting new chunks, then waits for the
     * consumer to drain everything already in the channel before returning.
     *
     *  1. Cancel the producer so no further `ChunkReady` events get enqueued.
     *  2. Close the channel — `consumeEach` exits naturally once the buffer
     *     is empty.
     *  3. Wait up to [drainTimeoutMs] for the consumer job to complete.
     *  4. If the timeout fires (Gemma stuck on a single inference), fall
     *     back to hard cancel so the call still returns deterministically.
     *
     * @param drainTimeoutMs maximum time to wait for queued + in-flight
     *   chunks to finish. Default 8000 ms ≈ queueCapacity(4) × ~2 s/inference.
     * @return number of translations that completed during the drain window
     *   (zero if the channel was already empty when called, or if the
     *   timeout fired before any chunk finished).
     */
    suspend fun stopGracefully(drainTimeoutMs: Long = 8_000L): Int {
        producerJob?.cancel()
        producerJob = null

        // Closing the channel signals "no more sends". consumeEach will
        // exit cleanly once the in-memory buffer is drained.
        channel.close()

        val before = translatedCount.get()
        val consumer = consumerJob
        val drained = withTimeoutOrNull(drainTimeoutMs) {
            consumer?.join()
            true
        }
        if (drained == null) {
            Log.w(TAG, "stopGracefully: drain timed out after ${drainTimeoutMs}ms — hard-cancelling consumer")
            consumer?.cancel()
        }
        consumerJob = null

        val completedDuringDrain = (translatedCount.get() - before).toInt()
        Log.i(
            TAG,
            "Router stopped gracefully. drained=$completedDuringDrain timedOut=${drained == null} " +
                "totalTranslated=$totalTranslated totalDropped=$totalDropped totalErrors=$totalErrors",
        )
        return completedDuringDrain
    }

    /**
     * Single-producer DROP_OLDEST.
     *
     * Counter discipline:
     *   - Every successful [Channel.trySend] is paired with `queueDepth++`.
     *   - Every successful [Channel.tryReceive] is paired with `queueDepth--`
     *     — both the consumer's [consumeEach] (which we decrement in [start])
     *     and our manual pop here.
     *
     * The router is the only writer (sequential collect on Dispatchers.Default),
     * but the consumer can drain in parallel — so a pop can fail if the
     * consumer just emptied the channel. We retry once; if the retry also
     * fails (pathological — channel filled up again instantly) we count the
     * NEW chunk as the drop.
     */
    private fun submitChunk(chunk: AudioEvent.ChunkReady) {
        val sent = channel.trySend(chunk)
        if (sent.isSuccess) {
            queueDepth.incrementAndGet()
            return
        }
        // Channel reported full — drop the oldest manually.
        val popped = channel.tryReceive()
        if (popped.isSuccess) {
            queueDepth.decrementAndGet()
            droppedCount.incrementAndGet()
        }
        // Whether or not the pop succeeded, try the new chunk one more time.
        val retry = channel.trySend(chunk)
        if (retry.isSuccess) {
            queueDepth.incrementAndGet()
            if (popped.isSuccess) {
                bus.emit(AudioEvent.EngineStatus(
                    "AST queue full, dropped oldest (totalDropped=${droppedCount.get()})"))
            }
        } else {
            // Single producer guarantees this shouldn't happen unless the
            // consumer is contending hard. Count the new chunk as dropped.
            droppedCount.incrementAndGet()
            Log.w(TAG, "submitChunk: trySend failed twice — dropping new chunk")
        }
    }

    private suspend fun processChunk(chunk: AudioEvent.ChunkReady) {
        // Pre-filter: skip near-silent chunks BEFORE the expensive Gemma call.
        // See AstConfig.rmsThreshold for rationale.
        if (config.rmsThreshold > 0.0) {
            val rms = computeRms(chunk.samples)
            if (rms < config.rmsThreshold) {
                val n = lowEnergyDiscardCount.incrementAndGet()
                Log.i(
                    TAG,
                    "Chunk discarded: low RMS (${"%.1f".format(rms)} < ${config.rmsThreshold}) " +
                        "durMs=${chunk.durationMs} peak=${chunk.peak} totalLowEnergy=$n",
                )
                bus.emit(AudioEvent.EngineStatus(
                    "AST skipped low-energy chunk (rms=${"%.0f".format(rms)}, total=$n)"))
                return
            }
        }

        val wav = try {
            WavBuilder.build(chunk.samples, sampleRate = sampleRate, channels = 1)
        } catch (t: Throwable) {
            Log.e(TAG, "WAV build failed for chunk ts=${chunk.timestampNs}", t)
            emitError("WAV build failed: ${t.message}", chunk)
            return
        }

        // Fase 6 Stage A — branch on the streaming flag. Off by default; the
        // legacy path is preserved byte-for-byte so a device regression is a
        // single-toggle revert.
        if (config.streamingEnabled) {
            processChunkStreaming(chunk, wav)
        } else {
            processChunkOneShot(chunk, wav)
        }
    }

    private suspend fun processChunkOneShot(chunk: AudioEvent.ChunkReady, wav: ByteArray) {
        val result = try {
            engine.translate(wav, config.activePrompt, config.audioAfterText)
        } catch (t: Throwable) {
            // Don't propagate — one bad chunk shouldn't kill the consumer.
            // The next chunk gets a fresh attempt on the same engine.
            errorCount.incrementAndGet()
            Log.e(TAG, "Gemma translate() failed", t)
            emitError("Gemma translate failed: ${t.javaClass.simpleName}: ${t.message}", chunk)
            return
        }

        // One-shot: "first token" == "full reply". Record now so the UI can
        // compare against the streaming path apples-to-apples.
        firstTokenLatencyMsAtomic.set((System.nanoTime() - chunk.timestampNs) / 1_000_000)

        translatedCount.incrementAndGet()
        totalLatencyMs.addAndGet(result.latencyMs)

        // Official-prompt path: strip everything up to and including the
        // `English: ` marker so the Spanish transcription is not spoken by
        // Kokoro. If the marker is missing (Gemma ignored the format),
        // extractEnglishTranslation returns the full reply as-is and bumps
        // englishMarkerMissingCount — meta-text filter still applies.
        val translated = extractEnglishTranslation(result.text)

        // Post-filter: Gemma sometimes replies with meta-text on near-silent
        // chunks that slipped past the RMS gate. Kill the reply BEFORE it
        // reaches TtsRouter so Kokoro never speaks "please provide the audio".
        val matched = matchMetaPattern(translated)
        if (matched != null) {
            val n = metaTextDiscardCount.incrementAndGet()
            Log.i(
                TAG,
                "Translation discarded: meta-text detected ('$matched') text='${translated.take(80)}' " +
                    "totalMeta=$n",
            )
            bus.emit(AudioEvent.EngineStatus(
                "AST dropped meta-text reply ('$matched', total=$n)"))
            return
        }

        bus.emit(
            AudioEvent.TranslationReady(
                text = translated,
                sourceChunkTimestampNs = chunk.timestampNs,
                sourceDurationMs = chunk.durationMs,
                sourcePeak = chunk.peak,
                latencyMs = result.latencyMs,
                timestampNs = System.nanoTime(),
            ),
        )
    }

    /**
     * Fase 6 Stage A — token-streaming path.
     *
     * Router owns the sentence boundary detection (in-line — not a shared
     * util) and the meta-text guard. Gemma's tokens are forwarded as they
     * arrive; each time a terminator (`.`, `!`, `?`) closes a sentence, the
     * router runs the meta-text filter on the ENTIRE accumulated buffer up
     * to and including that terminator. Rationale: preambles like "The
     * translation of the Spanish audio is: Hello." span multiple token
     * emissions — a per-sentence-only check would miss the "translation of"
     * fragment.
     *
     * Emission strategy: the newly-closed sentence is HELD as `pending`
     * until either (a) the next terminator fires — pending gets emitted
     * with `isFinal=false` and the new one takes its place — or (b) the
     * Flow completes — pending is emitted with `isFinal=true`. Trailing
     * non-terminated text at end-of-flow is emitted as the final event.
     * This gives downstream consumers a reliable "utterance ended" signal
     * (Stage B's `TtsAudioPlayer.endUtterance` will use it) without a
     * separate closer event on the bus.
     *
     * On meta-text hit anywhere in the accumulated buffer, we set a
     * `dropRestOfChunk` flag: no further sentences from this chunk are
     * emitted, and the currently-pending sentence (which pre-dated the
     * preamble hit) is also discarded — it may itself have been part of
     * the preamble. We keep consuming the Flow so LiteRT-LM's decoder
     * drains cleanly.
     */
    private suspend fun processChunkStreaming(chunk: AudioEvent.ChunkReady, wav: ByteArray) {
        val sb = StringBuilder()
        var lastCutOffset = 0
        var sentenceCounter = 0
        var dropRestOfChunk = false
        var pendingText: String? = null
        var pendingIndex = -1
        // Official-prompt path: everything before `English: ` is the Spanish
        // transcription, which we must NOT feed to Kokoro. The gate stays
        // shut until the marker appears in the accumulated buffer; once open,
        // [contentStartOffset] is set to the character AFTER the marker and
        // both sentence-scanning and meta-text checks operate from there.
        // Legacy prompt path skips the gate entirely (contentStartOffset stays
        // at 0, englishGateOpen starts true) — every token is treated as
        // English translation output from the first delta.
        val expectMarker = config.useOfficialAstPrompt
        var englishGateOpen = !expectMarker
        var contentStartOffset = 0

        suspend fun emitPendingAsNonFinal() {
            val p = pendingText ?: return
            bus.emit(
                AudioEvent.TranslationReady(
                    text = p,
                    sourceChunkTimestampNs = chunk.timestampNs,
                    sourceDurationMs = chunk.durationMs,
                    sourcePeak = chunk.peak,
                    // Per-sentence latency isn't meaningful pre-final; the
                    // aggregate lands on the isFinal=true event below.
                    latencyMs = 0L,
                    timestampNs = System.nanoTime(),
                    sentenceIndex = pendingIndex,
                    isFinal = false,
                ),
            )
            pendingText = null
            pendingIndex = -1
        }

        val result = try {
            engine.translateStreaming(wav, config.activePrompt, config.audioAfterText) { delta ->
                if (dropRestOfChunk) return@translateStreaming
                // Record first-token latency exactly once per chunk — on the
                // FIRST non-empty delta observed. `sb.isEmpty()` is the
                // cheapest way to detect that boundary; `delta` itself is
                // guaranteed non-empty by LiteRtGemmaAstEngine.
                if (sb.isEmpty()) {
                    firstTokenLatencyMsAtomic.set(
                        (System.nanoTime() - chunk.timestampNs) / 1_000_000,
                    )
                }
                sb.append(delta)

                // Official-prompt gate: hold off sentence scanning until the
                // `English:` marker arrives. Everything before is the Spanish
                // transcription; passing it downstream would make Kokoro
                // speak Spanish text with an English voice.
                if (!englishGateOpen) {
                    val markerEnd = findEnglishMarkerEnd(sb)
                    if (markerEnd < 0) return@translateStreaming
                    englishGateOpen = true
                    contentStartOffset = markerEnd
                    lastCutOffset = markerEnd
                    Log.i(
                        TAG,
                        "Streaming: English marker found at offset $markerEnd " +
                            "(prefix='${sb.substring(0, markerEnd).take(60)}')",
                    )
                }

                // One delta may contain multiple terminators (e.g. Gemma
                // emits a whole clause at once). Drain them all before
                // yielding back for the next token.
                while (!dropRestOfChunk) {
                    val termIdx = findSentenceTerminator(sb, lastCutOffset)
                    if (termIdx < 0) break
                    val sentence = sb.substring(lastCutOffset, termIdx + 1).trim()
                    lastCutOffset = termIdx + 1
                    if (sentence.isEmpty()) continue

                    // Meta-text check runs on the accumulated English content
                    // (contentStartOffset .. lastCutOffset), not the whole
                    // buffer — otherwise Spanish transcription tokens would
                    // pollute the substring match and produce spurious hits.
                    val matched = matchMetaPattern(sb.substring(contentStartOffset, lastCutOffset))
                    if (matched != null) {
                        val n = metaTextDiscardCount.incrementAndGet()
                        Log.i(
                            TAG,
                            "Streaming: dropping rest of chunk after meta-text '$matched' " +
                                "acc='${sb.take(80)}' totalMeta=$n",
                        )
                        bus.emit(AudioEvent.EngineStatus(
                            "AST dropped meta-text reply ('$matched', total=$n)"))
                        // Discard any pending — it pre-dated the preamble
                        // hit and may itself be preamble text.
                        pendingText = null
                        pendingIndex = -1
                        dropRestOfChunk = true
                        return@translateStreaming
                    }

                    // A new closed sentence is available. Flush the prior
                    // pending as non-final; hold this one until the next
                    // boundary or flow completion.
                    emitPendingAsNonFinal()
                    pendingText = sentence
                    pendingIndex = sentenceCounter++
                }
            }
        } catch (t: Throwable) {
            errorCount.incrementAndGet()
            Log.e(TAG, "Gemma translateStreaming() failed", t)
            emitError("Gemma translateStreaming failed: ${t.javaClass.simpleName}: ${t.message}", chunk)
            return
        }

        // Aggregate metrics — same semantics as the one-shot path: one chunk
        // has been fully processed by Gemma.
        translatedCount.incrementAndGet()
        totalLatencyMs.addAndGet(result.latencyMs)

        if (dropRestOfChunk) return

        // Marker never arrived — Gemma ignored the official-prompt format.
        // Fall back to treating the entire reply as the translation (same
        // behaviour as the one-shot path's extractEnglishTranslation
        // fallback). Emit one final event with the whole buffer.
        if (!englishGateOpen) {
            val n = englishMarkerMissingCount.incrementAndGet()
            val fallback = sb.toString().trim()
            Log.w(
                TAG,
                "Streaming: 'English:' marker never appeared (missing #$n). Falling back to " +
                    "full reply='${fallback.take(80)}'",
            )
            bus.emit(AudioEvent.EngineStatus(
                "AST English marker missing, fell back to full reply (total=$n)"))
            val matchedFallback = matchMetaPattern(fallback)
            if (matchedFallback != null) {
                val m = metaTextDiscardCount.incrementAndGet()
                Log.i(TAG, "Streaming fallback: meta-text match '$matchedFallback' totalMeta=$m")
                bus.emit(AudioEvent.EngineStatus(
                    "AST dropped meta-text reply ('$matchedFallback', total=$m)"))
                return
            }
            if (fallback.isNotEmpty()) {
                bus.emit(
                    AudioEvent.TranslationReady(
                        text = fallback,
                        sourceChunkTimestampNs = chunk.timestampNs,
                        sourceDurationMs = chunk.durationMs,
                        sourcePeak = chunk.peak,
                        latencyMs = result.latencyMs,
                        timestampNs = System.nanoTime(),
                        sentenceIndex = 0,
                        isFinal = true,
                    ),
                )
            }
            return
        }

        // Final meta-text check on the entire English portion — defensive
        // against preambles that never contain a terminator ("The audio was
        // not provided" with no period). Uses the accumulated English
        // substring, not the whole buffer.
        val finalMatched = matchMetaPattern(sb.substring(contentStartOffset))
        if (finalMatched != null) {
            val n = metaTextDiscardCount.incrementAndGet()
            Log.i(TAG, "Streaming: final meta-text match '$finalMatched' totalMeta=$n")
            bus.emit(AudioEvent.EngineStatus(
                "AST dropped meta-text reply ('$finalMatched', total=$n)"))
            return
        }

        val trailing = sb.substring(lastCutOffset).trim()
        when {
            trailing.isNotEmpty() -> {
                // Both pending + trailing exist — pending is not final,
                // trailing is.
                emitPendingAsNonFinal()
                bus.emit(
                    AudioEvent.TranslationReady(
                        text = trailing,
                        sourceChunkTimestampNs = chunk.timestampNs,
                        sourceDurationMs = chunk.durationMs,
                        sourcePeak = chunk.peak,
                        latencyMs = result.latencyMs,
                        timestampNs = System.nanoTime(),
                        sentenceIndex = sentenceCounter++,
                        isFinal = true,
                    ),
                )
            }
            pendingText != null -> {
                // The last closed sentence IS the final one.
                bus.emit(
                    AudioEvent.TranslationReady(
                        text = pendingText!!,
                        sourceChunkTimestampNs = chunk.timestampNs,
                        sourceDurationMs = chunk.durationMs,
                        sourcePeak = chunk.peak,
                        latencyMs = result.latencyMs,
                        timestampNs = System.nanoTime(),
                        sentenceIndex = pendingIndex,
                        isFinal = true,
                    ),
                )
                pendingText = null
                pendingIndex = -1
            }
            // else: reply was empty — emit nothing (consumer sees the
            // metrics increment but no TranslationReady, same as the
            // one-shot path when Gemma returns "").
        }
    }

    /**
     * Case-insensitive scan for the `English:` marker Google's official AST
     * prompt tells Gemma to emit between the Spanish transcription and the
     * English translation. Returns the offset **after** the marker (and any
     * single trailing space) — i.e. the first character of the English
     * content — or `-1` if the marker is not yet present.
     *
     * Accepts `English:` (Google's exact literal from the prompt), and also
     * `english:` / `ENGLISH:` in case the model varies capitalisation, plus
     * an optional single space after the colon which the model routinely
     * emits.
     */
    private fun findEnglishMarkerEnd(sb: StringBuilder): Int {
        // Manual case-insensitive substring search. `indexOf(...,
        // ignoreCase = true)` on a StringBuilder promotes to CharSequence
        // via kotlin.text, which allocates a matcher per call — the tight
        // token-callback loop can hit this hundreds of times per chunk so
        // we roll our own to keep it allocation-free.
        val target = "english:"
        val n = sb.length
        val m = target.length
        if (n < m) return -1
        outer@ for (i in 0..(n - m)) {
            for (j in 0 until m) {
                val c = sb[i + j]
                val lower = if (c in 'A'..'Z') c + 32 else c
                if (lower != target[j]) continue@outer
            }
            var end = i + m
            if (end < n && sb[end] == ' ') end++
            return end
        }
        return -1
    }

    /**
     * One-shot English extraction — the same semantics as the streaming
     * gate, applied to a fully-decoded reply. When [AstConfig.
     * useOfficialAstPrompt] is false we skip extraction and return the
     * text as-is (the legacy prompt already yields English only).
     *
     * When the flag is on and the `English:` marker is present, returns the
     * substring after it (trimmed). When the flag is on and the marker is
     * missing, logs a warning, bumps [englishMarkerMissingCount], and
     * returns the whole reply — fallback so a mis-formatted response still
     * produces something useful rather than silently disappearing.
     */
    private fun extractEnglishTranslation(reply: String): String {
        if (!config.useOfficialAstPrompt) return reply.trim()
        val sb = StringBuilder(reply)
        val markerEnd = findEnglishMarkerEnd(sb)
        if (markerEnd < 0) {
            val n = englishMarkerMissingCount.incrementAndGet()
            Log.w(
                TAG,
                "One-shot: 'English:' marker missing (fallback #$n). Reply='${reply.take(80)}'",
            )
            return reply.trim()
        }
        return sb.substring(markerEnd).trim()
    }

    /** In-line sentence terminator scan. `.`, `!`, `?` only — no abbreviation
     *  handling. Kokoro's own `splitIntoSentences` does another pass, so a
     *  mid-abbreviation split here is at worst a cosmetic mis-boundary. */
    private fun findSentenceTerminator(sb: StringBuilder, from: Int): Int {
        for (i in from until sb.length) {
            val c = sb[i]
            if (c == '.' || c == '!' || c == '?') return i
        }
        return -1
    }

    private fun matchMetaPattern(text: String): String? {
        if (config.metaTextPatterns.isEmpty()) return null
        val lower = text.trim().lowercase()
        if (lower.isEmpty()) return null
        return config.metaTextPatterns.firstOrNull { pat -> lower.contains(pat) }
    }

    /**
     * Per-sample RMS on int16 PCM. Uses `Int` accumulation up-front and
     * `Double` sums to avoid overflow on long chunks — a 6-second chunk at
     * 16 kHz is 96 000 samples, and `sum(s*s)` on peaks-near-32 767 comfortably
     * exceeds `Long.MAX_VALUE` if you accumulate in the wrong type.
     */
    private fun computeRms(samples: ShortArray): Double {
        if (samples.isEmpty()) return 0.0
        var sumSq = 0.0
        for (s in samples) {
            val v = s.toDouble()
            sumSq += v * v
        }
        return kotlin.math.sqrt(sumSq / samples.size)
    }

    private suspend fun emitError(message: String, chunk: AudioEvent.ChunkReady) {
        // Always emit even on cancellation so the UI gets a final signal.
        withContext(NonCancellable) {
            bus.emit(
                AudioEvent.AstError(
                    message = message,
                    sourceChunkTimestampNs = chunk.timestampNs,
                    timestampNs = System.nanoTime(),
                ),
            )
        }
    }
}
