package com.travel2chicago.gemmapipeline

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.travel2chicago.gemmapipeline.ast.AstChunkRouter
import com.travel2chicago.gemmapipeline.ast.AstConfig
import com.travel2chicago.gemmapipeline.ast.GemmaAstEngine
import com.travel2chicago.gemmapipeline.ast.LiteRtGemmaAstEngine
import com.travel2chicago.gemmapipeline.audio.AudioCaptureManager
import com.travel2chicago.gemmapipeline.audio.AudioDeviceManager
import com.travel2chicago.gemmapipeline.audio.AudioEngineConfig
import com.travel2chicago.gemmapipeline.audio.AudioEvent
import com.travel2chicago.gemmapipeline.audio.AudioEventBus
import com.travel2chicago.gemmapipeline.audio.AudioPlaybackManager
import com.travel2chicago.gemmapipeline.audio.NativeAudioEngine
import com.travel2chicago.gemmapipeline.audio.VadState
import com.travel2chicago.gemmapipeline.chunker.ChunkerConfig
import com.travel2chicago.gemmapipeline.pipeline.VadChunkingPipeline
import com.travel2chicago.gemmapipeline.vad.SileroVadModel
import com.travel2chicago.gemmapipeline.vad.SileroVadOnnxModel
import com.travel2chicago.gemmapipeline.vad.VadConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "GemmaPipelineVM"
private const val TRANSLATION_HISTORY_CAP = 50

data class GemmaPipelineUiState(
    // Devices + permissions
    val inputDevices: List<AudioDeviceManager.DeviceEntry> = emptyList(),
    val outputDevices: List<AudioDeviceManager.DeviceEntry> = emptyList(),
    val selectedInputId: Int? = null,
    val selectedOutputId: Int? = null,
    val hasRecordPermission: Boolean = false,
    val hasBluetoothPermission: Boolean = false,
    val hasStoragePermission: Boolean = false,

    // Silero model state
    val sileroLoaded: Boolean = false,
    val sileroLoadError: String? = null,

    // Gemma engine state
    val gemmaLoading: Boolean = false,
    val gemmaLoaded: Boolean = false,
    val gemmaBackend: String? = null,
    val gemmaLoadTimeMs: Long = 0,
    val gemmaLoadError: String? = null,
    val gemmaModelPath: String = AstConfig().modelPath,
    val gemmaModelExists: Boolean = false,

    // Pipeline + capture + playback
    val capturing: Boolean = false,
    val playing: Boolean = false,
    val pipelineRunning: Boolean = false,
    val routerRunning: Boolean = false,
    val playbackSinkLabel: String = "—",

    // VAD / chunker live state
    val vadState: VadState = VadState.SILENCE,
    val vadProbability: Float = 0f,
    val collecting: Boolean = false,
    val vadConfig: VadConfig = VadConfig(),
    val chunkerConfig: ChunkerConfig = ChunkerConfig(),

    // Chunk + translation history
    val chunksEmitted: Int = 0,
    val lastChunkDurationMs: Int = 0,
    val lastChunkPeak: Int = 0,
    val lastChunkSamples: ShortArray? = null,

    val totalTranslated: Long = 0,
    val totalDropped: Long = 0,
    val astErrors: Long = 0,
    val astQueueSize: Int = 0,
    val astAvgLatencyMs: Double = 0.0,
    val translations: List<TranslationEntry> = emptyList(),

    val totalSamplesCaptured: Long = 0,
    val overflowCount: Long = 0,

    val logs: List<String> = emptyList(),
    val error: String? = null,
) {
    val selectedInput get() = inputDevices.firstOrNull { it.id == selectedInputId }
    val selectedOutput get() = outputDevices.firstOrNull { it.id == selectedOutputId }
    val pipelineReady: Boolean get() = sileroLoaded && gemmaLoaded && hasRecordPermission && hasStoragePermission
}

data class TranslationEntry(
    val index: Int,
    val text: String,
    val latencyMs: Long,
    val sourceDurationMs: Int,
    val sourcePeak: Int,
    val timestampNs: Long,
)

class GemmaPipelineViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(GemmaPipelineUiState())
    val state: StateFlow<GemmaPipelineUiState> = _state.asStateFlow()

    private val audioConfig = AudioEngineConfig()
    private val astConfig = AstConfig()
    private val bus = AudioEventBus(bufferCapacity = 128)
    private val nativeEngine: NativeAudioEngine = NativeAudioEngine.create(audioConfig)
    private val deviceManager = AudioDeviceManager(app, bus)
    private val captureManager = AudioCaptureManager(nativeEngine, bus, audioConfig, viewModelScope)
    private val playbackManager = AudioPlaybackManager(app, nativeEngine, audioConfig, viewModelScope)

    @Volatile private var sileroModel: SileroVadModel? = null
    @Volatile private var pipeline: VadChunkingPipeline? = null

    @Volatile private var gemmaEngine: GemmaAstEngine? = null
    @Volatile private var router: AstChunkRouter? = null

    /**
     * Re-entry guard for [loadGemmaEngine]. Must be set atomically before any
     * other check so two concurrent callers can't both reach `LiteRtGemmaAstEngine.load()`
     * — LiteRT-LM is not thread-safe during init and a double-call would crash
     * with undefined native behavior after ~30s of work.
     */
    private val gemmaLoadInFlight = AtomicBoolean(false)

    init {
        deviceManager.register()
        refreshDevices()
        checkModelFileExists()

        viewModelScope.launch(Dispatchers.Default) {
            bus.events.collect { event -> handleEvent(event) }
        }

        loadSileroModel()
        // Gemma load is gated by storage permission — we kick it off in
        // onPermissionsUpdated once storage is granted.
    }

    override fun onCleared() {
        super.onCleared()
        // onCleared is not suspendable — use the synchronous hard cancel.
        // Any chunks in flight at this moment (rotation / process death)
        // are lost; that's an accepted trade-off here.
        router?.cancel()
        pipeline?.stop()
        captureManager.stop()
        playbackManager.stop()
        deviceManager.unregister()
        nativeEngine.close()
        sileroModel?.close()
        gemmaEngine?.close()
    }

    // ── Permissions ─────────────────────────────────────────────────────────

    fun onPermissionsUpdated(record: Boolean, bluetooth: Boolean, storage: Boolean) {
        val prev = _state.value
        _state.update {
            it.copy(
                hasRecordPermission = record,
                hasBluetoothPermission = bluetooth,
                hasStoragePermission = storage,
            )
        }
        log("Permissions: RECORD=$record, BT=$bluetooth, STORAGE=$storage")
        refreshDevices()
        checkModelFileExists()

        // Kick off Gemma load when storage permission first becomes available.
        if (storage && !prev.hasStoragePermission && gemmaEngine == null && !_state.value.gemmaLoading) {
            loadGemmaEngine()
        }
    }

    private fun checkModelFileExists() {
        val exists = try { File(astConfig.modelPath).isFile } catch (_: Throwable) { false }
        _state.update { it.copy(gemmaModelExists = exists) }
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
            ?: deviceManager.preferredOutput()?.id
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

    // ── Model loading ───────────────────────────────────────────────────────

    private fun loadSileroModel() {
        viewModelScope.launch(Dispatchers.IO) {
            log("Silero VAD load STARTED (assets/silero_vad.onnx)")
            val started = System.currentTimeMillis()
            try {
                val loaded = SileroVadOnnxModel.loadFromAssets(getApplication())
                sileroModel = loaded
                pipeline = VadChunkingPipeline(
                    bus = bus,
                    format = audioConfig.format,
                    model = loaded,
                    initialVadConfig = _state.value.vadConfig,
                    initialChunkerConfig = _state.value.chunkerConfig,
                )
                val elapsed = System.currentTimeMillis() - started
                _state.update { it.copy(sileroLoaded = true) }
                log("Silero LOADED ✓ in ${elapsed}ms")
            } catch (t: Throwable) {
                val msg = "Silero load FAILED: ${t.javaClass.simpleName}: ${t.message}"
                Log.e(TAG, msg, t)
                _state.update { it.copy(sileroLoadError = msg) }
                log("[ERROR] $msg")
            }
        }
    }

    fun loadGemmaEngine() {
        if (gemmaEngine != null) return
        // Atomic latch — only the FIRST caller proceeds. Subsequent calls
        // bounce until the in-flight load completes (success or failure).
        if (!gemmaLoadInFlight.compareAndSet(false, true)) return

        // Pre-flight checks — release the latch on early returns.
        if (!_state.value.hasStoragePermission) {
            log("[ERROR] Cannot load Gemma: storage permission not granted")
            gemmaLoadInFlight.set(false)
            return
        }
        if (!File(astConfig.modelPath).isFile) {
            val msg = "Gemma model not found at ${astConfig.modelPath} — copy from gemma-ast-poc"
            log("[ERROR] $msg")
            _state.update { it.copy(gemmaLoadError = msg) }
            gemmaLoadInFlight.set(false)
            return
        }

        _state.update { it.copy(gemmaLoading = true, gemmaLoadError = null) }
        viewModelScope.launch(Dispatchers.IO) {
            log("Gemma load STARTED (this takes ~10-30s on first run)")
            val cacheDir = getApplication<Application>().getExternalFilesDir(null)?.absolutePath
                ?: getApplication<Application>().cacheDir.absolutePath
            try {
                val loaded = LiteRtGemmaAstEngine.load(astConfig, cacheDir)
                gemmaEngine = loaded
                _state.update {
                    it.copy(
                        gemmaLoading = false,
                        gemmaLoaded = true,
                        gemmaBackend = loaded.backendUsed,
                        gemmaLoadTimeMs = loaded.loadTimeMs,
                    )
                }
                log("Gemma LOADED ✓ in ${loaded.loadTimeMs}ms (backend=${loaded.backendUsed})")
            } catch (t: Throwable) {
                val msg = "Gemma load FAILED: ${t.javaClass.simpleName}: ${t.message}"
                Log.e(TAG, msg, t)
                _state.update { it.copy(gemmaLoading = false, gemmaLoadError = msg) }
                log("[ERROR] $msg")
            } finally {
                gemmaLoadInFlight.set(false)
            }
        }
    }

    // ── Pipeline ────────────────────────────────────────────────────────────

    fun startPipeline() {
        val p = pipeline ?: run { log("[ERROR] Silero not loaded yet"); return }
        val g = gemmaEngine ?: run { log("[ERROR] Gemma not loaded yet"); return }
        if (!_state.value.hasRecordPermission) {
            log("[ERROR] RECORD_AUDIO not granted")
            return
        }
        val deviceId = _state.value.selectedInputId ?: 0
        if (!captureManager.start(deviceId)) {
            log("[ERROR] captureManager.start failed")
            return
        }
        val actualSr = nativeEngine.actualSampleRateCapture()
        log("Capture actual sample rate: $actualSr Hz")
        p.setCaptureSampleRate(actualSr)
        p.start(viewModelScope)

        val r = AstChunkRouter(bus, g, astConfig, sampleRate = audioConfig.format.sampleRate)
        r.start(viewModelScope)
        router = r

        _state.update {
            it.copy(
                capturing = true,
                pipelineRunning = true,
                routerRunning = true,
                chunksEmitted = 0,
                lastChunkSamples = null,
                totalTranslated = 0,
                totalDropped = 0,
                astErrors = 0,
                translations = emptyList(),
                totalSamplesCaptured = 0,
                overflowCount = 0,
                error = null,
            )
        }
        log("Pipeline + AST router started")
    }

    fun stopPipeline() {
        // Capture the router instance, then null it out so a quick re-start
        // can't reach the old one. The graceful drain runs in the background
        // — user-facing stop reports "stopped" immediately while pending
        // translations keep landing in the TRANSLATIONS list until done.
        val r = router
        router = null
        if (r != null) {
            viewModelScope.launch {
                val drained = r.stopGracefully()
                log("AST router drained $drained pending chunks before stop")
            }
        }
        pipeline?.stop()
        captureManager.stop()
        _state.update {
            it.copy(
                capturing = false,
                pipelineRunning = false,
                routerRunning = false,
                vadState = VadState.SILENCE,
                vadProbability = 0f,
                collecting = false,
            )
        }
        log("Pipeline + AST router stopped (drain in progress)")
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

    // ── Slider callbacks ────────────────────────────────────────────────────

    fun setVadThreshold(value: Float) {
        val cfg = _state.value.vadConfig.copy(threshold = value.coerceIn(0.05f, 0.95f))
        _state.update { it.copy(vadConfig = cfg) }
        pipeline?.updateVadConfig(cfg)
    }
    fun setVadMinSpeechMs(value: Int) {
        val cfg = _state.value.vadConfig.copy(minSpeechMs = value.coerceIn(0, 1000))
        _state.update { it.copy(vadConfig = cfg) }
        pipeline?.updateVadConfig(cfg)
    }
    fun setVadMinSilenceMs(value: Int) {
        val cfg = _state.value.vadConfig.copy(minSilenceMs = value.coerceIn(0, 2000))
        _state.update { it.copy(vadConfig = cfg) }
        pipeline?.updateVadConfig(cfg)
    }
    fun setChunkerMinMs(value: Int) {
        val current = _state.value.chunkerConfig
        val cfg = current.copy(minChunkMs = value.coerceIn(200, current.maxChunkMs))
        _state.update { it.copy(chunkerConfig = cfg) }
        pipeline?.updateChunkerConfig(cfg)
    }
    fun setChunkerMaxMs(value: Int) {
        val current = _state.value.chunkerConfig
        val cfg = current.copy(maxChunkMs = value.coerceAtLeast(current.minChunkMs).coerceAtMost(15_000))
        _state.update { it.copy(chunkerConfig = cfg) }
        pipeline?.updateChunkerConfig(cfg)
    }
    fun setChunkerSilenceEndMs(value: Int) {
        val cfg = _state.value.chunkerConfig.copy(silenceEndMs = value.coerceIn(50, 3000))
        _state.update { it.copy(chunkerConfig = cfg) }
        pipeline?.updateChunkerConfig(cfg)
    }
    fun setChunkerPreRollMs(value: Int) {
        val cfg = _state.value.chunkerConfig.copy(preRollMs = value.coerceIn(0, 1000))
        _state.update { it.copy(chunkerConfig = cfg) }
        pipeline?.updateChunkerConfig(cfg)
    }

    // ── Event handling ──────────────────────────────────────────────────────

    private fun handleEvent(event: AudioEvent) {
        when (event) {
            is AudioEvent.AudioData -> {
                _state.update {
                    it.copy(
                        totalSamplesCaptured = it.totalSamplesCaptured + event.samples.size,
                        vadState = pipeline?.vadState ?: it.vadState,
                        vadProbability = pipeline?.lastProbability ?: it.vadProbability,
                        collecting = pipeline?.isCollecting ?: it.collecting,
                        astQueueSize = router?.queueSize ?: 0,
                    )
                }
            }
            is AudioEvent.VadTransition -> {
                _state.update {
                    it.copy(vadState = event.state, vadProbability = event.probability)
                }
                log("VAD → ${event.state} (p=${"%.3f".format(event.probability)})")
            }
            is AudioEvent.ChunkReady -> {
                val r = router
                _state.update {
                    it.copy(
                        chunksEmitted = it.chunksEmitted + 1,
                        lastChunkDurationMs = event.durationMs,
                        lastChunkPeak = event.peak,
                        lastChunkSamples = event.samples,
                        astQueueSize = r?.queueSize ?: 0,
                    )
                }
                log("Chunk #${_state.value.chunksEmitted}: ${event.durationMs}ms peak=${event.peak}")
            }
            is AudioEvent.TranslationReady -> {
                val r = router
                val nextIdx = (r?.totalTranslated ?: 0L).toInt()
                val entry = TranslationEntry(
                    index = nextIdx,
                    text = event.text,
                    latencyMs = event.latencyMs,
                    sourceDurationMs = event.sourceDurationMs,
                    sourcePeak = event.sourcePeak,
                    timestampNs = event.timestampNs,
                )
                _state.update {
                    it.copy(
                        translations = (listOf(entry) + it.translations).take(TRANSLATION_HISTORY_CAP),
                        totalTranslated = r?.totalTranslated ?: it.totalTranslated,
                        astAvgLatencyMs = r?.averageLatencyMs ?: it.astAvgLatencyMs,
                        astQueueSize = r?.queueSize ?: 0,
                    )
                }
                log("Translation #$nextIdx (${event.latencyMs}ms): ${event.text.take(80)}")
            }
            is AudioEvent.AstError -> {
                val r = router
                _state.update {
                    it.copy(
                        astErrors = r?.totalErrors ?: (it.astErrors + 1),
                        totalDropped = r?.totalDropped ?: it.totalDropped,
                    )
                }
                log("[ERROR] AST: ${event.message}")
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
            it.copy(logs = (it.logs + "[${System.currentTimeMillis() % 100_000}] $msg").takeLast(400))
        }
    }
}
