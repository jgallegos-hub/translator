package com.travel2chicago.audiohwcheck

import android.app.Application
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

private const val TAG = "AudioHwCheck"

private const val SAMPLE_RATE = 16_000
private const val RECORD_DURATION_MS = 5_000
private const val RECORD_TOTAL_SAMPLES = SAMPLE_RATE * RECORD_DURATION_MS / 1000  // 80,000

/**
 * Describes one detected audio device (input or output).
 *
 * @property displayName Human readable label like "Saramonic Mic (USB_HEADSET)"
 * @property isPreferred true when this device matches the target (Saramonic / JBL Go 4)
 */
data class DeviceEntry(
    val info: AudioDeviceInfo,
    val displayName: String,
    val isPreferred: Boolean,
) {
    val id: Int get() = info.id
}

/**
 * UI state for the hardware-check screen.
 *
 * `recordedSamples` is intentionally kept in the state so playback can read it
 * back from RAM — there is no file persistence in this POC.
 */
data class HwCheckState(
    val inputDevices: List<DeviceEntry> = emptyList(),
    val outputDevices: List<DeviceEntry> = emptyList(),
    val selectedInputId: Int? = null,
    val selectedOutputId: Int? = null,

    val hasRecordPermission: Boolean = false,
    val hasBluetoothPermission: Boolean = false,

    val recording: Boolean = false,
    val playing: Boolean = false,

    val recordedSamples: ShortArray? = null,
    val recordedDurationMs: Long = 0,
    val recordedRmsAvg: Double = 0.0,
    val recordingRoutedDeviceName: String? = null,
    val recordingRoutingMatchesPreferred: Boolean? = null,

    val playbackOk: Boolean? = null,
    val playbackRoutedDeviceName: String? = null,
    val playbackRoutingMatchesPreferred: Boolean? = null,

    val logs: List<String> = emptyList(),
    val error: String? = null,
) {
    val selectedInput: DeviceEntry? get() = inputDevices.firstOrNull { it.id == selectedInputId }
    val selectedOutput: DeviceEntry? get() = outputDevices.firstOrNull { it.id == selectedOutputId }
}

class AudioHwCheckViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(HwCheckState())
    val state: StateFlow<HwCheckState> = _state.asStateFlow()

    private val audioManager: AudioManager =
        app.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    // Re-detect devices when something is plugged or unplugged.
    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            log("Hot-plug: ${addedDevices?.size ?: 0} device(s) added")
            detectDevices()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            log("Hot-plug: ${removedDevices?.size ?: 0} device(s) removed")
            detectDevices()
        }
    }

    init {
        audioManager.registerAudioDeviceCallback(deviceCallback, Handler(Looper.getMainLooper()))
        detectDevices()
    }

    override fun onCleared() {
        super.onCleared()
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
    }

    // ── Logging ──────────────────────────────────────────────────────────────

    private fun log(msg: String) {
        Log.i(TAG, msg)
        _state.update {
            it.copy(logs = (it.logs + "[${System.currentTimeMillis() % 100_000}] $msg").takeLast(200))
        }
    }

    private fun logError(msg: String, t: Throwable? = null) {
        Log.e(TAG, msg, t)
        _state.update {
            it.copy(
                logs = (it.logs + "[ERROR] $msg${t?.let { e -> ": ${e.message}" } ?: ""}").takeLast(200),
                error = msg,
            )
        }
    }

    // ── Permissions ──────────────────────────────────────────────────────────

    /** Called from MainActivity after a permission request resolves. */
    fun onPermissionsUpdated(hasRecord: Boolean, hasBluetooth: Boolean) {
        _state.update { it.copy(hasRecordPermission = hasRecord, hasBluetoothPermission = hasBluetooth) }
        log("Permissions: RECORD_AUDIO=$hasRecord, BLUETOOTH_CONNECT=$hasBluetooth")
        // Some devices only expose BT devices after BLUETOOTH_CONNECT is granted.
        detectDevices()
    }

    // ── Device detection ─────────────────────────────────────────────────────

    /**
     * Enumerate audio devices, classify them, and auto-select the preferred
     * Saramonic USB input + JBL Go 4 A2DP output when present.
     *
     * Re-runs on every hot-plug callback.
     */
    fun detectDevices() {
        val inputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)

        val inputEntries = inputs.map { it.toEntry(isInput = true) }
        val outputEntries = outputs.map { it.toEntry(isInput = false) }

        // Auto-select preferred when present; otherwise keep any prior selection
        // that still exists; otherwise pick the first reasonable candidate.
        val previousInputId = _state.value.selectedInputId
        val selectedInputId = inputEntries.firstOrNull { it.isPreferred }?.id
            ?: inputEntries.firstOrNull { it.id == previousInputId }?.id
            ?: inputEntries.firstOrNull { it.info.type.isUsbInput() }?.id
            ?: inputEntries.firstOrNull()?.id

        val previousOutputId = _state.value.selectedOutputId
        val selectedOutputId = outputEntries.firstOrNull { it.isPreferred }?.id
            ?: outputEntries.firstOrNull { it.id == previousOutputId }?.id
            ?: outputEntries.firstOrNull { it.info.type.isBluetoothOutput() }?.id
            ?: outputEntries.firstOrNull()?.id

        _state.update {
            it.copy(
                inputDevices = inputEntries,
                outputDevices = outputEntries,
                selectedInputId = selectedInputId,
                selectedOutputId = selectedOutputId,
            )
        }

        log("Inputs: ${inputEntries.size} | Outputs: ${outputEntries.size}")
        inputEntries.forEach { log("  IN  ${it.displayName}${if (it.isPreferred) " ★" else ""}") }
        outputEntries.forEach { log("  OUT ${it.displayName}${if (it.isPreferred) " ★" else ""}") }
    }

    fun selectInput(deviceId: Int) {
        _state.update { it.copy(selectedInputId = deviceId) }
    }

    fun selectOutput(deviceId: Int) {
        _state.update { it.copy(selectedOutputId = deviceId) }
    }

    private fun AudioDeviceInfo.toEntry(isInput: Boolean): DeviceEntry {
        val typeName = typeName(type)
        val product = productName?.toString()?.trim().orEmpty()
        val display = if (product.isNotEmpty()) "$product [$typeName]" else "[$typeName] (id=$id)"
        val productLower = product.lowercase()

        val preferred = if (isInput) {
            // Saramonic typically enumerates as USB_HEADSET or USB_DEVICE
            type.isUsbInput() && (productLower.contains("saramonic") ||
                productLower.contains("usb") ||
                productLower.isEmpty())
        } else {
            // JBL Go 4 is BLUETOOTH_A2DP
            type.isBluetoothOutput() && (productLower.contains("jbl") ||
                productLower.contains("go") ||
                productLower.isEmpty())
        }

        return DeviceEntry(info = this, displayName = display, isPreferred = preferred)
    }

    // ── Recording ────────────────────────────────────────────────────────────

    /**
     * Capture [RECORD_DURATION_MS] ms from the currently selected input device.
     *
     * Uses [MediaRecorder.AudioSource.MIC] for max compatibility — UNPROCESSED
     * can fail on some USB devices on MIUI. Forces routing to the preferred
     * device with [AudioRecord.setPreferredDevice] and verifies via
     * [AudioRecord.getRoutedDevice] post-start.
     */
    fun startRecording() {
        if (_state.value.recording) return
        if (!_state.value.hasRecordPermission) {
            logError("Cannot record: RECORD_AUDIO permission not granted")
            return
        }
        val inputDevice = _state.value.selectedInput
        if (inputDevice == null) {
            logError("No input device selected")
            return
        }

        _state.update {
            it.copy(
                recording = true,
                error = null,
                playbackOk = null,
                playbackRoutedDeviceName = null,
                playbackRoutingMatchesPreferred = null,
            )
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                doRecord(inputDevice)
            } catch (e: SecurityException) {
                logError("RECORD_AUDIO permission denied at runtime", e)
                _state.update { it.copy(recording = false) }
            } catch (e: Exception) {
                logError("Recording failed", e)
                _state.update { it.copy(recording = false) }
            }
        }
    }

    private suspend fun doRecord(inputDevice: DeviceEntry) = withContext(Dispatchers.IO) {
        log("=== RECORDING ===")
        log("Target device: ${inputDevice.displayName} (id=${inputDevice.id})")

        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val encoding = AudioFormat.ENCODING_PCM_16BIT

        val minBytes = AudioRecord.getMinBufferSize(SAMPLE_RATE, channelConfig, encoding)
        if (minBytes <= 0) {
            logError("getMinBufferSize returned $minBytes — config not supported")
            _state.update { it.copy(recording = false) }
            return@withContext
        }
        val bufferBytes = (minBytes * 2).coerceAtLeast(SAMPLE_RATE * 2 / 5)  // ≥200ms buffer
        log("minBuffer=${minBytes}B, using=${bufferBytes}B")

        val record = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.MIC)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(channelConfig)
                    .setEncoding(encoding)
                    .build(),
            )
            .setBufferSizeInBytes(bufferBytes)
            .build()

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            logError("AudioRecord failed to initialize (state=${record.state})")
            record.release()
            _state.update { it.copy(recording = false) }
            return@withContext
        }

        // Request explicit routing to the USB mic. On MIUI this is honored
        // when the device is plugged in and RECORD_AUDIO is granted.
        val routingRequested = record.setPreferredDevice(inputDevice.info)
        log("setPreferredDevice → $routingRequested")

        val captured = ShortArray(RECORD_TOTAL_SAMPLES)
        var totalRead = 0
        val readBuf = ShortArray(1024)
        val startNs = System.nanoTime()

        record.startRecording()

        // Verify actual routing once the stream is running.
        val routed = record.routedDevice
        val routedName = routed?.let { describe(it) } ?: "(unknown)"
        val routingOk = routed?.id == inputDevice.id
        log("Routed to: $routedName ${if (routingOk) "✓ matches preferred" else "✗ differs"}")

        while (totalRead < RECORD_TOTAL_SAMPLES) {
            val toRead = minOf(readBuf.size, RECORD_TOTAL_SAMPLES - totalRead)
            val n = record.read(readBuf, 0, toRead)
            if (n <= 0) {
                logError("AudioRecord.read returned $n at totalRead=$totalRead — aborting")
                break
            }
            System.arraycopy(readBuf, 0, captured, totalRead, n)
            totalRead += n
        }

        val durationMs = (System.nanoTime() - startNs) / 1_000_000
        record.stop()
        record.release()

        val rms = rms(captured, totalRead)
        log("Captured $totalRead samples (${"%.2f".format(totalRead.toFloat() / SAMPLE_RATE)}s) in ${durationMs}ms")
        log("RMS avg: ${"%.1f".format(rms)} ${if (rms > 100.0) "(signal OK)" else "(silent or very quiet)"}")

        _state.update {
            it.copy(
                recording = false,
                recordedSamples = captured.copyOf(totalRead),
                recordedDurationMs = durationMs,
                recordedRmsAvg = rms,
                recordingRoutedDeviceName = routedName,
                recordingRoutingMatchesPreferred = routingOk,
            )
        }
    }

    // ── Playback ─────────────────────────────────────────────────────────────

    /**
     * Play the buffer captured by [startRecording] through the currently
     * selected output device. Uses [AudioTrack.MODE_STATIC] so we can write
     * once and let the system stream from the internal buffer.
     */
    fun playRecording() {
        if (_state.value.playing) return
        val samples = _state.value.recordedSamples
        if (samples == null || samples.isEmpty()) {
            logError("Nothing to play — record first")
            return
        }
        val outputDevice = _state.value.selectedOutput
        if (outputDevice == null) {
            logError("No output device selected")
            return
        }

        _state.update {
            it.copy(
                playing = true,
                error = null,
                playbackOk = null,
                playbackRoutedDeviceName = null,
                playbackRoutingMatchesPreferred = null,
            )
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                doPlay(samples, outputDevice)
            } catch (e: Exception) {
                logError("Playback failed", e)
                _state.update { it.copy(playing = false, playbackOk = false) }
            }
        }
    }

    private suspend fun doPlay(samples: ShortArray, outputDevice: DeviceEntry) = withContext(Dispatchers.IO) {
        log("=== PLAYBACK ===")
        log("Target device: ${outputDevice.displayName} (id=${outputDevice.id})")
        log("Playing ${samples.size} samples (${"%.2f".format(samples.size.toFloat() / SAMPLE_RATE)}s)")

        // Diagnostic: what does the system think about audio mode / A2DP right now?
        log(
            "AudioManager: mode=${audioModeName(audioManager.mode)}, " +
                "a2dpOn=${audioManager.isBluetoothA2dpOn}, " +
                "speakerOn=${audioManager.isSpeakerphoneOn}",
        )

        // Make sure we are in MODE_NORMAL so USAGE_MEDIA routes to A2DP/speaker, not earpiece.
        val previousMode = audioManager.mode
        if (audioManager.mode != AudioManager.MODE_NORMAL) {
            audioManager.mode = AudioManager.MODE_NORMAL
            log("Switched AudioManager mode → MODE_NORMAL (was ${audioModeName(previousMode)})")
        }

        val byteSize = samples.size * 2  // 16-bit samples
        val format = AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .build()
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        val track = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(format)
            .setBufferSizeInBytes(byteSize)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

        // For MODE_STATIC the initial state is STATE_NO_STATIC_DATA (= 2) — that is
        // NOT a failure, it just means "I'm waiting for you to write() data". Only
        // STATE_UNINITIALIZED (= 0) is a real failure here.
        val initialState = track.state
        log("AudioTrack initial state: ${trackStateName(initialState)} ($initialState)")
        if (initialState == AudioTrack.STATE_UNINITIALIZED) {
            logError("AudioTrack failed to initialize (state=UNINITIALIZED)")
            track.release()
            audioManager.mode = previousMode
            _state.update { it.copy(playing = false, playbackOk = false) }
            return@withContext
        }

        // Pin routing BEFORE write so the system has the target chosen by the time
        // it commits the buffer. Honored when the JBL is paired + connected as A2DP.
        val routingRequested = track.setPreferredDevice(outputDevice.info)
        log("setPreferredDevice(${outputDevice.displayName}) → $routingRequested")

        // Write entire buffer — for MODE_STATIC this is mandatory before play().
        val written = track.write(samples, 0, samples.size)
        log("AudioTrack.write returned $written / ${samples.size}")
        if (written <= 0) {
            logError("AudioTrack.write failed with code $written")
            track.release()
            audioManager.mode = previousMode
            _state.update { it.copy(playing = false, playbackOk = false) }
            return@withContext
        }

        // After a successful write, MODE_STATIC tracks transition to STATE_INITIALIZED.
        val postWriteState = track.state
        log("AudioTrack post-write state: ${trackStateName(postWriteState)} ($postWriteState)")
        if (postWriteState != AudioTrack.STATE_INITIALIZED) {
            logError("AudioTrack not initialized after write (state=${trackStateName(postWriteState)})")
            track.release()
            audioManager.mode = previousMode
            _state.update { it.copy(playing = false, playbackOk = false) }
            return@withContext
        }

        track.play()

        // Verify actual routing once playback is running.
        val routed = track.routedDevice
        val routedName = routed?.let { describe(it) } ?: "(unknown)"
        val routingOk = routed?.id == outputDevice.id
        log("Routed to: $routedName ${if (routingOk) "✓ matches preferred" else "✗ differs from ${outputDevice.displayName}"}")

        // Block until the buffer has drained (track length + small margin).
        val durationMs = (samples.size * 1000L / SAMPLE_RATE) + 250
        Thread.sleep(durationMs)

        track.stop()
        track.release()
        if (audioManager.mode != previousMode) {
            audioManager.mode = previousMode
        }
        log("Playback complete (${durationMs}ms)")

        _state.update {
            it.copy(
                playing = false,
                playbackOk = true,
                playbackRoutedDeviceName = routedName,
                playbackRoutingMatchesPreferred = routingOk,
            )
        }
    }

    private fun trackStateName(state: Int): String = when (state) {
        AudioTrack.STATE_UNINITIALIZED -> "UNINITIALIZED"
        AudioTrack.STATE_INITIALIZED -> "INITIALIZED"
        AudioTrack.STATE_NO_STATIC_DATA -> "NO_STATIC_DATA"
        else -> "UNKNOWN"
    }

    private fun audioModeName(mode: Int): String = when (mode) {
        AudioManager.MODE_NORMAL -> "NORMAL"
        AudioManager.MODE_RINGTONE -> "RINGTONE"
        AudioManager.MODE_IN_CALL -> "IN_CALL"
        AudioManager.MODE_IN_COMMUNICATION -> "IN_COMMUNICATION"
        AudioManager.MODE_CALL_SCREENING -> "CALL_SCREENING"
        AudioManager.MODE_CURRENT -> "CURRENT"
        AudioManager.MODE_INVALID -> "INVALID"
        else -> "MODE_$mode"
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** RMS of the first [length] samples of [buffer]. Returns 0 for empty input. */
    private fun rms(buffer: ShortArray, length: Int): Double {
        if (length <= 0) return 0.0
        var sumSq = 0.0
        for (i in 0 until length) {
            val s = buffer[i].toDouble()
            sumSq += s * s
        }
        return sqrt(sumSq / length)
    }

    private fun describe(info: AudioDeviceInfo): String {
        val product = info.productName?.toString()?.trim().orEmpty()
        val type = typeName(info.type)
        return if (product.isNotEmpty()) "$product [$type] (id=${info.id})" else "[$type] (id=${info.id})"
    }
}

