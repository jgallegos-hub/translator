package com.travel2chicago.gemmapipeline.chunker

import android.util.Log
import com.travel2chicago.gemmapipeline.audio.AudioEvent
import com.travel2chicago.gemmapipeline.audio.AudioFormat
import com.travel2chicago.gemmapipeline.audio.VadState
import kotlin.math.abs

private const val TAG = "AudioChunker"

/**
 * Accumulates VAD-confirmed speech into 3–6 s chunks ready for Gemma AST.
 * Port of `AudioChunker` in `src/audio_manager/audio_chunker.py`.
 *
 * Two emission triggers:
 *   1. **Max-size**: the running chunk reaches [ChunkerConfig.maxChunkMs].
 *      With [ChunkerConfig.smartCut] (default) the chunk is cut at its LAST
 *      pause (>= [ChunkerConfig.pauseCutMs] of frames with speech probability
 *      under the VAD threshold) - or, if there was none, at the lowest-
 *      probability frame of the last 2 s - and the remainder is carried into
 *      the next chunk, so words are not split. Legacy mode cuts right at the
 *      cap (mid-utterance split).
 *   2. **End-of-utterance**: VAD has been SILENCE for [ChunkerConfig.silenceEndMs]
 *      AND the chunk is at least [ChunkerConfig.minChunkMs] long — emit and
 *      go back to "not collecting".
 *
 * Pre-roll: a bounded FIFO of the most recent N silence frames is prepended
 * to a chunk on speech onset. This recovers the first ~200 ms before the VAD
 * fires, so the first phoneme isn't clipped.
 *
 * Unlike the Python prototype (which used a void callback), [feed] and
 * [flush] return the emitted chunk directly so the caller can route it to a
 * coroutine or a bus without wrapping.
 */
