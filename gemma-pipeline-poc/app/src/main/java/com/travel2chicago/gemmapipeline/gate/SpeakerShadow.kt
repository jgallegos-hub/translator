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

    /** true: chunks below [threshold] are dropped before the translator (default since r29);
     *  false: shadow only, log what would be blocked. adb: --ez speaker_enforce true|false. */
    @Volatile var enforce = true

    /**
     * [similarity] = best window; [wouldBlock] = no loud window is his; [hisRuns] = sample ranges of his
     * windows (+ margin) when the chunk is [mixed] (a loud window that is not his), else empty.
     */
    data class Verdict(
        val similarity: Float,
        val wouldBlock: Boolean,
        val ms: Long,
        val mixed: Boolean = false,
        val hisRuns: List<IntRange> = emptyList(),
        val windows: String = "",
    ) {
        /** His runs joined with 0.2 s of silence, for a second ASR pass over only his voice. */
        fun cut(samples: ShortArray): ShortArray {
            val gap = ShortArray(GAP_SAMPLES)
            val parts = hisRuns.flatMap { r -> listOf(samples.copyOfRange(r.first, r.last + 1), gap) }
            val out = ShortArray(parts.sumOf { it.size })
            var o = 0
            for (p in parts) { p.copyInto(out, o); o += p.size }
            return out
        }

        val keptMs: Long get() = hisRuns.sumOf { (it.last - it.first + 1).toLong() } * 1000 / 16_000
    }

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
        // Speaker scan (2026-11-11, tools/segment_gate_eval.py): 1.5 s windows every 0.5 s. Quiet windows are
        // silence, neither his nor someone else's. r32: an English video and Abraham inside one Moonshine line
        // were separated in time (video 0.15-0.26, then him 0.51-0.83).
        val win = gate.scan(samples, WIN_FRAMES, HOP_FRAMES)
        val loud = win.filter { it.rms >= QUIET_RMS }
        val his = loud.filter { it.similarity >= threshold }
        val best = win.maxOf { it.similarity }
        val ms = (System.nanoTime() - t0) / 1_000_000
        val desc = win.joinToString(" ") { "%.2f%s".format(it.similarity, if (it.rms < QUIET_RMS) "q" else "") }
        if (his.isEmpty()) return Verdict(best, wouldBlock = true, ms = ms, windows = desc)
        if (his.size == loud.size) return Verdict(best, wouldBlock = false, ms = ms, windows = desc)
        // Mixed: keep only his windows (+ margin), merged into runs.
        val mask = BooleanArray(samples.size)
        for (w in his) {
            val a = (w.startFrame * SpeakerGate.FRAME_SHIFT - MARGIN_SAMPLES).coerceAtLeast(0)
            val b = ((w.startFrame + w.frames) * SpeakerGate.FRAME_SHIFT + MARGIN_SAMPLES).coerceAtMost(samples.size)
            for (i in a until b) mask[i] = true
        }
        val runs = ArrayList<IntRange>()
        var i = 0
        while (i < mask.size) {
            if (!mask[i]) { i++; continue }
            val start = i
            while (i < mask.size && mask[i]) i++
            runs.add(start until i)
        }
        Verdict(best, wouldBlock = false, ms = ms, mixed = true, hisRuns = runs, windows = desc)
    }.getOrElse {
        Log.w(TAG, "speaker check failed: ${it.message}")
        null
    }

    companion object {
        /** Offline set: Abraham >= 0.48, everyone/everything else <= 0.35. */
        const val DEFAULT_THRESHOLD = 0.40f
        private const val MIN_CHUNK_MS = 400L
        private const val WIN_FRAMES = 150 // 1.5 s
        private const val HOP_FRAMES = 50 // 0.5 s
        /** Window RMS (int16) below this is silence. */
        private const val QUIET_RMS = 1200.0
        private const val MARGIN_SAMPLES = 4_000 // 0.25 s each side of his windows
        internal const val GAP_SAMPLES = 3_200 // 0.2 s of silence between his runs
    }
}
