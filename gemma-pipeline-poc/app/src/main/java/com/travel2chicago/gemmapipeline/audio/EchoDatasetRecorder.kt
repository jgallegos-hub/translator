package com.travel2chicago.gemmapipeline.audio

import android.util.Log
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val TAG = "EchoDatasetRecorder"

/**
 * Experimento E0/E1 (research eco-latencia 2026-10) — graba un dataset de eco
 * para evaluar offline AEC3, gate por correlación y embeddings de hablante.
 *
 * Escribe en [dir]:
 *   - `mic.wav`  — captura del mic a 16 kHz mono, ANTES de AEC software / VAD
 *     (lo que el pipeline recibe tras la decimación).
 *   - `ref.wav`  — el PCM exacto que se entregó al `AudioTrack` (Kokoro,
 *     chirps), remuestreado a 16 kHz y colocado en la línea de tiempo del mic.
 *   - `events.csv` — `kind,mic_sample,len_samples,note` por cada bloque de
 *     referencia y cada marcador (p. ej. chirps).
 *
 * Alineación de la referencia: cada `play()` se coloca en
 * `max(muestras de mic grabadas hasta ahora, fin del bloque anterior)`. Si el
 * `AudioTrack` todavía tiene audio en cola, el bloque nuevo queda contiguo al
 * anterior (se reproduce a continuación); si se vació, empieza "ahora". Así
 * `ref.wav` sigue la línea de tiempo de reproducción salvo por un offset
 * casi constante (latencia HAL + Bluetooth) — exactamente lo que E1 mide con
 * los chirps.
 *
 * Thread-safe: [onMic] llega desde el colector del pipeline y [onRef] desde
 * la corrutina del player.
 */
class EchoDatasetRecorder(val dir: File) {

    private val lock = Any()
    private val mic = WavStreamWriter(File(dir, "mic.wav"), SAMPLE_RATE)
    private val ref = WavStreamWriter(File(dir, "ref.wav"), SAMPLE_RATE)
    private val events = File(dir, "events.csv").bufferedWriter()
    /** Salida del AEC software (si está activo), alineada con mic.wav. */
    private val aecOut = WavStreamWriter(File(dir, "aec_out.wav"), SAMPLE_RATE)
    private var aecOutSamples = 0L

    private var micSamples = 0L
    private var refSamples = 0L
    @Volatile var isClosed = false
        private set

    init {
        events.write("kind,mic_sample,len_samples,note\n")
        Log.i(TAG, "Recording echo dataset to ${dir.absolutePath}")
    }

    val micSeconds: Double get() = synchronized(lock) { micSamples.toDouble() / SAMPLE_RATE }

    /** Mic PCM already at 16 kHz (post-decimation, pre-AEC). */
    fun onMic(pcm16k: ShortArray) {
        synchronized(lock) {
            if (isClosed || pcm16k.isEmpty()) return
            mic.write(pcm16k)
            micSamples += pcm16k.size
        }
    }

    /** Output of the software AEC (16 kHz). Padded so it stays aligned with mic.wav
     *  (the AEC's 10 ms framing delays its output by < 1 frame). */
    fun onAecOut(pcm16k: ShortArray) {
        synchronized(lock) {
            if (isClosed || pcm16k.isEmpty()) return
            aecOut.write(pcm16k)
            aecOutSamples += pcm16k.size
        }
    }

    /** PCM handed to the AudioTrack at [sampleRate] Hz. */
    fun onRef(pcm: ShortArray, sampleRate: Int) {
        synchronized(lock) {
            if (isClosed || pcm.isEmpty()) return
            val pcm16 = resampleLinear(pcm, sampleRate, SAMPLE_RATE)
            val start = maxOf(micSamples, refSamples)
            padRefTo(start)
            ref.write(pcm16)
            refSamples = start + pcm16.size
            events.write("ref,$start,${pcm16.size},\n")
        }
    }

    /** Free-form marker at the current mic position (e.g. "chirp"). */
    fun mark(note: String) {
        synchronized(lock) {
            if (isClosed) return
            events.write("mark,$micSamples,0,$note\n")
        }
    }

