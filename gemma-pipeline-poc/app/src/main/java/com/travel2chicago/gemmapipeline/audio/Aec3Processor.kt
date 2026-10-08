package com.travel2chicago.gemmapipeline.audio

import android.util.Log
import cn.enaium.webrtc.aec3.Aec3AudioBuffer
import cn.enaium.webrtc.aec3.Aec3Config
import cn.enaium.webrtc.aec3.Aec3EchoControl
import cn.enaium.webrtc.aec3.Aec3Environment
import cn.enaium.webrtc.aec3.Aec3Factory
import cn.enaium.webrtc.aec3.createAec3AudioBuffer
import cn.enaium.webrtc.aec3.createAec3Config
import cn.enaium.webrtc.aec3.createAec3EchoControl
import cn.enaium.webrtc.aec3.createAec3Environment
import cn.enaium.webrtc.aec3.createAec3FactoryWithConfig
import java.util.concurrent.atomic.AtomicLong

private const val TAG = "Aec3Processor"

/** Common surface for the software echo cancellers wired into the player
 *  (far-end pump) and the VAD pipeline (near-end filter). */
interface SoftwareEchoCanceller : AutoCloseable {
    val isInitialized: Boolean
    val totalFarendFrames: Long
    val totalNearendFrames: Long
    fun bufferFarend(pcm: ShortArray, inputSampleRate: Int)
    /** Echo-cancelled near-end at 16 kHz; may return fewer samples than given
     *  (10 ms framing), the remainder is kept for the next call. */
    fun process(nearEnd: ShortArray): ShortArray
    fun reset()
}

/**
 * Far-end samples placed on the capture (mic) timeline — the same placement
 * rule as [EchoDatasetRecorder], which is what the offline AEC3 evaluation
 * was run on: a block starts at `max(captured samples so far, end of previous
 * block)`, i.e. contiguous while the AudioTrack still has queued audio, "now"
 * after it drained. Reads outside written blocks return silence.
 *
 * Pure Kotlin (no JNI) so the alignment is unit-testable.
 */
class RenderTimeline(private val capacity: Int = 16_000 * 30) {
    private val ring = ShortArray(capacity)
    /** Absolute (capture-timeline) index one past the last written sample. */
    var renderEnd = 0L
        private set

    /** Places [pcm16k] at `max(captureNow, renderEnd)`; returns the start index. */
    fun append(pcm16k: ShortArray, captureNow: Long): Long {
        val start = maxOf(captureNow, renderEnd)
        var i = renderEnd
        while (i < start) { ring[(i % capacity).toInt()] = 0; i++ }   // gap = silence
        for (k in pcm16k.indices) ring[((start + k) % capacity).toInt()] = pcm16k[k]
        renderEnd = start + pcm16k.size
        return start
    }

    /** Fills [out] with samples `[from, from + out.size)`, zeros where absent. */
    fun read(from: Long, out: FloatArray) {
        for (k in out.indices) {
            val idx = from + k
            out[k] = if (idx < 0 || idx >= renderEnd || idx < renderEnd - capacity) 0f
                     else ring[(idx % capacity).toInt()].toFloat()
        }
    }

    fun clear() { renderEnd = 0L }
}

/**
 * WebRTC AEC3 (via `webrtc-aec3-kmp`) on the mic path.
 *
 * Why AEC3 and not AECM: on the ModMic + JBL Go 4 (A2DP) setup the
 * speaker→mic delay is ~504 ms (measured on device, stable once the BT
 * keep-alive is on). AECM searches a tiny window around a fixed hint and never
 * converged. AEC3 has a continuous delay estimator; offline on the device
 * dataset, with the reference pre-delayed by 300–500 ms it always locked onto
 * 504 ms total, median echo RMS 5 466 → 101 (~-35 dB), voice-only unchanged,
 * synthetic double-talk voice loss ≤ 2 dB (`tools/aec3-offline/`).
 *
 * Timing: the far-end reference is pre-delayed by [preDelayMs] on the capture
 * timeline; AEC3 estimates the residual (~100–200 ms). The reference must
 * LEAD its echo slightly — a reference that arrives after its echo pins the
 * estimator near 0 ms and cancels nothing.
 */
