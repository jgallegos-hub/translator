package com.travel2chicago.gemmapipeline.mt

import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import java.io.File

private const val TAG = "MlKitBench"

/**
 * On-device ML Kit Translate spike (cascade plan, step 1c). Downloads the
 * es->en model once (~30 MB, Google Play services), then translates every
 * line of each input file and logs `MLKITBENCH` lines with time and output.
 *
 * Inputs in [ROOT] (written by tools/cascade_bench.py mtinput):
 *   mt_ref.txt  - the F0 script phrases (MT quality on clean text)
 *   mt_asr.txt  - Moonshine Tiny output per chunk, "file<TAB>text" (full cascade)
 *
 *   adb shell am start -n com.travel2chicago.gemmapipeline/.MainActivity --ez mlkit_bench true
 */
object MlKitBench {

    const val ROOT = "/sdcard/Download/moonshine"

    fun run(log: (String) -> Unit) {
        val translator = Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.SPANISH)
                .setTargetLanguage(TranslateLanguage.ENGLISH)
                .build()
        )
        try {
            val td = System.nanoTime()
            Tasks.await(translator.downloadModelIfNeeded(DownloadConditions.Builder().build()))
            Log.i(TAG, "MLKITBENCH model ready in ${(System.nanoTime() - td) / 1_000_000} ms")
            log("ML Kit es->en model ready")
            Tasks.await(translator.translate("hola"))   // warm-up
            for (name in listOf("mt_ref.txt", "mt_asr.txt")) {
                val f = File(ROOT, name)
                if (!f.isFile) { Log.w(TAG, "MLKITBENCH $name SKIPPED (missing)"); continue }
                val times = ArrayList<Long>()
                f.readLines().filter { it.isNotBlank() }.forEachIndexed { i, line ->
                    val (id, text) = line.split('\t', limit = 2).let { if (it.size == 2) it[0] to it[1] else "$i" to it[0] }
                    if (text.isBlank()) {
                        Log.i(TAG, "MLKITBENCH set=$name id=$id ms=0 src= out=")
                        return@forEachIndexed
                    }
                    val t0 = System.nanoTime()
                    val out = Tasks.await(translator.translate(text))
                    val ms = (System.nanoTime() - t0) / 1_000_000
                    times += ms
                    Log.i(TAG, "MLKITBENCH set=$name id=$id ms=$ms src=$text out=$out")
                }
                times.sort()
                val p50 = times.getOrElse(times.size / 2) { 0 }
                val max = times.lastOrNull() ?: 0
                Log.i(TAG, "MLKITBENCH set=$name SUMMARY n=${times.size} p50=$p50 max=$max")
                log("ML Kit $name: n=${times.size} p50=${p50}ms max=${max}ms")
            }
        } catch (e: Throwable) {
            Log.e(TAG, "MLKITBENCH FAILED", e)
            log("[ERROR] ML Kit bench: ${e.message}")
        } finally {
            translator.close()
        }
        log("ML Kit benchmark DONE")
    }
}
