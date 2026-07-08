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
import com.travel2chicago.gemmapipeline.tts.KokoroOnnxEngine
import com.travel2chicago.gemmapipeline.tts.KokoroTtsEngine
import com.travel2chicago.gemmapipeline.tts.TtsAudioPlayer
import com.travel2chicago.gemmapipeline.tts.TtsConfig
import com.travel2chicago.gemmapipeline.tts.TtsRouter
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
    /** Chunks skipped BEFORE Gemma because RMS was under the router threshold. */
    val totalDiscardedLowEnergy: Long = 0,
    /** Replies dropped AFTER Gemma because they matched a meta-text pattern. */
    val totalDiscardedMeta: Long = 0,
    val translations: List<TranslationEntry> = emptyList(),

    val totalSamplesCaptured: Long = 0,
    val overflowCount: Long = 0,

    /** True while `TtsAudioPlayer` is writing to `AudioTrack` — VAD is muted. */
    val ttsPlaying: Boolean = false,
    /** Frames dropped by the pipeline while [ttsPlaying] was true. */
    val mutedMicFrames: Long = 0,

    // Kokoro TTS engine state
    val kokoroLoading: Boolean = false,
    val kokoroLoaded: Boolean = false,
    val kokoroLoadTimeMs: Long = 0,
    val kokoroLoadError: String? = null,
    val kokoroModelExists: Boolean = false,
    val kokoroVoice: String = TtsConfig().voice,

    // TTS pipeline runtime
    val ttsRouterRunning: Boolean = false,
    val ttsQueueSize: Int = 0,
    val totalSynthesized: Long = 0,
    val totalSpoken: Int = 0,
    val ttsDropped: Long = 0,
    val ttsErrors: Long = 0,
    val ttsAvgLatencyMs: Double = 0.0,
    val lastSpokenText: String = "",
    val lastTtsDurationMs: Int = 0,

    val logs: List<String> = emptyList(),
    val error: String? = null,
) {
    val selectedInput get() = inputDevices.firstOrNull { it.id == selectedInputId }
    val selectedOutput get() = outputDevices.firstOrNull { it.id == selectedOutputId }
    val pipelineReady: Boolean
        get() = sileroLoaded && gemmaLoaded && kokoroLoaded && hasRecordPermission && hasStoragePermission
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
    private val ttsConfig = TtsConfig()
    private val bus = AudioEventBus(bufferCapacity = 128)
    private val nativeEngine: NativeAudioEngine = NativeAudioEngine.create(audioConfig)
    private val deviceManager = AudioDeviceManager(app, bus)
    private val captureManager = AudioCaptureManager(nativeEngine, bus, audioConfig, viewModelScope)
    private val playbackManager = AudioPlaybackManager(app, nativeEngine, audioConfig, viewModelScope)
    /**
     * Shared VAD-mute flag. `TtsAudioPlayer` raises it while writing samples
     * to `AudioTrack`; the VAD pipeline reads it via `setTtsPlayingRef` and
     * drops mic frames while it's `true`. Prevents the speaker output from
     * being re-captured and re-translated.
     */
    private val ttsPlaying = AtomicBoolean(false)

    private val ttsPlayer = TtsAudioPlayer(
        sampleRate = ttsConfig.sampleRate,
        ttsPlaying = ttsPlaying,
    )

    @Volatile private var sileroModel: SileroVadModel? = null
    @Volatile private var pipeline: VadChunkingPipeline? = null

    @Volatile private var gemmaEngine: GemmaAstEngine? = null
    @Volatile private var router: AstChunkRouter? = null

    @Volatile private var kokoroEngine: KokoroTtsEngine? = null
    @Volatile private var ttsRouter: TtsRouter? = null

    /**
     * Re-entry guard for [loadGemmaEngine]. Must be set atomically before any
     * other check so two concurrent callers can't both reach `LiteRtGemmaAstEngine.load()`
     * — LiteRT-LM is not thread-safe during init and a double-call would crash
     * with undefined native behavior after ~30s of work.
     */
    private val gemmaLoadInFlight = AtomicBoolean(false)

    /** Same CAS-latch pattern as [gemmaLoadInFlight] — ORT session creation
     *  is not safe to call concurrently from two coroutines. */
    private val kokoroLoadInFlight = AtomicBoolean(false)

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
        ttsRouter?.cancel()
        router?.cancel()
        pipeline?.stop()
        captureManager.stop()
        playbackManager.stop()
        ttsPlayer.close()
        deviceManager.unregister()
        nativeEngine.close()
        sileroModel?.close()
        gemmaEngine?.close()
        kokoroEngine?.close()
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

        // Kick off Gemma + Kokoro loads when storage permission first becomes
        // available. Each runs in its own coroutine; ORT and LiteRT both
        // initialise on separate threads so they don't block each other.
        if (storage && !prev.hasStoragePermission) {
            if (gemmaEngine == null && !_state.value.gemmaLoading) loadGemmaEngine()
            if (kokoroEngine == null && !_state.value.kokoroLoading) loadKokoroEngine()
        }
    }

    private fun checkModelFileExists() {
        val gemmaExists = try { File(astConfig.modelPath).isFile } catch (_: Throwable) { false }
        val kokoroExists = try {
            File(ttsConfig.modelPath).isFile && File(ttsConfig.voicesPath).isFile
        } catch (_: Throwable) { false }
        _state.update { it.copy(gemmaModelExists = gemmaExists, kokoroModelExists = kokoroExists) }
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

    fun loadKokoroEngine() {
        if (kokoroEngine != null) return
        if (!kokoroLoadInFlight.compareAndSet(false, true)) return

        if (!_state.value.hasStoragePermission) {
            log("[ERROR] Cannot load Kokoro: storage permission not granted")
            kokoroLoadInFlight.set(false)
            return
        }
        if (!File(ttsConfig.modelPath).isFile) {
            val msg = "Kokoro model not found at ${ttsConfig.modelPath} — " +
                "download kokoro-v1.0.onnx + voices-v1.0.bin from HuggingFace"
            log("[ERROR] $msg")
            _state.update { it.copy(kokoroLoadError = msg) }
            kokoroLoadInFlight.set(false)
            return
        }
        if (!File(ttsConfig.voicesPath).isFile) {
            val msg = "Kokoro voices file not found at ${ttsConfig.voicesPath}"
            log("[ERROR] $msg")
            _state.update { it.copy(kokoroLoadError = msg) }
            kokoroLoadInFlight.set(false)
            return
        }

        _state.update { it.copy(kokoroLoading = true, kokoroLoadError = null) }
        viewModelScope.launch(Dispatchers.IO) {
            log("Kokoro load STARTED (this takes ~5-15s)")
            try {
                val loaded = KokoroOnnxEngine.load(getApplication(), ttsConfig)
                kokoroEngine = loaded
                _state.update {
                    it.copy(
                        kokoroLoading = false,
                        kokoroLoaded = true,
                        kokoroLoadTimeMs = loaded.loadTimeMs,
                    )
                }
                log("Kokoro LOADED ✓ in ${loaded.loadTimeMs}ms (voices=${loaded.availableVoices.size})")
            } catch (t: Throwable) {
                val msg = "Kokoro load FAILED: ${t.javaClass.simpleName}: ${t.message}"
                Log.e(TAG, msg, t)
                _state.update { it.copy(kokoroLoading = false, kokoroLoadError = msg) }
                log("[ERROR] $msg")
            } finally {
                kokoroLoadInFlight.set(false)
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
        p.setTtsPlayingRef(ttsPlaying)
        p.start(viewModelScope)

        val r = AstChunkRouter(bus, g, astConfig, sampleRate = audioConfig.format.sampleRate)
        r.start(viewModelScope)
        router = r

        val k = kokoroEngine ?: run { log("[ERROR] Kokoro not loaded yet"); return }
        // Init the TTS player lazily here so a missing audio output device
        // surfaces at start-pipeline time, not at app launch.
        runCatching { ttsPlayer.init() }
            .onFailure { log("[ERROR] TTS player init failed: ${it.message}") }
        // Pass the player as a TtsPlayerSink so the router can drive
        // beginUtterance/play/endUtterance directly when streaming is on.
        // With streaming off, the router falls back to bus-emit and the
        // ViewModel plays TtsAudioReady as before — no double-play.
        val ttsR = TtsRouter(bus, k, ttsConfig, player = ttsPlayer)
        ttsR.start(viewModelScope)
        ttsRouter = ttsR

        _state.update {
            it.copy(
                capturing = true,
                pipelineRunning = true,
                routerRunning = true,
                ttsRouterRunning = true,
                chunksEmitted = 0,
                lastChunkSamples = null,
                totalTranslated = 0,
                totalDropped = 0,
                astErrors = 0,
                totalDiscardedLowEnergy = 0,
                totalDiscardedMeta = 0,
                translations = emptyList(),
                ttsPlaying = false,
                mutedMicFrames = 0,
                totalSynthesized = 0,
                totalSpoken = 0,
                ttsDropped = 0,
                ttsErrors = 0,
                ttsAvgLatencyMs = 0.0,
                lastSpokenText = "",
                lastTtsDurationMs = 0,
                totalSamplesCaptured = 0,
                overflowCount = 0,
                error = null,
            )
        }
        log("Pipeline + AST router + TTS router started")
    }

    fun stopPipeline() {
        // Capture the router instances, then null them out so a quick re-start
        // can't reach the old ones. Both drains run in the background — the
        // user-facing stop reports "stopped" immediately while pending
        // translations + their TTS audio keep landing until done.
        val r = router
        router = null
        if (r != null) {
            viewModelScope.launch {
                val drained = r.stopGracefully()
                log("AST router drained $drained pending chunks before stop")
            }
        }
        val ttsR = ttsRouter
        ttsRouter = null
        if (ttsR != null) {
            viewModelScope.launch {
                val drained = ttsR.stopGracefully()
                log("TTS router drained $drained pending translations before stop")
            }
        }
        pipeline?.stop()
        captureManager.stop()
        _state.update {
            it.copy(
                capturing = false,
                pipelineRunning = false,
                routerRunning = false,
                ttsRouterRunning = false,
                vadState = VadState.SILENCE,
                vadProbability = 0f,
                collecting = false,
            )
        }
        log("Pipeline + AST router + TTS router stopped (drain in progress)")
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
                val p = pipeline
                _state.update {
                    it.copy(
                        totalSamplesCaptured = it.totalSamplesCaptured + event.samples.size,
                        vadState = p?.vadState ?: it.vadState,
                        vadProbability = p?.lastProbability ?: it.vadProbability,
                        collecting = p?.isCollecting ?: it.collecting,
                        astQueueSize = router?.queueSize ?: 0,
                        ttsPlaying = ttsPlaying.get(),
                        mutedMicFrames = p?.totalMutedFrames ?: it.mutedMicFrames,
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
                        totalDiscardedLowEnergy = r?.totalDiscardedLowEnergy ?: it.totalDiscardedLowEnergy,
                        totalDiscardedMeta = r?.totalDiscardedMeta ?: it.totalDiscardedMeta,
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
                        totalDiscardedLowEnergy = r?.totalDiscardedLowEnergy ?: it.totalDiscardedLowEnergy,
                        totalDiscardedMeta = r?.totalDiscardedMeta ?: it.totalDiscardedMeta,
                    )
                }
                log("[ERROR] AST: ${event.message}")
            }
            is AudioEvent.TtsAudioReady -> {
                val tr = ttsRouter
                val durationMs = if (event.sampleRate > 0)
                    event.samples.size * 1000 / event.sampleRate else 0
                _state.update {
                    it.copy(
                        totalSynthesized = tr?.totalSynthesized ?: it.totalSynthesized,
                        ttsAvgLatencyMs = tr?.averageLatencyMs ?: it.ttsAvgLatencyMs,
                        ttsQueueSize = tr?.queueSize ?: 0,
                        ttsDropped = tr?.totalDropped ?: it.ttsDropped,
                        lastSpokenText = event.sourceText,
                        lastTtsDurationMs = durationMs,
                    )
                }
                log("TTS ready (${event.latencyMs}ms, ${event.samples.size} samp @${event.sampleRate}Hz): " +
                    event.sourceText.take(60))
                // When Stage B streaming is on, the router already handed
                // the samples to the sink (`beginUtterance` / `play` /
                // `endUtterance` in TtsRouter.processTranslationStreaming),
                // so this event is metrics-only — playing again would
                // duplicate the audio. When streaming is off, the router
                // never touched the player and it's up to us to play.
                if (!ttsConfig.streamingEnabled) {
                    // Fire-and-forget playback. TtsAudioPlayer's internal
                    // Mutex serialises overlapping play() calls, so two
                    // TtsAudioReady events arriving back-to-back are spoken
                    // in order.
                    viewModelScope.launch(Dispatchers.IO) {
                        try {
                            ttsPlayer.play(event.samples)
                            _state.update { it.copy(totalSpoken = it.totalSpoken + 1) }
                        } catch (t: Throwable) {
                            log("[ERROR] TTS playback failed: ${t.javaClass.simpleName}: ${t.message}")
                        }
                    }
                } else {
                    // Router already played. Just tick the spoken counter.
                    _state.update { it.copy(totalSpoken = it.totalSpoken + 1) }
                }
            }
            is AudioEvent.TtsError -> {
                val tr = ttsRouter
                _state.update {
                    it.copy(
                        ttsErrors = tr?.totalErrors ?: (it.ttsErrors + 1),
                        ttsDropped = tr?.totalDropped ?: it.ttsDropped,
                    )
                }
                log("[ERROR] TTS: ${event.message}")
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
