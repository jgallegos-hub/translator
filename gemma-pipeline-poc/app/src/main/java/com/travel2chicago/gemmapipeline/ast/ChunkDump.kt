package com.travel2chicago.gemmapipeline.ast

import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

private const val TAG = "ChunkDump"

/**
 * Diagnostics only (2026-11-10): saves every chunk the cascade receives as a WAV next to an
 * `index.tsv` row with what happened to it, to build a labelled set (Abraham's speech, his
 * laughter, other speakers, TTS echo) for choosing an audio gate before ASR. The audio is the
 * user's voice: it stays in the app's files dir on the phone and is pulled by adb, never to git.
 */
class ChunkDump(private val dir: File) {
    private val seq = AtomicInteger(0)
    private val clock = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val index = File(dir, "index.tsv")

    init {
        dir.mkdirs()
        if (!index.exists()) {
            index.writeText("seq\ttime\tfile\tdurMs\tttsActive\tgate\tlines\tspanish\tenglish\toutcome\n")
        }
        Log.i(TAG, "dumping chunks to ${dir.absolutePath}")
    }

    fun record(
        wav: ByteArray,
        durMs: Long,
        ttsActive: Boolean,
        gate: Double,
        lines: List<String>,
        spanish: String,
        english: String,
        outcome: String,
    ) {
        runCatching {
            val n = seq.incrementAndGet()
            val name = "chunk_%04d.wav".format(n)
            File(dir, name).writeBytes(wav)
            val clean = { s: String -> s.replace('\t', ' ').replace('\n', ' ') }
            synchronized(index) {
                index.appendText(
                    listOf(
                        n, clock.format(Date()), name, durMs, ttsActive, "%.0f".format(gate),
                        clean(lines.joinToString(" | ")), clean(spanish), clean(english), outcome,
                    ).joinToString("\t") + "\n",
                )
            }
        }.onFailure { Log.w(TAG, "dump failed: ${it.message}") }
    }
}
