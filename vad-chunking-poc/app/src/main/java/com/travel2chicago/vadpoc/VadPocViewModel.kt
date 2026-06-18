package com.travel2chicago.vadpoc

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.travel2chicago.vadpoc.audio.AudioCaptureManager
import com.travel2chicago.vadpoc.audio.AudioDeviceManager
import com.travel2chicago.vadpoc.audio.AudioEngineConfig
import com.travel2chicago.vadpoc.audio.AudioEvent
import com.travel2chicago.vadpoc.audio.AudioEventBus
import com.travel2chicago.vadpoc.audio.AudioPlaybackManager
import com.travel2chicago.vadpoc.audio.NativeAudioEngine
import com.travel2chicago.vadpoc.audio.VadState
import com.travel2chicago.vadpoc.chunker.ChunkerConfig
import com.travel2chicago.vadpoc.pipeline.VadChunkingPipeline
import com.travel2chicago.vadpoc.vad.SileroVadModel
import com.travel2chicago.vadpoc.vad.SileroVadOnnxModel
import com.travel2chicago.vadpoc.vad.VadConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val TAG = "VadPoc"

/** UI snapshot — immutable so Compose recomposes on any single-field change. */
data class VadPocUiState(
    val inputDevices: List<AudioDeviceManager.DeviceEntry> = emptyList(),
    val outputDevices: List<AudioDeviceManager.DeviceEntry> = emptyList(),
    val selectedInputId: Int? = null,
    val selectedOutputId: Int? = null,

    val hasRecordPermission: Boolean = false,
    val hasBluetoothPermission: Boolean = false,

    val modelLoaded: Boolean = false,
    val modelLoadError: String? = null,

    val capturing: Boolean = false,
    val playing: Boolean = false,
    val pipelineRunning: Boolean = false,
    val playbackSinkLabel: String = "—",

    val vadState: VadState = VadState.SILENCE,
    val vadProbability: Float = 0f,
    val collecting: Boolean = false,

    val chunksEmitted: Int = 0,
    val lastChunkDurationMs: Int = 0,
    val lastChunkPeak: Int = 0,
    val lastChunkSamples: ShortArray? = null,
    val chunkHistory: List<ChunkSummary> = emptyList(),

    val vadConfig: VadConfig = VadConfig(),
    val chunkerConfig: ChunkerConfig = ChunkerConfig(),

    val totalSamplesCaptured: Long = 0,
    val overflowCount: Long = 0,

    val logs: List<String> = emptyList(),
    val error: String? = null,
) {
    val selectedInput get() = inputDevices.firstOrNull { it.id == selectedInputId }
    val selectedOutput get() = outputDevices.firstOrNull { it.id == selectedOutputId }
}

data class ChunkSummary(
    val index: Int,
    val durationMs: Int,
    val peak: Int,
    val timestampNs: Long,
)

class VadPocViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(VadPocUiState())
    val state: StateFlow<VadPocUiState> = _state.asStateFlow()

    private val config = AudioEngineConfig()
    private val bus = AudioEventBus()
    private val engine: NativeAudioEngine = NativeAudioEngine.create(config)
    private val deviceManager = AudioDeviceManager(app, bus)
    private val captureManager = AudioCaptureManager(engine, bus, config, viewModelScope)
    private val playbackManager = AudioPlaybackManager(app, engine, config, viewModelScope)

    @Volatile private var model: SileroVadModel? = null
    @Volatile private var pipeline: VadChunkingPipeline? = null

    init {
        deviceManager.register()
        refreshDevices()

        // Forward bus events into UI state.
        viewModelScope.launch(Dispatchers.Default) {
            bus.events.collect { event -> handleEvent(event) }
        }

        // Load the ONNX model in background so the UI doesn't block.
        viewModelScope.launch(Dispatchers.IO) {
            log("Model load STARTED (assets/silero_vad.onnx)")
            val started = System.currentTimeMillis()
            try {
                val loaded = SileroVadOnnxModel.loadFromAssets(getApplication())
                model = loaded
                pipeline = VadChunkingPipeline(
                    bus = bus,
                    format = config.format,
                    model = loaded,
                    initialVadConfig = _state.value.vadConfig,
                    initialChunkerConfig = _state.value.chunkerConfig,
                )
                _state.update { it.copy(modelLoaded = true) }
                val elapsed = System.currentTimeMillis() - started
                log("Model LOADED ✓ in ${elapsed}ms — pipeline ready")
            } catch (t: Throwable) {
                Log.e(TAG, "Model load failed", t)
                val elapsed = System.currentTimeMillis() - started
                val msg = "Model load FAILED after ${elapsed}ms: ${t.javaClass.simpleName}: ${t.message}"
                _state.update { it.copy(modelLoadError = msg) }
                log("[ERROR] $msg")
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        pipeline?.stop()
        captureManager.stop()
        playbackManager.stop()
        deviceManager.unregister()
        engine.close()
        model?.close()
    }

    // ── Permissions ─────────────────────────────────────────────────────────

    fun onPermissionsUpdated(record: Boolean, bluetooth: Boolean) {
        _state.update { it.copy(hasRecordPermission = record, hasBluetoothPermission = bluetooth) }
        log("Permissions: RECORD_AUDIO=$record, BLUETOOTH_CONNECT=$bluetooth")
        refreshDevices()
    }

    // ── Device selection ────────────────────────────────────────────────────

    fun refreshDevices() {
        val inputs = deviceManager.listInputs()
        val outputs = deviceManager.listOutputs()
        val prevIn = _state.value.selectedInputId
        val prevOut = _state.value.selectedOutputId

        val selectedIn = inputs.firstOrNull { it.id == prevIn }?.id
            ?: inputs.firstOrNull { it.isPreferred }?.id
            ?: inputs.firstOrNull()?.id

        val selectedOut = outputs.firstOrNull { it.id == prevOut }?.id
            ?: outputs.firstOrNull { it.isPreferred }?.id
            ?: deviceManager.preferredOutput()?.id   // BT > USB DAC fallback
            ?: outputs.firstOrNull()?.id

        _state.update {
            it.copy(
                inputDevices = inputs,
                outputDevices = outputs,
                selectedInputId = selectedIn,
                selectedOutputId = selectedOut,
            )
        }
    }

    fun selectInput(deviceId: Int) { _state.update { it.copy(selectedInputId = deviceId) } }
    fun selectOutput(deviceId: Int) { _state.update { it.copy(selectedOutputId = deviceId) } }

    // ── Pipeline ────────────────────────────────────────────────────────────

    fun startPipeline() {
        val p = pipeline ?: run {
            log("[ERROR] Cannot start: model not loaded yet")
            return
        }
        if (!_state.value.hasRecordPermission) {
            log("[ERROR] Cannot start: RECORD_AUDIO not granted")
            return
        }
        val deviceId = _state.value.selectedInputId ?: 0
        if (!captureManager.start(deviceId)) {
            log("[ERROR] captureManager.start failed")
            return
        }
        // CRITICAL: Oboe may open the device at the hardware native rate
        // (often 48 kHz) regardless of our setSampleRate(16000) hint.
        // Tell the pipeline so it decimates correctly before Silero sees it.
        val actualSr = engine.actualSampleRateCapture()
        log("Capture actual sample rate: $actualSr Hz (target=${config.format.sampleRate} Hz)")
        p.setCaptureSampleRate(actualSr)
        p.start(viewModelScope)
        _state.update {
            it.copy(
                capturing = true,
                pipelineRunning = true,
                chunksEmitted = 0,
                lastChunkSamples = null,
                chunkHistory = emptyList(),
                totalSamplesCaptured = 0,
                overflowCount = 0,
                error = null,
            )
        }
        log("Pipeline started (deviceId=$deviceId)")
    }

    fun stopPipeline() {
        pipeline?.stop()
        captureManager.stop()
        _state.update {
            it.copy(
                capturing = false,
                pipelineRunning = false,
                vadState = VadState.SILENCE,
                vadProbability = 0f,
                collecting = false,
            )
        }
        log("Pipeline stopped")
    }

    fun startPlayback() {
        if (_state.value.playing) return
        val out = _state.value.selectedOutput
        if (!playbackManager.start(out)) {
            log("[ERROR] playbackManager.start failed")
            return
        }
        _state.update {
            it.copy(
                playing = true,
                playbackSinkLabel = playbackManager.activeSinkLabel ?: "?",
            )
        }
        log("Playback started (sink=${playbackManager.activeSinkLabel}, device=${out?.displayName ?: "default"})")
    }

    fun stopPlayback() {
        if (!_state.value.playing) return
        playbackManager.stop()
        _state.update { it.copy(playing = false) }
        log("Playback stopped")
    }

    fun replayLastChunk() {
        val samples = _state.value.lastChunkSamples
        if (samples == null) {
            log("No chunk to replay yet")
            return
        }
        if (!_state.value.playing) startPlayback()
        playbackManager.play(samples)
        log("Replaying last chunk (${samples.size} samples)")
    }

    fun playSineWave() {
        if (!_state.value.playing) startPlayback()
        playbackManager.playSineWave()
        log("Sine 440Hz 1.5s enqueued")
    }

    // ── Slider callbacks ────────────────────────────────────────────────────

    fun setVadThreshold(value: Float) {
        val safe = value.coerceIn(0.05f, 0.95f)
        val newCfg = _state.value.vadConfig.copy(threshold = safe)
        _state.update { it.copy(vadConfig = newCfg) }
        pipeline?.updateVadConfig(newCfg)
    }

    fun setVadMinSpeechMs(value: Int) {
        val newCfg = _state.value.vadConfig.copy(minSpeechMs = value.coerceIn(0, 1000))
        _state.update { it.copy(vadConfig = newCfg) }
        pipeline?.updateVadConfig(newCfg)
    }

    fun setVadMinSilenceMs(value: Int) {
        val newCfg = _state.value.vadConfig.copy(minSilenceMs = value.coerceIn(0, 2000))
        _state.update { it.copy(vadConfig = newCfg) }
        pipeline?.updateVadConfig(newCfg)
    }

    fun setChunkerMinMs(value: Int) {
        val current = _state.value.chunkerConfig
        val newMin = value.coerceIn(200, current.maxChunkMs)
        val newCfg = current.copy(minChunkMs = newMin)
        _state.update { it.copy(chunkerConfig = newCfg) }
        pipeline?.updateChunkerConfig(newCfg)
    }

    fun setChunkerMaxMs(value: Int) {
        val current = _state.value.chunkerConfig
        val newMax = value.coerceAtLeast(current.minChunkMs).coerceAtMost(15_000)
        val newCfg = current.copy(maxChunkMs = newMax)
        _state.update { it.copy(chunkerConfig = newCfg) }
        pipeline?.updateChunkerConfig(newCfg)
    }

    fun setChunkerSilenceEndMs(value: Int) {
        val newCfg = _state.value.chunkerConfig.copy(silenceEndMs = value.coerceIn(50, 3000))
        _state.update { it.copy(chunkerConfig = newCfg) }
        pipeline?.updateChunkerConfig(newCfg)
    }

    fun setChunkerPreRollMs(value: Int) {
        val newCfg = _state.value.chunkerConfig.copy(preRollMs = value.coerceIn(0, 1000))
        _state.update { it.copy(chunkerConfig = newCfg) }
        pipeline?.updateChunkerConfig(newCfg)
    }

    // ── Event handling ──────────────────────────────────────────────────────

    private fun handleEvent(event: AudioEvent) {
        when (event) {
            is AudioEvent.AudioData -> {
                _state.update {
                    it.copy(
                        totalSamplesCaptured = it.totalSamplesCaptured + event.samples.size,
                        // Pipeline pushes the latest probability via VadTransition; we
                        // surface its "current" state here for the UI dot.
                        vadState = pipeline?.vadState ?: it.vadState,
                        vadProbability = pipeline?.lastProbability ?: it.vadProbability,
                        collecting = pipeline?.isCollecting ?: it.collecting,
                    )
                }
            }
            is AudioEvent.VadTransition -> {
                _state.update {
                    it.copy(
                        vadState = event.state,
                        vadProbability = event.probability,
                    )
                }
                log("VAD → ${event.state} (p=${"%.3f".format(event.probability)})")
            }
            is AudioEvent.ChunkReady -> {
                val n = _state.value.chunksEmitted + 1
                val summary = ChunkSummary(
                    index = n,
                    durationMs = event.durationMs,
                    peak = event.peak,
                    timestampNs = event.timestampNs,
                )
                _state.update {
                    it.copy(
                        chunksEmitted = n,
                        lastChunkDurationMs = event.durationMs,
                        lastChunkPeak = event.peak,
                        lastChunkSamples = event.samples,
                        chunkHistory = (listOf(summary) + it.chunkHistory).take(20),
                    )
                }
                log("Chunk #$n: ${event.durationMs}ms peak=${event.peak} (${event.samples.size} samples)")
            }
            is AudioEvent.BufferOverflow -> {
                _state.update { it.copy(overflowCount = it.overflowCount + event.droppedSamples) }
                log("OVERFLOW: dropped ${event.droppedSamples} samples")
            }
            is AudioEvent.DeviceConnected -> {
                log("Device connected: ${event.info.productName} (id=${event.info.id}, input=${event.isInput})")
                refreshDevices()
            }
            is AudioEvent.DeviceDisconnected -> {
                log("Device disconnected: ${event.description} (id=${event.deviceId})")
                refreshDevices()
            }
            is AudioEvent.EngineStatus -> log(event.message)
        }
    }

    private fun log(msg: String) {
        Log.i(TAG, msg)
        _state.update {
            it.copy(logs = (it.logs + "[${System.currentTimeMillis() % 100_000}] $msg").takeLast(300))
        }
    }
}
