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

    fun start(scope: CoroutineScope) {
        if (isRunning) {
            Log.w(TAG, "start: already running, ignoring")
            return
        }
        reassembler.reset()
        processor.reset()
        chunker.flush() // discard any leftover

        job = scope.launch(Dispatchers.Default) {
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
        val frames = reassembler.feed(event.samples)
        if (frames.isEmpty()) return

        val currentProcessor = processor
        val currentChunker = chunker

        for (frame in frames) {
            // Read raw probability via the processor's underlying model (one
            // inference per frame total — processFrame already calls it).
            val transition = try {
                currentProcessor.processFrame(frame, event.timestampNs)
            } catch (t: Throwable) {
                Log.e(TAG, "VAD inference failed", t)
                bus.emit(AudioEvent.EngineStatus("VAD inference error: ${t.message}"))
                return
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
