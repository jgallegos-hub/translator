package com.travel2chicago.gemmapipeline.asr

import ai.moonshine.voice.Transcriber
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val TAG = "MoonshineBench"

/**
 * On-device Moonshine ASR spike (cascade plan, step 1b). Loads each Spanish
 * streaming model from [ROOT] and transcribes every WAV in [ROOT]/wavs with
 * our own PCM (no Moonshine microphone), logging `MOONSHINEBENCH` lines with
 * the wall time and transcript, then the RTF per model.
 *
 * Push the models and chunks first (see tools/moonshine_bench.py), then:
 *   adb shell am start -n com.travel2chicago.gemmapipeline/.MainActivity --ez moonshine_bench true
 */
object MoonshineBench {

    const val ROOT = "/sdcard/Download/moonshine"

    /** Directory name -> Moonshine model arch (moonshine-c-api: TINY_STREAMING=2, SMALL_STREAMING=4). */
    val MODELS = listOf("tiny-streaming-es" to 2, "small-streaming-es" to 4)

    fun run(log: (String) -> Unit) {
        val wavs = File(ROOT, "wavs").listFiles { f -> f.name.endsWith(".wav") }?.sortedBy { it.name }.orEmpty()
        log("Moonshine benchmark STARTED (${wavs.size} wavs)")
        for ((dir, arch) in MODELS) {
            val modelDir = File(ROOT, dir)
            if (!modelDir.isDirectory) {
                Log.w(TAG, "MOONSHINEBENCH model=$dir SKIPPED (missing $modelDir)")
                continue
            }
            val t = Transcriber()
            try {
                val tl = System.nanoTime()
                t.loadFromFiles(modelDir.absolutePath, arch)
                Log.i(TAG, "MOONSHINEBENCH model=$dir loadMs=${(System.nanoTime() - tl) / 1_000_000}")
                if (wavs.isNotEmpty()) t.transcribeWithoutStreaming(readWav16k(wavs[0]), 16_000)  // warm-up
                var totMs = 0L; var totAudioMs = 0L
                for (w in wavs) {
                    val pcm = readWav16k(w)
                    val t0 = System.nanoTime()
                    // Transcript.text() returns only the first line; long chunks split
                    // into several lines, so join them all.
                    val text = t.transcribeWithoutStreaming(pcm, 16_000).lines
                        .joinToString(" ") { it.text.trim() }.trim()
                    val ms = (System.nanoTime() - t0) / 1_000_000
                    val audioMs = pcm.size * 1000L / 16_000
                    totMs += ms; totAudioMs += audioMs
                    Log.i(TAG, "MOONSHINEBENCH model=$dir file=${w.name} audioMs=$audioMs ms=$ms text=${text.trim()}")
                }
                val rtf = if (totAudioMs > 0) totMs.toDouble() / totAudioMs else 0.0
                Log.i(TAG, "MOONSHINEBENCH model=$dir SUMMARY files=${wavs.size} totalMs=$totMs audioMs=$totAudioMs rtf=${"%.3f".format(rtf)}")
                log("Moonshine $dir: RTF ${"%.3f".format(rtf)} over ${wavs.size} wavs")
            } catch (e: Throwable) {
                Log.e(TAG, "MOONSHINEBENCH model=$dir FAILED", e)
                log("[ERROR] Moonshine bench $dir: ${e.message}")
            } finally {
                t.close()
            }
        }
        log("Moonshine benchmark DONE")
    }

    /** Reads a 16 kHz mono 16-bit PCM WAV (as written by tools/chunker_sim.py) into floats in [-1, 1]. */
    private fun readWav16k(f: File): FloatArray {
        RandomAccessFile(f, "r").use { raf ->
            val bytes = ByteArray(raf.length().toInt()).also { raf.readFully(it) }
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            var pos = 12
            while (pos + 8 <= bytes.size) {
                val id = String(bytes, pos, 4, Charsets.US_ASCII)
                val size = bb.getInt(pos + 4)
                if (id == "data") {
                    val n = minOf(size, bytes.size - pos - 8) / 2
                    return FloatArray(n) { bb.getShort(pos + 8 + it * 2) / 32768f }
                }
                pos += 8 + size + (size and 1)
            }
            error("no data chunk in ${f.name}")
        }
    }
}
