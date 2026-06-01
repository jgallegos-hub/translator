package com.travel2chicago.audiopoc

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.travel2chicago.audiopoc.audio.AudioCaptureManager
import com.travel2chicago.audiopoc.audio.AudioDeviceManager
import com.travel2chicago.audiopoc.audio.AudioEngineConfig
import com.travel2chicago.audiopoc.audio.AudioEvent
import com.travel2chicago.audiopoc.audio.AudioEventBus
import com.travel2chicago.audiopoc.audio.AudioPlaybackManager
import com.travel2chicago.audiopoc.audio.NativeAudioEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.sqrt

private const val TAG = "AudioPoc"

/**
 * UI state for the POC. All fields are immutable snapshots so Compose can
 * recompose on any field change.
 */
data class AudioPocUiState(
    val inputDevices: List<AudioDeviceManager.DeviceEntry> = emptyList(),
    val outputDevices: List<AudioDeviceManager.DeviceEntry> = emptyList(),
    val selectedInputId: Int? = null,
    val selectedOutputId: Int? = null,

    val hasRecordPermission: Boolean = false,
    val hasBluetoothPermission: Boolean = false,

    val capturing: Boolean = false,
    val playing: Boolean = false,
    val loopback: Boolean = false,

    val captureRoutedDeviceId: Int = -1,
    val playbackRoutedDeviceId: Int = -1,
    val captureSampleRate: Int = -1,
    val playbackSampleRate: Int = -1,
    val captureLatencyMs: Int = -1,
    val playbackLatencyMs: Int = -1,
    /** "Oboe" or "AudioTrack" — which back-end the playback sink is using. */
    val playbackSinkLabel: String = "—",

    val totalSamplesCaptured: Long = 0,
    val overflowCount: Long = 0,
    val underflowCount: Long = 0,

    /** VU meter level: most recent chunk RMS / Short.MAX_VALUE, clamped [0..1]. */
    val vuLevel: Float = 0f,
    /** Most recent chunk peak (signed short magnitude) — useful for the log. */
    val lastChunkPeak: Int = 0,
    /** Frames per drained chunk (engine cadence). */
    val lastChunkFrames: Int = 0,

    val logs: List<String> = emptyList(),
    val error: String? = null,
) {
    val selectedInput get() = inputDevices.firstOrNull { it.id == selectedInputId }
    val selectedOutput get() = outputDevices.firstOrNull { it.id == selectedOutputId }
}

class AudioPocViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(AudioPocUiState())
    val state: StateFlow<AudioPocUiState> = _state.asStateFlow()

    private val config = AudioEngineConfig()
    private val bus = AudioEventBus()
    private val engine: NativeAudioEngine = NativeAudioEngine.create(config)
    private val deviceManager = AudioDeviceManager(app, bus)
    private val captureManager = AudioCaptureManager(engine, bus, config, viewModelScope)
    private val playbackManager = AudioPlaybackManager(app, engine, config, viewModelScope)

    init {
        deviceManager.register()
        refreshDevices()
        // Forward audio bus events into the UI state / log.
        viewModelScope.launch(Dispatchers.Default) {
            bus.events.collect { event -> handleEvent(event) }
        }
    }

    override fun onCleared() {
        super.onCleared()
        captureManager.stop()
        playbackManager.stop()
        deviceManager.unregister()
        engine.close()
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

    fun selectInput(deviceId: Int) {
        _state.update { it.copy(selectedInputId = deviceId) }
    }

    fun selectOutput(deviceId: Int) {
        _state.update { it.copy(selectedOutputId = deviceId) }
    }

    // ── Capture ─────────────────────────────────────────────────────────────

    fun startCapture() {
        if (_state.value.capturing) return
        if (!_state.value.hasRecordPermission) {
            logError("Cannot start capture: RECORD_AUDIO not granted")
            return
        }
        val inputDeviceId = _state.value.selectedInputId ?: 0
        log("Starting capture (selected deviceId=$inputDeviceId)")
        val ok = captureManager.start(inputDeviceId)
        if (!ok) {
            logError("captureManager.start returned false")
            return
        }
        _state.update {
            it.copy(
                capturing = true,
                error = null,
                totalSamplesCaptured = 0,
                overflowCount = 0,
                captureRoutedDeviceId = engine.captureRoutedDeviceId(),
                captureSampleRate = engine.actualSampleRateCapture(),
                captureLatencyMs = engine.captureLatencyMs(),
            )
        }
    }

    fun stopCapture() {
        if (!_state.value.capturing) return
        captureManager.stop()
        _state.update { it.copy(capturing = false, vuLevel = 0f) }
        log("Capture stopped")
    }

    // ── Playback ────────────────────────────────────────────────────────────

    fun startPlayback() {
        if (_state.value.playing) return
        val outputDevice = _state.value.selectedOutput
        log("Starting playback (selected=${outputDevice?.displayName ?: "default"})")
        val ok = playbackManager.start(outputDevice)
        if (!ok) {
            logError("playbackManager.start returned false")
            return
        }
        log("Sink: ${playbackManager.activeSinkLabel}")
        _state.update {
            it.copy(
                playing = true,
                error = null,
                playbackSinkLabel = playbackManager.activeSinkLabel ?: "?",
                playbackRoutedDeviceId = playbackManager.routedDeviceId(),
                playbackSampleRate = playbackManager.sampleRate(),
                playbackLatencyMs = playbackManager.latencyMs(),
            )
        }
    }

    fun stopPlayback() {
        if (!_state.value.playing) return
        playbackManager.stop()
        _state.update { it.copy(playing = false, loopback = false) }
        log("Playback stopped")
    }

    fun playSineWave() {
        if (!_state.value.playing) startPlayback()
        playbackManager.playSineWave(freqHz = 440f, durationMs = 1500, amplitude = 0.4f)
        log("Sine wave 440Hz 1.5s enqueued")
    }

    /**
     * Toggle loop-back: route captured PCM into the playback ring. Useful for
     * confirming the full round-trip in one tap. Requires capture and playback
     * to both be active.
     */
    fun setLoopback(enabled: Boolean) {
        _state.update { it.copy(loopback = enabled) }
        log("Loopback ${if (enabled) "ENABLED" else "disabled"}")
    }

    // ── Event handling ──────────────────────────────────────────────────────

    private suspend fun handleEvent(event: AudioEvent) {
        when (event) {
            is AudioEvent.AudioData -> {
                val rms = rms(event.samples)
                val peak = peak(event.samples)
                // rms is Double; convert to Float for the Compose state.
                val vu = (rms / Short.MAX_VALUE).toFloat().coerceIn(0f, 1f)
                _state.update {
                    it.copy(
                        totalSamplesCaptured = it.totalSamplesCaptured + event.samples.size,
                        vuLevel = vu,
                        lastChunkPeak = peak,
                        lastChunkFrames = event.frameCount,
                        captureLatencyMs = engine.captureLatencyMs(),
                        playbackLatencyMs = playbackManager.latencyMs(),
                        underflowCount = playbackManager.underflowOrDropCount(),
                    )
                }
                if (_state.value.loopback && _state.value.playing) {
                    // Goes through whichever sink (Oboe or AudioTrack) is active.
                    playbackManager.feedLoopback(event.samples)
                }
            }
            is AudioEvent.BufferOverflow -> {
                _state.update {
                    it.copy(
                        overflowCount = it.overflowCount + event.droppedSamples,
                    )
                }
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

    // ── Helpers ─────────────────────────────────────────────────────────────

    private fun rms(samples: ShortArray): Double {
        if (samples.isEmpty()) return 0.0
        var sumSq = 0.0
        for (s in samples) {
            val v = s.toDouble()
            sumSq += v * v
        }
        return sqrt(sumSq / samples.size)
    }

    private fun peak(samples: ShortArray): Int {
        var max = 0
        for (s in samples) {
            val abs = if (s.toInt() == Short.MIN_VALUE.toInt()) Short.MAX_VALUE.toInt() else kotlin.math.abs(s.toInt())
            if (abs > max) max = abs
        }
        return max
    }

    private fun log(msg: String) {
        Log.i(TAG, msg)
        _state.update {
            it.copy(logs = (it.logs + "[${System.currentTimeMillis() % 100_000}] $msg").takeLast(300))
        }
    }

    private fun logError(msg: String, t: Throwable? = null) {
        Log.e(TAG, msg, t)
        _state.update {
            it.copy(
                logs = (it.logs + "[ERROR] $msg${t?.let { e -> ": ${e.message}" } ?: ""}").takeLast(300),
                error = msg,
            )
        }
    }
}
