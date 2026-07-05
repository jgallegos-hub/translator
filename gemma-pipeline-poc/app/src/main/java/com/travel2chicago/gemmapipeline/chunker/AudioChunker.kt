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
 *   1. **Max-size**: the running chunk reaches [ChunkerConfig.maxChunkMs] —
 *      cut and start a new chunk immediately (mid-utterance split).
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

    private val chunkFrames: ArrayList<ShortArray> = ArrayList()
    private var chunkSampleCount: Int = 0
    private val preRoll: ArrayDeque<ShortArray> = ArrayDeque()
    private var silenceCount: Int = 0
    private var collecting: Boolean = false

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
    fun feed(frame: ShortArray, vadState: VadState): AudioEvent.ChunkReady? {
        if (!collecting) {
            // Pre-roll always captures the latest frames so the syllable onset
            // isn't clipped. On the SILENCE→SPEECH crossing, start_collecting
            // dumps the pre-roll (which contains *this* frame) into chunkFrames,
            // so we MUST NOT also append it below — that would double-count.
            pushPreRoll(frame)
            if (vadState == VadState.SPEECH) {
                startCollecting()
            } else {
                return null
            }
        } else {
            chunkFrames.add(frame.copyOf())
            chunkSampleCount += frame.size
            silenceCount = if (vadState == VadState.SPEECH) 0 else silenceCount + 1
        }

        return when {
            chunkSampleCount >= maxChunkSamples -> emit()
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
        chunkSampleCount = 0
        collecting = false
        silenceCount = 0
    }
}