class Aec3Processor(
    private val preDelayMs: Int = DEFAULT_PRE_DELAY_MS,
) : SoftwareEchoCanceller {

    private val lock = Any()
    private var config: Aec3Config? = null
    private var env: Aec3Environment? = null
    private var factory: Aec3Factory? = null
    private var echo: Aec3EchoControl? = null
    private var renderBuf: Aec3AudioBuffer? = null
    private var captureBuf: Aec3AudioBuffer? = null

    private val timeline = RenderTimeline()
    private var captureCount = 0L
    private val nearPending = ArrayDeque<Short>()
    private val renderFrame = FloatArray(FRAME)
    private val captureFrame = FloatArray(FRAME)

    // Diagnostics (reset every metrics log)
    private var diagFarendSamples = 0L
    private var diagRenderSumSq = 0.0
    private var diagCaptureSumSq = 0.0
    private var diagNanos = 0L

    private val farendFrames = AtomicLong(0)
    private val nearendFrames = AtomicLong(0)
    @Volatile var lastErle: Double = 0.0; private set
    @Volatile var lastDelayMs: Int = 0; private set

    @Volatile override var isInitialized: Boolean = false
        private set
    override val totalFarendFrames: Long get() = farendFrames.get()
    override val totalNearendFrames: Long get() = nearendFrames.get()

    fun initialize(): Unit = synchronized(lock) {
        if (isInitialized) return@synchronized
        val cfg = createAec3Config().apply {
            setFilterInitialStateSeconds(0.5f)
            setFilterConservativeInitialPhase(false)
        }
        val e = createAec3Environment()
        val f = createAec3FactoryWithConfig(cfg)
        config = cfg; env = e; factory = f
        echo = createAec3EchoControl(f, e, SAMPLE_RATE, 1, 1)
        renderBuf = createAec3AudioBuffer(SAMPLE_RATE, 1)
        captureBuf = createAec3AudioBuffer(SAMPLE_RATE, 1)
        isInitialized = true
        Log.i(TAG, "AEC3 initialised (16 kHz mono, preDelay=${preDelayMs} ms)")
    }

    override fun bufferFarend(pcm: ShortArray, inputSampleRate: Int) {
        if (!isInitialized || pcm.isEmpty()) return
        val pcm16 = EchoDatasetRecorder.resampleLinear(pcm, inputSampleRate, SAMPLE_RATE)
        synchronized(lock) {
            timeline.append(pcm16, captureCount)
            diagFarendSamples += pcm16.size
        }
    }

    override fun process(nearEnd: ShortArray): ShortArray = synchronized(lock) {
        val ec = echo ?: return nearEnd
        val rb = renderBuf ?: return nearEnd
        val cb = captureBuf ?: return nearEnd
        for (s in nearEnd) nearPending.addLast(s)
        val frames = nearPending.size / FRAME
        if (frames == 0) return ShortArray(0)
        val out = ShortArray(frames * FRAME)
        val pre = preDelayMs.toLong() * SAMPLE_RATE / 1000
        for (f in 0 until frames) {
            for (k in 0 until FRAME) captureFrame[k] = nearPending.removeFirst().toFloat()
            timeline.read(captureCount - pre, renderFrame)
            for (k in 0 until FRAME) {
                diagRenderSumSq += renderFrame[k] * renderFrame[k]
                diagCaptureSumSq += captureFrame[k] * captureFrame[k]
            }
            val t0 = System.nanoTime()
            try {
                rb.writeChannel(0, renderFrame); ec.analyzeRender(rb)
                cb.writeChannel(0, captureFrame); ec.analyzeCapture(cb); ec.processCapture(cb, false)
                val cleaned = cb.readChannel(0)
                for (k in 0 until FRAME) {
                    out[f * FRAME + k] = cleaned[k].toInt().coerceIn(-32768, 32767).toShort()
                }
            } catch (t: Throwable) {
                Log.w(TAG, "AEC3 frame failed, passing mic through: ${t.message}")
                for (k in 0 until FRAME) out[f * FRAME + k] = captureFrame[k].toInt().toShort()
            }
            diagNanos += System.nanoTime() - t0
            captureCount += FRAME
            farendFrames.incrementAndGet()
            if (nearendFrames.incrementAndGet() % METRICS_EVERY_FRAMES == 0L) {
                runCatching { ec.getMetrics() }.getOrNull()?.let { m ->
                    lastErle = m.echoReturnLossEnhancement
                    lastDelayMs = m.delayMs
                    Log.i(TAG, "AEC3 metrics: ERL=${"%.1f".format(m.echoReturnLoss)} " +
                        "ERLE=${"%.1f".format(m.echoReturnLossEnhancement)} " +
                        "delay=${m.delayMs} ms (+pre $preDelayMs ms) | " +
                        "farendIn=${diagFarendSamples} smp renderRms=${"%.0f".format(Math.sqrt(diagRenderSumSq / (METRICS_EVERY_FRAMES * FRAME)))} " +
                        "micRms=${"%.0f".format(Math.sqrt(diagCaptureSumSq / (METRICS_EVERY_FRAMES * FRAME)))} " +
                        "capture=$captureCount renderEnd=${timeline.renderEnd} " +
                        "cpu=${"%.2f".format(diagNanos / 1e6 / METRICS_EVERY_FRAMES)} ms/frame")
                    diagFarendSamples = 0; diagRenderSumSq = 0.0; diagCaptureSumSq = 0.0; diagNanos = 0
                }
            }
        }
        out
    }

    override fun reset() {
        synchronized(lock) {
            nearPending.clear()
            timeline.clear()
            captureCount = 0L
        }
    }

    override fun close() {
        synchronized(lock) {
            isInitialized = false
            runCatching { renderBuf?.close() }; runCatching { captureBuf?.close() }
            runCatching { echo?.close() }; runCatching { factory?.close() }
            runCatching { env?.close() }; runCatching { config?.close() }
            renderBuf = null; captureBuf = null; echo = null; factory = null; env = null; config = null
            Log.i(TAG, "AEC3 closed")
        }
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val FRAME = 160
        /** Offline sweep: any 300–500 ms works (total locks at ~504 ms); 380
         *  leaves ~120 ms of margin on both sides for delay drift. */
        const val DEFAULT_PRE_DELAY_MS = 380
        private const val METRICS_EVERY_FRAMES = 500L  // every 5 s
    }
}
