package com.travel2chicago.gemmapipeline.mt

import android.util.Log
import com.travel2chicago.gemmapipeline.ast.LiteRtGemmaAstEngine
import java.io.File

private const val TAG = "GemmaTextBench"

/**
 * Cascade plan: Gemma 4 E4B as a TEXT translator (input = Spanish transcript,
 * no audio encoder). Same inputs as [MlKitBench]; logs `GEMMATEXTBENCH` lines
 * with first-token and total time so it can be compared with ML Kit and with
 * Gemma's live audio path (first token ~1.13 s on the F0 session).
 *
 *   adb shell am start -n com.travel2chicago.gemmapipeline/.MainActivity --ez gemma_text_bench true
 */
object GemmaTextBench {

    const val PROMPT =
        "Translate this Mexican Spanish speech transcript into natural, idiomatic English. " +
            "Translate slang and colloquial expressions by their meaning, not word by word. " +
            "Reply with the English translation only.\n\nSpanish: "

    suspend fun run(engine: LiteRtGemmaAstEngine, log: (String) -> Unit) {
        log("Gemma text benchmark STARTED")
        engine.translateText(PROMPT + "Hola, ¿cómo estás?")   // warm-up
        for (name in listOf("mt_ref.txt", "mt_asr.txt")) {
            val f = File(MlKitBench.ROOT, name)
            if (!f.isFile) { Log.w(TAG, "GEMMATEXTBENCH $name SKIPPED (missing)"); continue }
            val firsts = ArrayList<Long>(); val totals = ArrayList<Long>()
            f.readLines().filter { it.isNotBlank() }.forEachIndexed { i, line ->
                val (id, text) = line.split('\t', limit = 2).let { if (it.size == 2) it[0] to it[1] else "$i" to it[0] }
                if (text.isBlank()) {
                    Log.i(TAG, "GEMMATEXTBENCH set=$name id=$id first=0 ms=0 src= out=")
                    return@forEachIndexed
                }
                try {
                    val (out, first, total) = engine.translateText(PROMPT + text)
                    firsts += first; totals += total
                    Log.i(TAG, "GEMMATEXTBENCH set=$name id=$id first=$first ms=$total src=$text out=${out.replace('\n', ' ')}")
                } catch (e: Throwable) {
                    Log.e(TAG, "GEMMATEXTBENCH set=$name id=$id FAILED", e)
                }
            }
            firsts.sort(); totals.sort()
            val msg = "set=$name n=${totals.size} firstP50=${firsts.getOrElse(firsts.size / 2) { 0 }} " +
                "totalP50=${totals.getOrElse(totals.size / 2) { 0 }} totalP90=${totals.getOrElse(totals.size * 9 / 10) { 0 }} " +
                "totalMax=${totals.lastOrNull() ?: 0}"
            Log.i(TAG, "GEMMATEXTBENCH SUMMARY $msg")
            log("Gemma text $msg")
        }
        log("Gemma text benchmark DONE")
    }
}
