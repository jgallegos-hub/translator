package com.travel2chicago.gemmapipeline.tts

import android.content.Context
import android.util.Log

private const val TAG = "KokoroBench"

/**
 * On-device Kokoro speed benchmark (2026-10-13 TTS latency work). Loads one
 * engine per variant (threads / XNNPACK), synthesises fixed sentences after a
 * warm-up, and logs `KOKOROBENCH` lines with synthesis time, audio duration
 * and RTF (synthesis / audio; < 1 = faster than real time).
 *
 * Triggered with:
 *   adb shell am start -n com.travel2chicago.gemmapipeline/.MainActivity --ez kokoro_bench true
 */
object KokoroBench {

    val SENTENCES = listOf(
        "Hello, how are you?",
        "I would like to buy two tickets for the museum, please.",
        "Okay, I see that the transcription occurred, but the audio arrived " +
            "a little later than I expected today.",
    )

    /**
     * Round 1 (int8 only, 2026-10-13): cpu2 0.93, cpu4 0.84, cpu6 0.92,
     * xnn4 0.96, xnn6 0.96 — XNNPACK cannot accelerate the int8 dynamic-quant
     * ops. Round 2 compares the float exports (fp16 / fp32), which XNNPACK can
     * accelerate. Variants whose model file is missing on the device are skipped.
     */
    fun variants(base: TtsConfig): List<Pair<String, TtsConfig>> = listOf(
        // Round 3 (2026-10-14, ORT 1.23.2): thread scaling of fp16/int8.
        "fp16-cpu2" to base.copy(modelFilename = "kokoro-v1.0.fp16.onnx", intraOpThreads = 2, useXnnpack = false),
        "fp16-cpu6" to base.copy(modelFilename = "kokoro-v1.0.fp16.onnx", intraOpThreads = 6, useXnnpack = false),
        "fp16-cpu8" to base.copy(modelFilename = "kokoro-v1.0.fp16.onnx", intraOpThreads = 8, useXnnpack = false),
        "int8-cpu6" to base.copy(intraOpThreads = 6, useXnnpack = false),
        "int8-cpu4" to base.copy(intraOpThreads = 4, useXnnpack = false),
        "fp16-cpu4" to base.copy(modelFilename = "kokoro-v1.0.fp16.onnx", intraOpThreads = 4, useXnnpack = false),
        "fp16-xnn4" to base.copy(modelFilename = "kokoro-v1.0.fp16.onnx", intraOpThreads = 4, useXnnpack = true),
        "fp32-cpu4" to base.copy(modelFilename = "kokoro-v1.0.onnx", intraOpThreads = 4, useXnnpack = false),
        "fp32-xnn4" to base.copy(modelFilename = "kokoro-v1.0.onnx", intraOpThreads = 4, useXnnpack = true),
        "fp32-xnn6" to base.copy(modelFilename = "kokoro-v1.0.onnx", intraOpThreads = 6, useXnnpack = true),
    )

    suspend fun run(context: Context, base: TtsConfig, log: (String) -> Unit) {
        log("Kokoro benchmark STARTED (${variants(base).size} variants)")
        for ((name, cfg) in variants(base)) {
            if (!java.io.File(cfg.modelPath).isFile) {
                Log.w(TAG, "KOKOROBENCH variant=$name SKIPPED (missing ${cfg.modelPath})")
                continue
            }
            val engine = try {
                KokoroOnnxEngine.load(context, cfg)
            } catch (t: Throwable) {
                Log.e(TAG, "KOKOROBENCH variant=$name load FAILED", t)
                log("[ERROR] Kokoro bench $name load failed: ${t.message}")
                continue
            }
            try {
                engine.synthesize(SENTENCES[0], cfg.voice, cfg.speed)   // warm-up
                var totSynth = 0L; var totAudio = 0L
                for ((i, s) in SENTENCES.withIndex()) {
                    val t0 = System.nanoTime()
                    val r = engine.synthesize(s, cfg.voice, cfg.speed)
                    val synthMs = (System.nanoTime() - t0) / 1_000_000
                    val audioMs = r.pcm.size * 1000L / r.sampleRate
                    totSynth += synthMs; totAudio += audioMs
                    Log.i(TAG, "KOKOROBENCH variant=$name sentence=$i words=${s.split(" ").size} " +
                        "synthMs=$synthMs audioMs=$audioMs rtf=${"%.2f".format(synthMs.toDouble() / audioMs)}")
                }
                val rtf = totSynth.toDouble() / totAudio
                Log.i(TAG, "KOKOROBENCH variant=$name TOTAL synthMs=$totSynth audioMs=$totAudio " +
                    "rtf=${"%.2f".format(rtf)} loadMs=${engine.loadTimeMs}")
                log("Kokoro bench $name: RTF ${"%.2f".format(rtf)}")
            } catch (t: Throwable) {
                Log.e(TAG, "KOKOROBENCH variant=$name synth FAILED", t)
                log("[ERROR] Kokoro bench $name synth failed: ${t.message}")
            } finally {
                engine.close()
            }
        }
        Log.i(TAG, "KOKOROBENCH DONE")
        log("Kokoro benchmark DONE")
    }
}
