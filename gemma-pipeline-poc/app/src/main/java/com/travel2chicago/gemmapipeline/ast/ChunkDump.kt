package com.travel2chicago.gemmapipeline.ast

import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

private const val TAG = "ChunkDump"

/**
 * Session record (2026-11-10, field use since 2026-11-11): every chunk the cascade receives gets an
 * `index.tsv` row with what happened to it and how long each step took, plus its WAV; `events.tsv` keeps
 * session events (start/stop, speaker connected/disconnected, Abraham's "✗ esa no" marks).
 *
 * Privacy: chunks the speaker gate rejected (other people, echo) keep only numbers — no audio, no text —
 * because in the field the microphone also hears strangers and coworkers. Everything stays in the app's files
 * dir on the phone and is pulled by adb; never to git.
 */
class ChunkDump(private val dir: File) {
    private val seq = AtomicInteger(0)
    private val clock = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val index = File(dir, "index.tsv")
    private val events = File(dir, "events.tsv")

    /** Last chunk that was spoken, for the "✗ esa no" mark. */
    @Volatile var lastSpokenSeq: Int = 0
        private set
    @Volatile private var lastSpokenEnglish: String = ""

    init {
        dir.mkdirs()
        if (!index.exists()) {
            index.writeText(
                "seq\ttime\tfile\tdurMs\tttsActive\tgate\tlines\tspanish\tenglish\toutcome\tsim\t" +
                    "asrMs\tmtFirstMs\ttotalMs\tmt\tverified\n",
            )
        }
        if (!events.exists()) events.writeText("time\tevent\tdetail\n")
        Log.i(TAG, "dumping chunks to ${dir.absolutePath}")
    }

    /** Per-chunk timings and the translator actually used (server or local fallback). */
    data class Timing(val asrMs: Long, val mtFirstMs: Long = -1, val totalMs: Long = -1, val mt: String = "", val verified: Boolean = false)

    fun record(
        wav: ByteArray,
        durMs: Long,
        ttsActive: Boolean,
        gate: Double,
        lines: List<String>,
        spanish: String,
        english: String,
        outcome: String,
        similarity: Float? = null,
        timing: Timing? = null,
    ) {
        runCatching {
            val n = seq.incrementAndGet()
            val private = outcome == "speaker_blocked"
            val name = if (private) "" else "chunk_%04d.wav".format(n)
            if (!private) File(dir, name).writeBytes(wav)
            val clean = { s: String -> s.replace('\t', ' ').replace('\n', ' ') }
            synchronized(index) {
                index.appendText(
                    listOf(
                        n, clock.format(Date()), name, durMs, ttsActive, "%.0f".format(gate),
                        if (private) "" else clean(lines.joinToString(" | ")), clean(spanish), clean(english), outcome,
                        similarity?.let { "%.3f".format(it) } ?: "",
                        timing?.asrMs ?: "", timing?.mtFirstMs ?: "", timing?.totalMs ?: "", timing?.mt ?: "",
                        timing?.verified ?: "",
                    ).joinToString("\t") + "\n",
                )
            }
            if (outcome == "spoken") { lastSpokenSeq = n; lastSpokenEnglish = english }
        }.onFailure { Log.w(TAG, "dump failed: ${it.message}") }
    }

    fun event(kind: String, detail: String = "") {
        runCatching {
            synchronized(events) {
                events.appendText("${clock.format(Date())}\t$kind\t${detail.replace('\t', ' ').replace('\n', ' ')}\n")
            }
        }.onFailure { Log.w(TAG, "event failed: ${it.message}") }
    }

    /** Abraham's "✗ esa no": marks the last spoken chunk as wrong, to review it first. */
    fun flagLast(): String {
        val detail = if (lastSpokenSeq == 0) "(nothing spoken yet)" else "seq=$lastSpokenSeq en='$lastSpokenEnglish'"
        event("flag", detail)
        return detail
    }
}
