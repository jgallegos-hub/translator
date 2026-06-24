package com.travel2chicago.gemmapipeline.pipeline

import android.util.Log
import com.travel2chicago.gemmapipeline.audio.AudioEvent
import com.travel2chicago.gemmapipeline.audio.AudioEventBus
import com.travel2chicago.gemmapipeline.audio.AudioFormat
import com.travel2chicago.gemmapipeline.audio.VadState
import com.travel2chicago.gemmapipeline.chunker.AudioChunker
import com.travel2chicago.gemmapipeline.chunker.ChunkerConfig
import com.travel2chicago.gemmapipeline.vad.FrameReassembler
import com.travel2chicago.gemmapipeline.vad.SileroVadModel
import com.travel2chicago.gemmapipeline.vad.SileroVadProcessor
import com.travel2chicago.gemmapipeline.vad.VadConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch

private const val TAG = "VadChunkingPipeline"

/**
 * Orchestrator: subscribes to [AudioEvent.AudioData] on [bus], re-frames the
 * variable Oboe chunks into Silero-sized frames, runs VAD + chunker, and
 * publishes the resulting [AudioEvent.VadTransition] and
 * [AudioEvent.ChunkReady] back to the same bus.
 *
 * Reconfiguration: [vadConfig] and [chunkerConfig] are `@Volatile` references;
 * [updateVadConfig] / [updateChunkerConfig] swap a fresh state-machine in
 * place. State of in-flight chunks is discarded (the running collection is
 * not preserved across a reconfigure — the user is tuning, after all).
 *
 * Latest reads exposed for the UI:
 *   - [lastProbability]: raw Silero prob of the most recent frame
 *   - [vadState]: current state machine state
 *   - [isCollecting]: current chunker collecting flag
 */
