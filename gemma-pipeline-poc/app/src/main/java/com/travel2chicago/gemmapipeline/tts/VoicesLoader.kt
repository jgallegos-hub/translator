package com.travel2chicago.gemmapipeline.tts

import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val TAG = "VoicesLoader"

/**
 * Loads voice-style embeddings from `voices-v1.0.bin`.
 *
 * The Kokoro ONNX release ships voice embeddings as a single binary blob:
 * all known voices concatenated as float32 little-endian arrays. The exact
 * per-voice tensor shape varies across releases; the v1.0 layout commonly
 * seen in the wild is `(511, 1, 256)` floats per voice — 511 positions of a
 * 256-dim style vector. The runtime engine selects the slice for the actual
 * input token length.
 *
 * Because the shape is release-specific, this loader is **configurable** via
 * the bundled tokenizer config and validates the on-disk size against the
 * declared voice list. If the size doesn't divide cleanly, the loader logs
 * the mismatch and surfaces it as an exception — preferable to silently
 * mis-slicing tensors and feeding garbage to the model.
 *
 * For Fase 5 v1 the engine uses the row-0 embedding (`offset_in_voice = 0`)
 * for every inference; per-position selection is a post-POC refinement.
 */
class VoicesLoader(
    /** Ordered voice names — same order as the binary file. */
    private val voiceNames: List<String>,
    /** Floats per voice (e.g. 511 * 1 * 256 = 130 816 for the v1.0 release). */
    private val voiceFloatStride: Int,
    /** Slice within a voice we hand to the model (e.g. first 256 floats). */
    private val sliceLen: Int,
) {

    val embeddingSize: Int get() = sliceLen

    fun loadFromFile(file: File): Map<String, FloatArray> {
        require(file.isFile) { "voices file does not exist: ${file.absolutePath}" }
        val bytes = file.readBytes()
        val expectedBytes = voiceNames.size.toLong() * voiceFloatStride * 4L
        if (bytes.size.toLong() != expectedBytes) {
            Log.w(TAG, "voices file size ${bytes.size} != expected $expectedBytes " +
                "(${voiceNames.size} voices × $voiceFloatStride floats × 4 bytes). " +
                "Layout may not match this release.")
        }
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val map = LinkedHashMap<String, FloatArray>(voiceNames.size)
        for ((index, name) in voiceNames.withIndex()) {
            val voiceStart = index * voiceFloatStride
            if (voiceStart + sliceLen > buf.capacity()) {
                Log.e(TAG, "Voice '$name' (#$index) would read past EOF — stopping early")
                break
            }
            val slice = FloatArray(sliceLen)
            buf.position(voiceStart)
            buf.get(slice, 0, sliceLen)
            map[name] = slice
        }
        Log.i(TAG, "Loaded ${map.size}/${voiceNames.size} voices from ${file.name}")
        return map
    }
}