// ── Free functions for AudioDeviceInfo.type ──────────────────────────────────

internal fun Int.isUsbInput(): Boolean = this == AudioDeviceInfo.TYPE_USB_DEVICE ||
    this == AudioDeviceInfo.TYPE_USB_HEADSET ||
    this == AudioDeviceInfo.TYPE_USB_ACCESSORY

internal fun Int.isBluetoothOutput(): Boolean = this == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
    this == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
    this == AudioDeviceInfo.TYPE_BLE_HEADSET ||
    this == AudioDeviceInfo.TYPE_BLE_SPEAKER

/** Human-readable name for an [AudioDeviceInfo.TYPE_*] constant. */
internal fun typeName(type: Int): String = when (type) {
    AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "BUILTIN_EARPIECE"
    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "BUILTIN_SPEAKER"
    AudioDeviceInfo.TYPE_BUILTIN_MIC -> "BUILTIN_MIC"
    AudioDeviceInfo.TYPE_WIRED_HEADSET -> "WIRED_HEADSET"
    AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "WIRED_HEADPHONES"
    AudioDeviceInfo.TYPE_USB_DEVICE -> "USB_DEVICE"
    AudioDeviceInfo.TYPE_USB_HEADSET -> "USB_HEADSET"
    AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB_ACCESSORY"
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "BLUETOOTH_A2DP"
    AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "BLUETOOTH_SCO"
    AudioDeviceInfo.TYPE_BLE_HEADSET -> "BLE_HEADSET"
    AudioDeviceInfo.TYPE_BLE_SPEAKER -> "BLE_SPEAKER"
    AudioDeviceInfo.TYPE_HDMI -> "HDMI"
    AudioDeviceInfo.TYPE_TELEPHONY -> "TELEPHONY"
    AudioDeviceInfo.TYPE_DOCK -> "DOCK"
    AudioDeviceInfo.TYPE_FM -> "FM"
    AudioDeviceInfo.TYPE_AUX_LINE -> "AUX_LINE"
    AudioDeviceInfo.TYPE_LINE_ANALOG -> "LINE_ANALOG"
    AudioDeviceInfo.TYPE_LINE_DIGITAL -> "LINE_DIGITAL"
    AudioDeviceInfo.TYPE_REMOTE_SUBMIX -> "REMOTE_SUBMIX"
    else -> "TYPE_$type"
}