class VadChunkingPipeline(
    private val bus: AudioEventBus,
    private val format: AudioFormat,
    private val model: SileroVadModel,
    initialVadConfig: VadConfig = VadConfig(),
    initialChunkerConfig: ChunkerConfig = ChunkerConfig(),
) {
    @Volatile private var vadConfig: VadConfig = initialVadConfig
    @Volatile private var chunkerConfig: ChunkerConfig = initialChunkerConfig
    @Volatile private var processor: SileroVadProcessor = SileroVadProcessor(initialVadConfig, format, model)
    @Volatile private var chunker: AudioChunker = AudioChunker(initialChunkerConfig, format)
    private val reassembler = FrameReassembler(frameSize = 512)

    @Volatile private var job: Job? = null

    /** Latest raw Silero probability from any frame — surfaced for the UI bar. */
    val lastProbability: Float get() = processor.lastProbability

    val vadState: VadState get() = processor.state
    val isCollecting: Boolean get() = chunker.isCollecting

    val isRunning: Boolean get() = job?.isActive == true

    /**
     * Sample-rate decimation factor. Silero v5 only supports 8 kHz and 16 kHz;
     * Oboe may open the USB device at the hardware native rate (48 kHz on most
     * modern phones). If the actual rate is an integer multiple of the target,
     * we mean-decimate inside [handleAudioData] before the reassembler so the
     * frames Silero sees correspond to the right time window.
     *
     * Default 1 = no decimation. Configure via [setCaptureSampleRate].
     */
    @Volatile private var decimationFactor: Int = 1
    @Volatile private var captureSampleRate: Int = format.sampleRate

    // ── Diagnostic counters ────────────────────────────────────────────────
    @Volatile private var audioDataEventsSeen: Long = 0
    @Volatile private var framesEmitted: Long = 0
    @Volatile private var vadInferences: Long = 0
    @Volatile private var loggedSampleStats: Int = 0  // first 5 frames get a per-frame stats line

    fun start(scope: CoroutineScope) {
        if (isRunning) {
            Log.w(TAG, "start: already running, ignoring")
            return
        }
        reassembler.reset()
        processor.reset()
        chunker.flush() // discard any leftover
        audioDataEventsSeen = 0
        framesEmitted = 0
        vadInferences = 0
        loggedSampleStats = 0

        job = scope.launch(Dispatchers.Default) {
            bus.emit(AudioEvent.EngineStatus(
                "Pipeline subscribed (target sr=${format.sampleRate} Hz, " +
                    "capture sr=$captureSampleRate Hz, decimation=${decimationFactor}x)"))
            Log.i(TAG, "Pipeline subscribed to bus")
            bus.events
                .filterIsInstance<AudioEvent.AudioData>()
                .collect { event -> handleAudioData(event) }
        }
        Log.i(TAG, "Pipeline started (vad=$vadConfig, chunker=$chunkerConfig)")
    }

    /**
     * Tell the pipeline what sample rate the capture stream was actually
     * opened at. If it does NOT match [AudioFormat.sampleRate], we set up
     * integer decimation (e.g. 48 kHz → 16 kHz = factor 3) so Silero sees
     * audio at the rate it was trained on.
     *
     * Call BEFORE [start] (or any time the capture device changes).
     */
    fun setCaptureSampleRate(actualSr: Int) {
        captureSampleRate = actualSr
        val target = format.sampleRate
        decimationFactor = when {
            actualSr <= 0 -> 1.also {
                Log.w(TAG, "setCaptureSampleRate: invalid actualSr=$actualSr, leaving decimation=1")
            }
            actualSr == target -> 1
            actualSr % target == 0 -> (actualSr / target).also {
                Log.i(TAG, "Sample rate mismatch detected: capture=$actualSr Hz, " +
                    "target=$target Hz → mean-decimate by factor $it")
                bus.emit(AudioEvent.EngineStatus(
                    "Sample rate mismatch: capture=$actualSr Hz → decimate ${it}x to $target Hz"))
            }
            else -> 1.also {
                val msg = "Sample rate mismatch: capture=$actualSr Hz, target=$target Hz — " +
                    "non-integer ratio, CANNOT decimate cleanly. VAD will see garbled audio."
                Log.e(TAG, msg)
                bus.emit(AudioEvent.EngineStatus("[ERROR] $msg"))
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        // Force-emit any in-flight chunk so the user can hear it.
        val tail = chunker.flush()
        if (tail != null) bus.emit(tail)
        Log.i(TAG, "Pipeline stopped")
    }

    fun updateVadConfig(newConfig: VadConfig) {
        vadConfig = newConfig
        processor = SileroVadProcessor(newConfig, format, model)
        Log.i(TAG, "VAD config updated: $newConfig")
    }

    fun updateChunkerConfig(newConfig: ChunkerConfig) {
        chunkerConfig = newConfig
        chunker = AudioChunker(newConfig, format)
        Log.i(TAG, "Chunker config updated: $newConfig")
    }

    private fun handleAudioData(event: AudioEvent.AudioData) {
        audioDataEventsSeen += 1
        if (audioDataEventsSeen == 1L) {
            bus.emit(AudioEvent.EngineStatus(
                "Pipeline received first AudioData (${event.samples.size} samples)"))
            Log.i(TAG, "First AudioData event received: ${event.samples.size} samples")
        }

        // Decimate to the target Silero rate if Oboe opened the device at a
        // higher native rate (e.g. 48 kHz → 16 kHz with factor 3 by mean of N).
        val pcm = if (decimationFactor > 1) decimateMean(event.samples, decimationFactor)
                  else event.samples

        // First 5 frames: log RMS + peak so we can SEE whether real signal is
        // arriving. p=0.000 with peak ≈ 0 means the mic is muted; p=0.000 with
        // real signal means the model is misconfigured (sample rate, format).
        if (loggedSampleStats < 5) {
            loggedSampleStats += 1
            var peak = 0
            var sumSq = 0.0
            for (s in pcm) {
                val a = if (s.toInt() == Short.MIN_VALUE.toInt()) Short.MAX_VALUE.toInt() else kotlin.math.abs(s.toInt())
                if (a > peak) peak = a
                val v = s.toDouble()
                sumSq += v * v
            }
            val rms = if (pcm.isNotEmpty()) kotlin.math.sqrt(sumSq / pcm.size) else 0.0
            Log.i(TAG, "PCM frame stats #$loggedSampleStats: " +
                "size=${pcm.size}, peak=$peak, rms=${"%.1f".format(rms)} " +
                "(decimation=${decimationFactor}x)")
            if (loggedSampleStats == 1) {
                bus.emit(AudioEvent.EngineStatus(
                    "First PCM to VAD: peak=$peak rms=${"%.1f".format(rms)} " +
                        "(should be >100 when speaking)"))
            }
        }

        val frames = reassembler.feed(pcm)
        if (frames.isEmpty()) return

        framesEmitted += frames.size
        if (framesEmitted - frames.size == 0L) {
            // Just crossed from 0 → first frames emitted.
            bus.emit(AudioEvent.EngineStatus(
                "Reassembler emitted first ${frames.size} frame(s) of 512 samples"))
            Log.i(TAG, "Reassembler first emission: ${frames.size} frame(s)")
        }

        val currentProcessor = processor
        val currentChunker = chunker

        for (frame in frames) {
            val transition = try {
                currentProcessor.processFrame(frame, event.timestampNs)
            } catch (t: Throwable) {
                Log.e(TAG, "VAD inference failed", t)
                bus.emit(AudioEvent.EngineStatus("VAD inference error: ${t.message}"))
                return
            }
            vadInferences += 1
            // First inference: surface to the UI so we know the model is alive.
            if (vadInferences == 1L) {
                bus.emit(AudioEvent.EngineStatus(
                    "VAD first inference: p=${"%.3f".format(currentProcessor.lastProbability)}"))
                Log.i(TAG, "VAD first inference: p=${currentProcessor.lastProbability}")
            }
            // Periodic heartbeat to logcat every 50 frames (~1.6 s of audio).
            if (vadInferences % 50L == 0L) {
                Log.i(TAG, "VAD heartbeat: inferences=$vadInferences " +
                    "p=${"%.3f".format(currentProcessor.lastProbability)} " +
                    "state=${currentProcessor.state} collecting=${currentChunker.isCollecting}")
            }
            if (transition != null) {
                bus.emit(transition)
            }

            val chunk = currentChunker.feed(frame, currentProcessor.state)
            if (chunk != null) {
                bus.emit(chunk)
            }
        }
    }

    /**
     * Mean-of-N decimation: averages every [factor] consecutive samples into
     * one output sample. Crude low-pass; good enough for VAD-grade audio
     * because Silero is robust to mild aliasing. Trailing samples that don't
     * fill a full bucket are dropped — at 25 ms drain cadence the next event
     * picks them up via the reassembler tail.
     */
    private fun decimateMean(samples: ShortArray, factor: Int): ShortArray {
        if (factor <= 1) return samples
        val outSize = samples.size / factor
        if (outSize == 0) return ShortArray(0)
        val out = ShortArray(outSize)
        var j = 0
        while (j < outSize) {
            var sum = 0
            val base = j * factor
            for (k in 0 until factor) sum += samples[base + k]
            out[j] = (sum / factor).toShort()
            j += 1
        }
        return out
    }
}
