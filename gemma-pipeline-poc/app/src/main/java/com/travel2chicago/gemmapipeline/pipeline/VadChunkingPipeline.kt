package com.travel2chicago.gemmapipeline.pipeline

import android.util.Log
import com.travel2chicago.gemmapipeline.audio.SoftwareEchoCanceller
import com.travel2chicago.gemmapipeline.audio.EchoDatasetRecorder
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
import java.util.concurrent.atomic.AtomicBoolean

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
    @Volatile private var mutedFramesDropped: Long = 0
    @Volatile private var wasMuted: Boolean = false

    /**
     * Shared flag driven by [com.travel2chicago.gemmapipeline.tts.TtsAudioPlayer].
     * While `true` AND [fullDuplexMode] is `false`, [handleAudioData] drops
     * every incoming frame — the mic is likely picking up the TTS output
     * through the speaker and re-feeding it into Gemma. Reset to `null` in
     * [stop] so a fresh [start] can rebind.
     */
    @Volatile private var ttsPlaying: AtomicBoolean? = null

    /**
     * When `true`, the mic is NEVER muted while TTS is speaking — the chunker
     * keeps collecting and the VAD keeps running. Used for OpenAI-style
     * continuous / barge-in conversation. See [com.travel2chicago.gemmapipeline.ast.AstConfig.fullDuplexMode]
     * for the physical caveats (needs a directional mic or isolated speaker
     * — omnidirectional-mic + shared-room-speaker will feedback-loop the
     * TTS output back into Gemma as new "speech").
     *
     * Mutate via [setFullDuplexMode] rather than assigning directly — the
     * setter also handles the mid-mute edge case (force un-mute if we were
     * already dropping frames when the flag flipped ON).
     */
    @Volatile private var fullDuplexModeInternal: Boolean = false

    /** Snapshot for read paths (handleAudioData). Kept as a public read-only
     *  property to preserve existing callers; write via [setFullDuplexMode]. */
    val fullDuplexMode: Boolean get() = fullDuplexModeInternal

    /**
     * Optional software echo canceller. When non-null AND
     * [AecProcessor.isInitialized] is true, [handleAudioData] pipes the
     * post-decimation PCM through [AecProcessor.process] BEFORE the
     * reassembler — Silero + chunker see the AEC-cleaned signal. Null
     * (default) preserves every pre-AEC path byte-for-byte.
     */
    @Volatile private var aecProcessor: SoftwareEchoCanceller? = null

    /** Set/clear the software AEC processor. Safe to call while the
     *  pipeline is running — the read in [handleAudioData] is volatile
     *  and both branches are correct. */
    /** Experimento E0/E1: recibe la captura del mic a 16 kHz (post-decimación,
     *  pre-AEC) para el dataset de eco. Se graba aunque el VAD esté muteado. */
    @Volatile private var datasetRecorder: EchoDatasetRecorder? = null

    fun setDatasetRecorder(recorder: EchoDatasetRecorder?) {
        datasetRecorder = recorder
    }

    fun setAecProcessor(processor: SoftwareEchoCanceller?) {
        aecProcessor = processor
        Log.i(TAG, "setAecProcessor: ${if (processor == null) "cleared" else "attached"}")
    }

    /**
     * Flip the full-duplex flag at runtime.
     *
     * If enabling while we were currently in the muted state (a TTS playback
     * had already fired the rising edge), immediately clear `wasMuted` and
     * emit an EngineStatus so the UI reflects the un-mute on the NEXT frame
     * without waiting for a `muted != wasMuted` transition — otherwise the
     * next frame's `muted` computes to `false` and `wasMuted` is also
     * false, no transition detected, no user-visible un-mute event.
     */
    fun setFullDuplexMode(enabled: Boolean) {
        val prev = fullDuplexModeInternal
        fullDuplexModeInternal = enabled
        val currentlyPlaying = ttsPlaying?.get() == true
        Log.i(
            TAG,
            "setFullDuplexMode: prev=$prev, new=$enabled, ttsPlaying=$currentlyPlaying, wasMuted=$wasMuted",
        )
        if (enabled && wasMuted) {
            wasMuted = false
            Log.i(TAG, "Full-duplex enabled while muted — force-clearing mute state")
            bus.emit(AudioEvent.EngineStatus(
                "VAD un-muted (full-duplex enabled during TTS)"))
        }
    }

    /** Frames skipped since [start] because the TTS was speaking. */
    val totalMutedFrames: Long get() = mutedFramesDropped

    /**
     * Bind the shared TTS-playing flag. Must be called BEFORE [start].
     * Passing `null` disables muting (default).
     */
    fun setTtsPlayingRef(ref: AtomicBoolean?) {
        ttsPlaying = ref
        Log.i(TAG, "setTtsPlayingRef: ${if (ref == null) "disabled" else "enabled"}")
    }

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
        mutedFramesDropped = 0
        wasMuted = false

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

        // Full-duplex mode disables the mute path entirely — mic keeps
        // capturing, chunker keeps collecting, VAD keeps running while TTS
        // speaks. See [fullDuplexMode] KDoc for the physical caveat.
        // TtsAudioPlayer bookends still fire and still write the shared
        // ttsPlaying flag, so the UI stays accurate — we just stop acting
        // on the flag here in the mic path.
        // Decimate to the target Silero rate if Oboe opened the device at a
        // higher native rate (e.g. 48 kHz → 16 kHz with factor 3 by mean of N).
        // Done before the mute check so the echo-dataset tap also sees the
        // mic while half-duplex mutes the VAD.
        val pcmDecimated = if (decimationFactor > 1) decimateMean(event.samples, decimationFactor)
                           else event.samples
        datasetRecorder?.onMic(pcmDecimated)

        val muted = !fullDuplexMode && ttsPlaying?.get() == true
        if (muted != wasMuted) {
            wasMuted = muted
            if (muted) {
                // Rising edge — mic is about to be muted. If the chunker was
                // in the middle of collecting a chunk, we can't just keep it
                // suspended: post-mute audio would concatenate to pre-mute
                // audio with a gap the length of the TTS output, giving
                // Gemma a temporally-broken utterance. Emit it now if it's
                // already long enough to be useful, otherwise drop it. Then
                // clear the chunker's pre-roll (pre-mute silence frames)
                // and the VAD state machine + reassembler so the un-mute
                // starts from a clean baseline.
                handleMuteRisingEdge()
                Log.i(TAG, "TTS playing → muting VAD input (chunker/VAD reset)")
                bus.emit(AudioEvent.EngineStatus("VAD muted: TTS is speaking"))
            } else {
                Log.i(TAG, "TTS finished → un-muting VAD input (droppedFrames=$mutedFramesDropped)")
                bus.emit(AudioEvent.EngineStatus(
                    "VAD un-muted (dropped ${mutedFramesDropped} mic events during playback)"))
            }
        }
        if (muted) {
            mutedFramesDropped += 1
            return
        }

        // Optional software AEC. Runs BEFORE the reassembler so Silero sees
        // the echo-cancelled signal. Buffers internally in 10 ms frames —
        // may return an empty array when the input is shorter than one
        // full frame after buffering; the reassembler handles empty inputs
        // gracefully. Falls back to the raw decimated PCM when no AEC is
        // wired in.
        val proc = aecProcessor
        val pcm = if (proc != null && proc.isInitialized) {
            proc.process(pcmDecimated).also { datasetRecorder?.onAecOut(it) }
        } else {
            pcmDecimated
        }

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
     * Rising-edge mute handler. Called once, right when [ttsPlaying] flips
     * from false to true. Decides what to do with any in-flight chunk buffer,
     * then wipes VAD + reassembler so the un-mute starts clean.
     *
     *   - Chunker collecting AND buffer ≥ minChunkSamples → flush it out as
     *     a normal ChunkReady (Gemma still gets a coherent utterance).
     *   - Chunker collecting AND buffer < minChunkSamples → drop; too short
     *     to translate meaningfully, would just spend GPU on garbage.
     *   - Chunker idle → nothing to flush, still reset pre-roll.
     *
     * After the buffer decision, [AudioChunker.resetAll], [SileroVadProcessor.reset],
     * and [FrameReassembler.reset] all fire unconditionally. The reassembler
     * matters: it may hold up to 511 pre-mute samples that would otherwise be
     * spliced onto the first post-mute frame, giving a garbled first inference.
     */
    private fun handleMuteRisingEdge() {
        val currentChunker = chunker
        val currentProcessor = processor
        if (currentChunker.isCollecting) {
            val sampleCount = currentChunker.currentSampleCount
            if (sampleCount >= currentChunker.minChunkSamples) {
                val flushed = currentChunker.flush()
                if (flushed != null) {
                    Log.i(TAG, "Mute edge: flushed in-flight chunk (${flushed.samples.size} samples)")
                    bus.emit(flushed)
                }
            } else {
                Log.i(TAG, "Mute edge: discarded partial chunk ($sampleCount samples < minChunk)")
            }
        }
        currentChunker.resetAll()
        currentProcessor.reset()
        reassembler.reset()
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