    fun close() {
        synchronized(lock) {
            if (isClosed) return
            isClosed = true
            padRefTo(micSamples)
            runCatching { mic.close() }
            runCatching { ref.close() }
            runCatching { aecOut.close() }
            runCatching { events.close() }
            Log.i(TAG, "Echo dataset closed: ${"%.1f".format(micSamples.toDouble() / SAMPLE_RATE)} s " +
                "in ${dir.absolutePath}")
        }
    }

    private fun padRefTo(target: Long) {
        var gap = target - refSamples
        if (gap <= 0) return
        val zeros = ShortArray(minOf(gap, SAMPLE_RATE.toLong()).toInt())
        while (gap > 0) {
            val n = minOf(gap, zeros.size.toLong()).toInt()
            ref.write(zeros, n)
            gap -= n
        }
        refSamples = target
    }

    companion object {
        const val SAMPLE_RATE = 16_000

        /** Linear-interpolation resampler — same approach as AecProcessor. */
        fun resampleLinear(pcm: ShortArray, fromRate: Int, toRate: Int): ShortArray {
            if (fromRate == toRate) return pcm
            val outLen = (pcm.size.toLong() * toRate / fromRate).toInt()
            val out = ShortArray(outLen)
            val step = fromRate.toDouble() / toRate
            for (i in 0 until outLen) {
                val pos = i * step
                val i0 = pos.toInt().coerceAtMost(pcm.size - 1)
                val i1 = (i0 + 1).coerceAtMost(pcm.size - 1)
                val frac = pos - i0
                out[i] = (pcm[i0] * (1 - frac) + pcm[i1] * frac).toInt().toShort()
            }
            return out
        }

        /** Linear chirp (log-friendly for GCC-PHAT) at [sampleRate], ~-6 dBFS. */
        fun chirp(sampleRate: Int, durationMs: Int = 300, f0: Double = 400.0, f1: Double = 4_000.0): ShortArray {
            val n = sampleRate * durationMs / 1000
            val t1 = durationMs / 1000.0
            val k = (f1 - f0) / t1
            return ShortArray(n) { i ->
                val t = i.toDouble() / sampleRate
                // Short raised-cosine fade (5 ms) to avoid clicks.
                val fadeN = sampleRate / 200
                val env = when {
                    i < fadeN -> 0.5 * (1 - kotlin.math.cos(Math.PI * i / fadeN))
                    i > n - fadeN -> 0.5 * (1 - kotlin.math.cos(Math.PI * (n - i) / fadeN))
                    else -> 1.0
                }
                (16_000 * env * kotlin.math.sin(2 * Math.PI * (f0 * t + 0.5 * k * t * t))).toInt().toShort()
            }
        }
    }
}

/** Minimal streaming 16-bit mono WAV writer; header is patched on [close]. */
private class WavStreamWriter(private val file: File, private val sampleRate: Int) {
    private val out = BufferedOutputStream(FileOutputStream(file), 64 * 1024)
    private var dataBytes = 0L
    private var buf = ByteBuffer.allocate(0).order(ByteOrder.LITTLE_ENDIAN)

    init { out.write(ByteArray(44)) }

    fun write(pcm: ShortArray, count: Int = pcm.size) {
        val bytes = count * 2
        if (buf.capacity() < bytes) buf = ByteBuffer.allocate(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buf.clear()
        for (i in 0 until count) buf.putShort(pcm[i])
        out.write(buf.array(), 0, bytes)
        dataBytes += bytes
    }

    fun close() {
        out.flush()
        out.close()
        RandomAccessFile(file, "rw").use { raf ->
            val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            h.put("RIFF".toByteArray()); h.putInt((36 + dataBytes).toInt())
            h.put("WAVE".toByteArray()); h.put("fmt ".toByteArray())
            h.putInt(16); h.putShort(1); h.putShort(1)
            h.putInt(sampleRate); h.putInt(sampleRate * 2); h.putShort(2); h.putShort(16)
            h.put("data".toByteArray()); h.putInt(dataBytes.toInt())
            raf.seek(0); raf.write(h.array())
        }
    }
}
