package com.travel2chicago.vadpoc.vad

import android.util.Log

private const val TAG = "FrameReassembler"

/**
 * Re-frames variable-size audio chunks (1–2048 samples from Oboe via the
 * capture drain) into fixed [frameSize]-sample frames that Silero VAD can
 * consume.
 *
 * There is no Python equivalent — the prototype used `sounddevice` which
 * delivered exact 512-sample frames already. On Android the drain coroutine
 * produces irregular chunks based on what the ring buffer has accumulated
 * since the last 25 ms tick, so we need to re-batch here.
 *
 * The implementation keeps a single growable [ShortArray] tail buffer; each
 * [feed] call appends to the tail, slices out as many full frames as fit, and
 * leaves the remainder for next time. Sum-equality is preserved: across any
 * sequence of feeds, `sum(frame sizes emitted) + tail.size == sum(input sizes)`.
 */
class FrameReassembler(val frameSize: Int = 512) {
    init { require(frameSize > 0) { "frameSize must be positive" } }

    private var tail: ShortArray = ShortArray(0)
    private var loggedFirstEmission: Boolean = false

    /** Total samples consumed since the last [reset]. Useful for diagnostics. */
    var totalSamplesIn: Long = 0
        private set

    /** Total samples emitted (in completed frames) since the last [reset]. */
    var totalSamplesOut: Long = 0
        private set

    /** Number of samples currently buffered waiting for a full frame. */
    val pendingSamples: Int get() = tail.size

    /**
     * Append [samples] to the tail buffer and emit a list of complete frames
     * of exactly [frameSize] each. Returns an empty list when not enough new
     * samples have arrived.
     */
    fun feed(samples: ShortArray): List<ShortArray> {
        if (samples.isEmpty()) return emptyList()
        totalSamplesIn += samples.size

        // Concatenate tail + samples in a single allocation.
        val combined = ShortArray(tail.size + samples.size)
        System.arraycopy(tail, 0, combined, 0, tail.size)
        System.arraycopy(samples, 0, combined, tail.size, samples.size)

        val fullFrames = combined.size / frameSize
        if (fullFrames == 0) {
            tail = combined
            return emptyList()
        }

        val frames = ArrayList<ShortArray>(fullFrames)
        var offset = 0
        for (i in 0 until fullFrames) {
            val frame = ShortArray(frameSize)
            System.arraycopy(combined, offset, frame, 0, frameSize)
            frames.add(frame)
            offset += frameSize
        }
        totalSamplesOut += fullFrames.toLong() * frameSize

        if (!loggedFirstEmission) {
            loggedFirstEmission = true
            Log.i(TAG, "Reassembled first frame: emitted $fullFrames frame(s) of $frameSize samples " +
                "(consumed ${combined.size} samples total)")
        }

        val remaining = combined.size - offset
        tail = if (remaining == 0) {
            EMPTY
        } else {
            val newTail = ShortArray(remaining)
            System.arraycopy(combined, offset, newTail, 0, remaining)
            newTail
        }
        return frames
    }

    fun reset() {
        tail = EMPTY
        totalSamplesIn = 0
        totalSamplesOut = 0
        loggedFirstEmission = false
    }

    companion object {
        private val EMPTY = ShortArray(0)
    }
}
