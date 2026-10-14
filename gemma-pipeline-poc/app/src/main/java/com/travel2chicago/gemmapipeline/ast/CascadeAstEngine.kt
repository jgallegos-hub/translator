package com.travel2chicago.gemmapipeline.ast

import android.util.Log
import com.travel2chicago.gemmapipeline.asr.MoonshineAsr
import com.travel2chicago.gemmapipeline.mt.TextTranslator
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val TAG = "CascadeAstEngine"

/**
 * ASR -> MT cascade behind the [GemmaAstEngine] interface, so [AstChunkRouter]
 * (queue, RMS gate, sentence streaming, meta/echo filters) and the TTS path
 * stay unchanged. The router's Gemma prompt is ignored.
 *
 * Per chunk: Moonshine transcribes the PCM, lines under [lineRmsThreshold]
 * (echo residue) are dropped, and the remaining Spanish text goes to
 * [translator]. An empty transcript yields an empty reply, which the router
 * treats as "nothing to say" - silence is never sent to the translator.
 */
class CascadeAstEngine(
    private val asr: MoonshineAsr,
    private val translator: TextTranslator,
    private val lineRmsThreshold: Double,
) : GemmaAstEngine {

    override val isLoaded: Boolean = true
    override val backendUsed: String = "Moonshine+${translator.name}"
    override val loadTimeMs: Long = 0

    override fun translate(wavBytes: ByteArray, prompt: String, audioAfterText: Boolean): AstResult =
        kotlinx.coroutines.runBlocking { translateStreaming(wavBytes, prompt, audioAfterText) {} }

    override suspend fun translateStreaming(
        wavBytes: ByteArray,
        prompt: String,
        audioAfterText: Boolean,
        onToken: suspend (token: String) -> Unit,
    ): AstResult {
        val t0 = System.nanoTime()
        val samples = pcmFromWav(wavBytes)
        val lines = asr.transcribe(samples)
        val asrMs = (System.nanoTime() - t0) / 1_000_000
        val kept = lines.filter { it.rms >= lineRmsThreshold }
        for (l in lines - kept.toSet()) {
            Log.i(TAG, "CASCADE drop echo-residue line rms=${"%.0f".format(l.rms)} text='${l.text}'")
        }
        val spanish = kept.joinToString(" ") { it.text }.trim()
        if (spanish.isEmpty()) {
            Log.i(TAG, "CASCADE asrMs=$asrMs empty transcript (lines=${lines.size}) — nothing to translate")
            return AstResult("", asrMs, backendUsed)
        }
        val (english, mtFirstMs) = translator.translate(spanish, onToken)
        val totalMs = (System.nanoTime() - t0) / 1_000_000
        Log.i(
            TAG,
            "CASCADE asrMs=$asrMs mtFirstMs=$mtFirstMs totalMs=$totalMs " +
                "lineRms=${kept.joinToString(",") { "%.0f".format(it.rms) }} es='$spanish' en='$english'",
        )
        return AstResult(english, totalMs, backendUsed)
    }

    /** ASR and translator are owned by the ViewModel (reused across router restarts). */
    override fun close() = Unit

    private fun pcmFromWav(wav: ByteArray): ShortArray {
        // WavBuilder writes a canonical 44-byte header; find "data" to be safe.
        val bb = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 12
        while (pos + 8 <= wav.size) {
            val id = String(wav, pos, 4, Charsets.US_ASCII)
            val size = bb.getInt(pos + 4)
            if (id == "data") {
                val n = minOf(size, wav.size - pos - 8) / 2
                return ShortArray(n) { bb.getShort(pos + 8 + it * 2) }
            }
            pos += 8 + size
        }
        error("no data chunk in WAV")
    }
}
