package com.travel2chicago.gemmapipeline.tts

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipInputStream

private const val TAG = "VoicesLoader"

internal const val KOKORO_EMBEDDING_DIM = 256
internal const val KOKORO_MAX_POSITIONS = 511

/**
 * In-memory voice-style table for Kokoro-82M v1.0.
 *
 * Each voice in the published `voices-v1.0.bin` is a `[511, 1, 256]` float32
 * array (511 positions of a 256-dim style vector). At inference time the
 * Python reference picks `voice[len(tokens)]` (a `[1, 256]` slice) per
 * sentence, where `len(tokens)` is the **unwrapped** phoneme-token count
 * (before the `[0, ..., 0]` PAD wrap). See `_create_audio` in
 * `thewh1teagle/kokoro-onnx` (`src/kokoro_onnx/__init__.py`).
 *
 * This class keeps the full flat array per voice and provides [styleFor] to
 * carve out the per-sentence vector. It is process-singleton-friendly: load
 * once at app start, reuse for every inference.
 */
class VoiceStyles internal constructor(
    private val full: Map<String, FloatArray>,
    val embeddingDim: Int = KOKORO_EMBEDDING_DIM,
    val maxPositions: Int = KOKORO_MAX_POSITIONS,
) {
    val availableVoices: Set<String> get() = full.keys

    fun has(name: String): Boolean = full.containsKey(name)

    /**
     * Returns the 256-float style vector for [name] indexed by [tokenCount].
     *
     * [tokenCount] is the raw (unwrapped) phoneme-token count — the same
     * value that becomes the length of `tokens` before the engine wraps it
     * with PAD on both ends. Values are clamped to `[0, maxPositions-1]` so
     * a too-long input falls back to the last valid slot (Python does the
     * same after truncating phonemes to MAX_PHONEME_LENGTH = 510).
     */
    fun styleFor(name: String, tokenCount: Int): FloatArray {
        val arr = full[name]
            ?: throw IllegalArgumentException(
                "Unknown voice '$name'. Available: $availableVoices",
            )
        val idx = tokenCount.coerceIn(0, maxPositions - 1)
        val offset = idx * embeddingDim
        check(offset + embeddingDim <= arr.size) {
            "Voice '$name' is too small: ${arr.size} floats, need >= ${offset + embeddingDim}"
        }
        val out = FloatArray(embeddingDim)
        System.arraycopy(arr, offset, out, 0, embeddingDim)
        return out
    }
}

/**
 * Parses the Kokoro `voices-v1.0.bin` (an NPZ archive — a ZIP of `.npy`
 * float32 arrays, one per voice). Voice key = entry filename without the
 * `.npy` extension. The bundled file ships ~26 voices including `af_heart`,
 * `af_bella`, `am_michael`, `bf_emma`, `bm_george`, etc.
 *
 * Why NPZ (not raw concatenated floats):
 * - The published file IS the output of `np.savez` / equivalent — Python
 *   loads it with `np.load(voices_path)` and indexes by voice name.
 * - There is no canonical voice ordering across releases; the ZIP entries
 *   carry the names directly, so we don't have to hard-code anything.
 */
object VoicesNpz {
    fun load(file: File): VoiceStyles {
        require(file.isFile) { "voices file does not exist: ${file.absolutePath}" }
        val voices = LinkedHashMap<String, FloatArray>()
        FileInputStream(file).use { fis ->
            ZipInputStream(fis).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name
                    if (!name.endsWith(".npy", ignoreCase = true)) {
                        Log.w(TAG, "skipping non-.npy entry: $name")
                        zip.closeEntry()
                        continue
                    }
                    val voiceKey = name.substringAfterLast('/')
                        .removeSuffix(".npy")
                        .removeSuffix(".NPY")
                    val payload = ByteArrayOutputStream().use { baos ->
                        zip.copyTo(baos)
                        baos.toByteArray()
                    }
                    zip.closeEntry()
                    val parsed = parseNpyFloat32(payload, voiceKey)
                    if (parsed != null) voices[voiceKey] = parsed
                }
            }
        }
        check(voices.isNotEmpty()) {
            "No .npy entries found in ${file.name} — is this a valid NPZ archive?"
        }
        Log.i(TAG, "Loaded ${voices.size} voices from ${file.name}: ${voices.keys}")
        return VoiceStyles(voices)
    }

    /**
     * Parse a single `.npy` payload (header + raw data) into a flat
     * [FloatArray]. Supports v1.0 / v2.0 / v3.0 headers, C-order, little-
     * endian float32 only — that's the only shape Kokoro voices use.
     *
     * Returns `null` (and logs a warning) on any unsupported variant so a
     * single bad entry doesn't break the rest of the archive.
     */
    internal fun parseNpyFloat32(bytes: ByteArray, label: String): FloatArray? {
        if (bytes.size < 10) {
            Log.w(TAG, "$label: payload too small (${bytes.size} bytes)")
            return null
        }
        val magicOk = bytes[0] == 0x93.toByte() &&
            bytes[1] == 'N'.code.toByte() && bytes[2] == 'U'.code.toByte() &&
            bytes[3] == 'M'.code.toByte() && bytes[4] == 'P'.code.toByte() &&
            bytes[5] == 'Y'.code.toByte()
        if (!magicOk) {
            Log.w(TAG, "$label: bad NPY magic bytes")
            return null
        }
        val major = bytes[6].toInt() and 0xff
        val headerLenSize = if (major >= 2) 4 else 2
        if (bytes.size < 8 + headerLenSize) return null
        val headerLen: Int = when (headerLenSize) {
            2 -> (bytes[8].toInt() and 0xff) or
                ((bytes[9].toInt() and 0xff) shl 8)
            else -> (bytes[8].toInt() and 0xff) or
                ((bytes[9].toInt() and 0xff) shl 8) or
                ((bytes[10].toInt() and 0xff) shl 16) or
                ((bytes[11].toInt() and 0xff) shl 24)
        }
        val headerStart = 8 + headerLenSize
        val dataStart = headerStart + headerLen
        if (dataStart > bytes.size) {
            Log.w(TAG, "$label: NPY header overruns payload (dataStart=$dataStart, size=${bytes.size})")
            return null
        }
        val header = String(bytes, headerStart, headerLen, Charsets.US_ASCII)
        if (!header.contains("'<f4'") && !header.contains("\"<f4\"")) {
            Log.w(TAG, "$label: only float32 LE ('<f4') is supported. header=$header")
            return null
        }
        if (header.contains("'fortran_order': True") ||
            header.contains("\"fortran_order\": true")
        ) {
            Log.w(TAG, "$label: fortran_order=True is not supported")
            return null
        }
        val dataBytes = bytes.size - dataStart
        if (dataBytes % 4 != 0) {
            Log.w(TAG, "$label: data length $dataBytes is not divisible by 4")
            return null
        }
        val nFloats = dataBytes / 4
        val out = FloatArray(nFloats)
        ByteBuffer.wrap(bytes, dataStart, dataBytes)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asFloatBuffer()
            .get(out)
        val expected = KOKORO_MAX_POSITIONS * KOKORO_EMBEDDING_DIM
        if (nFloats != expected) {
            Log.w(TAG, "$label: unexpected float count $nFloats (expected $expected for [511,1,256])")
        }
        return out
    }
}
