package com.travel2chicago.gemmapipeline.gate

import android.util.Log

private const val TAG = "SpeakerGate"

/**
 * Shadow mode of the speaker gate (2026-11-10): scores every chunk against Abraham's profile and logs
 * what the gate WOULD do, without blocking anything. Also handles enrolment: while [enrolling], chunks
 * without our own TTS in them are added to the profile instead of being scored.
 */
class SpeakerShadow(
    private val gate: SpeakerGate,
    var threshold: Float = DEFAULT_THRESHOLD,
) {
    @Volatile var enrolling = false
        private set

    data class Verdict(val similarity: Float, val wouldBlock: Boolean, val ms: Long)

    fun startEnrolment(reset: Boolean) {
        if (reset) gate.clearProfile()
        enrolling = true
        Log.i(TAG, "ENROLL start (reset=$reset) — speak Spanish (and a bit of English) alone for ~45 s")
    }

    fun stopEnrolment() {
        enrolling = false
        gate.saveProfile()
        Log.i(TAG, "ENROLL stop: ${"%.1f".format(gate.enrolledSeconds)} s enrolled")
    }

    /** Returns the verdict, or null while enrolling / without a profile / on error. */
    fun observe(samples: ShortArray, ttsActive: Boolean): Verdict? = runCatching {
        val durMs = samples.size * 1000L / 16_000
        if (durMs < MIN_CHUNK_MS) return null
        val t0 = System.nanoTime()
        if (enrolling) {
            if (ttsActive) {
                Log.i(TAG, "ENROLL skip chunk ${durMs} ms: our TTS was playing")
            } else {
                gate.addToProfile(samples)
                Log.i(TAG, "ENROLL +${durMs} ms -> ${"%.1f".format(gate.enrolledSeconds)} s (${(System.nanoTime() - t0) / 1_000_000} ms)")
            }
            return null
        }
        if (!gate.hasProfile) return null
        val sim = gate.similarity(gate.embed(samples)) ?: return null
        Verdict(sim, sim < threshold, (System.nanoTime() - t0) / 1_000_000)
    }.getOrElse {
        Log.w(TAG, "speaker check failed: ${it.message}")
        null
    }

    companion object {
        /** Offline set: Abraham >= 0.48, everyone/everything else <= 0.35. */
        const val DEFAULT_THRESHOLD = 0.40f
        private const val MIN_CHUNK_MS = 400L
    }
}
