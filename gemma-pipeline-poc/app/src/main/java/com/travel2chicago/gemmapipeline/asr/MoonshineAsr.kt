package com.travel2chicago.gemmapipeline.asr

import ai.moonshine.voice.Transcriber
import android.util.Log
import java.io.File

private const val TAG = "MoonshineAsr"

/** One Moonshine transcript line with the RMS (int16 scale) of its audio span. */
data class AsrLine(val text: String, val startS: Float, val durS: Float, val rms: Double)

/**
 * Moonshine Voice ASR over our own 16 kHz PCM (cascade plan, step 1d).
 *
 * Echo-residue gate: after AEC3 the residual of Kokoro's English voice is
 * still transcribed by Moonshine as invented Spanish ("no se puede disimular").
 * On the F0 corpus every real-speech line had RMS >= ~2200 and every residue
 * line <= ~1300 (tools/logs/cascade, 2026-10-14), so lines under
 * [lineRmsThreshold] are dropped before translation.
 */
class MoonshineAsr private constructor(private val transcriber: Transcriber) : AutoCloseable {

    fun transcribe(samples: ShortArray, sampleRate: Int = 16_000): List<AsrLine> {
        val pcm = FloatArray(samples.size) { samples[it] / 32768f }
        val transcript = synchronized(transcriber) { transcriber.transcribeWithoutStreaming(pcm, sampleRate) }
        return transcript.lines.mapNotNull { l ->
            val text = l.text?.trim().orEmpty()
            if (text.isEmpty()) return@mapNotNull null
            val s = (l.startTime * sampleRate).toInt().coerceIn(0, samples.size)
            val e = ((l.startTime + l.duration) * sampleRate).toInt().coerceIn(s, samples.size)
            AsrLine(text, l.startTime, l.duration, rms(samples, s, if (e > s) e else samples.size))
        }
    }

    override fun close() = transcriber.close()

    companion object {
        const val DEFAULT_DIR = "/sdcard/Download/moonshine/tiny-streaming-es"
        /** moonshine-c-api model arch: TINY_STREAMING=2, SMALL_STREAMING=4. */
        const val ARCH_TINY_STREAMING = 2

        fun load(modelDir: String = DEFAULT_DIR, arch: Int = ARCH_TINY_STREAMING): MoonshineAsr {
            check(File(modelDir).isDirectory) { "Moonshine model not found at $modelDir" }
            val t = Transcriber()
            val t0 = System.nanoTime()
            t.loadFromFiles(modelDir, arch)
            Log.i(TAG, "Moonshine loaded from $modelDir in ${(System.nanoTime() - t0) / 1_000_000} ms")
            return MoonshineAsr(t)
        }

        fun rms(samples: ShortArray, from: Int, to: Int): Double {
            if (to <= from) return 0.0
            var sum = 0.0
            for (i in from until to) { val v = samples[i].toDouble(); sum += v * v }
            return kotlin.math.sqrt(sum / (to - from))
        }
    }
}