class AudioChunker(
    private val config: ChunkerConfig,
    private val format: AudioFormat,
) {
    /** Minimum sample count that [feed] requires before emitting on
     *  end-of-utterance. Exposed so the pipeline can decide whether a
     *  partial chunk is worth flushing across an audio-timeline
     *  discontinuity (e.g. mic mute during TTS playback). */
    val minChunkSamples: Int = config.minChunkSamples(format)
    private val maxChunkSamples: Int = config.maxChunkSamples(format)
    private val silenceEndFrames: Int = config.silenceEndFrames(format)
    private val preRollFrames: Int = config.preRollFrames(format)
    private val pauseCutFrames: Int = config.pauseCutFrames(format)
    private val minChunkFrames: Int = (minChunkSamples / format.blockSize).coerceAtLeast(1)
    private val backtrackFrames: Int = (2 * format.sampleRate / format.blockSize).coerceAtLeast(1)

    private val chunkFrames: ArrayList<ShortArray> = ArrayList()
    private var chunkSampleCount: Int = 0
    private val preRoll: ArrayDeque<ShortArray> = ArrayDeque()
    private var silenceCount: Int = 0
    private var collecting: Boolean = false
    /** Speech probability per frame in [chunkFrames] (smart cut). */
    private val chunkProbs: ArrayList<Float> = ArrayList()
    private var lowRun: Int = 0
    /** Frame index (exclusive) right after the most recent pause, or -1. */
    private var lastPauseEnd: Int = -1

    val isCollecting: Boolean get() = collecting

    /** Samples currently buffered in the running chunk (0 if not collecting). */
    val currentSampleCount: Int get() = chunkSampleCount

    /**
     * Full reset — clears the running chunk, the pre-roll ring, and the
     * silence counter. Use this when the mic input has a discontinuity that
     * makes any leftover data misleading (e.g. we just muted the VAD during
     * TTS playback, so the pre-roll frames are pre-mute audio that shouldn't
     * be prepended to a post-mute utterance).
     */
    fun resetAll() {
        reset()
        preRoll.clear()
    }

    /**
     * Feed one frame plus its VAD state. Returns the assembled chunk if this
     * call crossed an emission threshold, otherwise null.
     */
    fun feed(
        frame: ShortArray,
        vadState: VadState,
        speechProb: Float = if (vadState == VadState.SPEECH) 1f else 0f,
        vadThreshold: Float = 0.5f,
    ): AudioEvent.ChunkReady? {
        if (!collecting) {
            // Pre-roll always captures the latest frames so the syllable onset
            // isn't clipped. On the SILENCE→SPEECH crossing, start_collecting
            // dumps the pre-roll (which contains *this* frame) into chunkFrames,
            // so we MUST NOT also append it below — that would double-count.
            pushPreRoll(frame)
            if (vadState == VadState.SPEECH) {
                startCollecting()
                // Pre-roll frames carry no stored probability; treat them as
                // speech so they are never chosen as a cut point.
                while (chunkProbs.size < chunkFrames.size) chunkProbs.add(1f)
            } else {
                return null
            }
        } else {
            chunkFrames.add(frame.copyOf())
            chunkProbs.add(speechProb)
            chunkSampleCount += frame.size
            silenceCount = if (vadState == VadState.SPEECH) 0 else silenceCount + 1
            lowRun = if (speechProb < vadThreshold) lowRun + 1 else 0
            if (lowRun >= pauseCutFrames && chunkFrames.size >= minChunkFrames) {
                lastPauseEnd = chunkFrames.size
            }
        }

        return when {
            chunkSampleCount >= maxChunkSamples ->
                if (config.smartCut) emitAtLastPause() else emit()
            vadState == VadState.SILENCE &&
                silenceCount >= silenceEndFrames &&
                chunkSampleCount >= minChunkSamples -> emit()
            else -> null
        }
    }

    /**
     * Force-emit whatever has been collected. Returns null if not currently
     * collecting or if the buffer is empty. Does NOT enforce min-chunk size —
     * caller is asking for everything we have.
     */
    fun flush(): AudioEvent.ChunkReady? {
        if (!collecting || chunkFrames.isEmpty()) {
            reset()
            return null
        }
        return emit()
    }

    /** Smart cut at the hard cap: emit up to the last pause (or the quietest
     *  frame of the last ~2 s), keep the remainder as the start of the next
     *  chunk. Never splits mid-word unless the speaker never paused at all. */
    private fun emitAtLastPause(): AudioEvent.ChunkReady {
        val cut = if (lastPauseEnd > minChunkFrames && lastPauseEnd < chunkFrames.size) {
            lastPauseEnd
        } else {
            val lo = maxOf(minChunkFrames, chunkFrames.size - backtrackFrames)
                .coerceAtMost(chunkFrames.size - 1)
            var best = chunkFrames.size - 1
            for (i in lo until chunkFrames.size) if (chunkProbs[i] < chunkProbs[best]) best = i
            (best + 1).coerceIn(1, chunkFrames.size)
        }
        val head = ArrayList(chunkFrames.subList(0, cut))
        val tail = ArrayList(chunkFrames.subList(cut, chunkFrames.size))
        val tailProbs = ArrayList(chunkProbs.subList(cut, chunkProbs.size))
        val usedPause = cut == lastPauseEnd
        chunkFrames.clear(); chunkFrames.addAll(head)
        chunkSampleCount = head.sumOf { it.size }
        Log.d(TAG, "smart cut at frame $cut/${head.size + tail.size} (pause=$usedPause)")
        val out = emit()
        if (tail.isNotEmpty()) {
            collecting = true
            chunkFrames.addAll(tail)
            chunkProbs.addAll(tailProbs)
            chunkSampleCount = tail.sumOf { it.size }
        }
        return out
    }

    private fun pushPreRoll(frame: ShortArray) {
        if (preRollFrames == 0) return
        if (preRoll.size >= preRollFrames) {
            preRoll.removeFirst()
        }
        preRoll.addLast(frame.copyOf())
    }

    private fun startCollecting() {
        collecting = true
        silenceCount = 0
        // Dump the pre-roll into the chunk so we capture the syllable onset.
        for (rollFrame in preRoll) {
            chunkFrames.add(rollFrame)
            chunkSampleCount += rollFrame.size
        }
        preRoll.clear()
        Log.d(TAG, "started collecting (pre-roll ${chunkSampleCount} samples)")
    }

    private fun emit(): AudioEvent.ChunkReady {
        val total = chunkSampleCount
        val out = ShortArray(total)
        var offset = 0
        var peak = 0
        for (f in chunkFrames) {
            System.arraycopy(f, 0, out, offset, f.size)
            for (s in f) {
                val a = if (s.toInt() == Short.MIN_VALUE.toInt()) Short.MAX_VALUE.toInt() else abs(s.toInt())
                if (a > peak) peak = a
            }
            offset += f.size
        }
        val durationMs = (total.toLong() * 1000L / format.sampleRate).toInt()
        Log.d(TAG, "emitting chunk: ${durationMs}ms ($total samples, peak=$peak)")

        reset()
        return AudioEvent.ChunkReady(
            samples = out,
            durationMs = durationMs,
            peak = peak,
            timestampNs = System.nanoTime(),
        )
    }

    private fun reset() {
        chunkFrames.clear()
        chunkProbs.clear()
        chunkSampleCount = 0
        collecting = false
        silenceCount = 0
        lowRun = 0
        lastPauseEnd = -1
    }
}
