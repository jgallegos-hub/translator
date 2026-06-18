package com.travel2chicago.vadpoc.pipeline

import android.util.Log
import com.travel2chicago.vadpoc.audio.AudioEvent
import com.travel2chicago.vadpoc.audio.AudioEventBus
import com.travel2chicago.vadpoc.audio.AudioFormat
import com.travel2chicago.vadpoc.audio.VadState
import com.travel2chicago.vadpoc.chunker.AudioChunker
import com.travel2chicago.vadpoc.chunker.ChunkerConfig
import com.travel2chicago.vadpoc.vad.FrameReassembler
import com.travel2chicago.vadpoc.vad.SileroVadModel
import com.travel2chicago.vadpoc.vad.SileroVadProcessor
import com.travel2chicago.vadpoc.vad.VadConfig
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

    // ── Diagnostic counters ────────────────────────────────────────────────
    @Volatile private var audioDataEventsSeen: Long = 0
    @Volatile private var framesEmitted: Long = 0
    @Volatile private var vadInferences: Long = 0

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

        job = scope.launch(Dispatchers.Default) {
            bus.emit(AudioEvent.EngineStatus("Pipeline subscribed to bus (waiting for AudioData…)"))
            Log.i(TAG, "Pipeline subscribed to bus")
            bus.events
                .filterIsInstance<AudioEvent.AudioData>()
                .collect { event -> handleAudioData(event) }
        }
        Log.i(TAG, "Pipeline started (vad=$vadConfig, chunker=$chunkerConfig)")
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

        val frames = reassembler.feed(event.samples)
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
}
