package com.travel2chicago.gemmapipeline.audio

import android.media.audiofx.AcousticEchoCanceler
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val TAG = "AudioCapture"

class AudioCaptureManager(
    private val engine: NativeAudioEngine,
    private val bus: AudioEventBus,
    private val config: AudioEngineConfig,
    private val scope: CoroutineScope,
) {
    @Volatile private var drainJob: Job? = null
    @Volatile private var lastOverflowSeen: Long = 0

    /**
     * Java-side AEC effect attached to the Oboe stream's session id when
     * the caller passes `aecEnabled = true`. Preserved across the drain
     * loop so [stop] can `release()` it (calling release() while the
     * effect is still bound leaks a native audiofx handle).
     */
    @Volatile private var aec: AcousticEchoCanceler? = null

    val isRunning: Boolean get() = drainJob?.isActive == true

    /**
     * @param inputDeviceId 0 = system default; otherwise `AudioDeviceInfo.id`.
     * @param aecEnabled when `true`, opens the Oboe capture stream with
     *   `InputPreset::VoiceCommunication` (HAL-level AEC — the AAudio
     *   equivalent of `MediaRecorder.AudioSource.VOICE_COMMUNICATION`)
     *   AND attaches an `android.media.audiofx.AcousticEchoCanceler` to
     *   the stream's allocated session id when the framework reports
     *   the effect is available on this device. Both layers subtract
     *   the loopback of our own playback (Kokoro / Android TTS through
     *   the JBL / built-in speaker) from the mic signal, letting us
     *   run full-duplex without the mic re-capturing what the speaker
     *   just produced.
     *
     *   When `false`, the stream opens with `InputPreset::Unprocessed`
     *   (Fase 2 baseline: raw mic samples, no HAL processing). Used
     *   when validating VAD/chunker tuning without HAL interference,
     *   or as a kill switch if AEC clips the user's voice on a
     *   specific device.
     */
    fun start(inputDeviceId: Int = 0, aecEnabled: Boolean = false): Boolean {
        if (isRunning) {
            Log.w(TAG, "start: already running, ignoring")
            return true
        }
        val preset = if (aecEnabled) InputPreset.VOICE_COMMUNICATION else InputPreset.UNPROCESSED
        // We only allocate a session id when AEC is on — the Java effect
        // needs a real id, but allocating an id we never use adds a small
        // startup cost for zero benefit.
        if (!engine.startCapture(inputDeviceId, preset, allocateSessionId = aecEnabled)) {
            Log.e(TAG, "engine.startCapture failed for deviceId=$inputDeviceId preset=$preset")
            return false
        }
        if (aecEnabled) attachAec()
        lastOverflowSeen = 0
        drainJob = scope.launch(Dispatchers.IO) { drainLoop() }
        Log.i(TAG, "Capture started (deviceId=$inputDeviceId, routed=${engine.captureRoutedDeviceId()}, " +
            "preset=$preset, aecAttached=${aec != null})")
        return true
    }

    fun stop() {
        val job = drainJob ?: return
        drainJob = null
        job.cancel()
        releaseAec()
        engine.stopCapture()
        Log.i(TAG, "Capture stopped")
    }

    /**
     * Attach a Java `AcousticEchoCanceler` to the Oboe stream's session id
     * if the framework reports the effect is available on this device.
     * Called from [start] after `engine.startCapture` succeeds. Failures
     * (effect unavailable, session id invalid, create() returns null) are
     * logged and swallowed — the HAL-level AEC from the input preset is
     * the primary AEC path and this Java effect is defence-in-depth.
     */
    private fun attachAec() {
        if (!AcousticEchoCanceler.isAvailable()) {
            Log.w(TAG, "AEC: framework reports AcousticEchoCanceler not available on this device")
            return
        }
        val sessionId = engine.captureSessionId()
        if (sessionId <= 0) {
            Log.w(TAG, "AEC: capture session id is $sessionId — cannot attach effect " +
                "(stream may have been opened without SessionId::Allocate)")
            return
        }
        val effect = try {
            AcousticEchoCanceler.create(sessionId)
        } catch (t: Throwable) {
            Log.w(TAG, "AEC: create() threw on sessionId=$sessionId", t)
            null
        }
        if (effect == null) {
            Log.w(TAG, "AEC: create(sessionId=$sessionId) returned null")
            return
        }
        val enableRc = try { effect.enabled = true; 0 } catch (t: Throwable) {
            Log.w(TAG, "AEC: enable threw", t); -1
        }
        Log.i(TAG, "AEC attached to sessionId=$sessionId, enabled=${effect.enabled} (rc=$enableRc)")
        aec = effect
    }

    private fun releaseAec() {
        val e = aec ?: return
        aec = null
        runCatching { e.enabled = false }
        runCatching { e.release() }
        Log.i(TAG, "AEC released")
    }

    private suspend fun drainLoop() {
        val blockSize = config.format.blockSize
        val drainBuffer = ShortArray(blockSize * 4)
        var cycles = 0L
        var totalDrained = 0L
        var nonZeroEmits = 0L

        while (currentCoroutineContext().isActive) {
            val n = engine.drainCapture(drainBuffer)
            if (n > 0) {
                totalDrained += n
                nonZeroEmits += 1
                if (nonZeroEmits == 1L) {
                    bus.emit(AudioEvent.EngineStatus(
                        "Drain coroutine: first non-empty drain ($n samples)"))
                    Log.i(TAG, "First non-empty drain: $n samples")
                }
                val payload = drainBuffer.copyOfRange(0, n)
                val accepted = bus.emit(
                    AudioEvent.AudioData(
                        samples = payload,
                        frameCount = n / config.format.channelCount,
                        timestampNs = System.nanoTime(),
                    ),
                )
                if (!accepted) {
                    Log.w(TAG, "bus.emit returned false (subscribers slow?) — dropped oldest")
                }
            }

            val totalOverflow = engine.captureOverflowCount()
            if (totalOverflow > lastOverflowSeen) {
                val dropped = totalOverflow - lastOverflowSeen
                lastOverflowSeen = totalOverflow
                bus.emit(AudioEvent.BufferOverflow(dropped))
                Log.w(TAG, "Ring buffer dropped $dropped samples (total=$totalOverflow)")
            }

            cycles += 1
            if (cycles % 100L == 0L) {
                Log.i(TAG, "Drain heartbeat: cycles=$cycles nonEmptyEmits=$nonZeroEmits " +
                    "totalDrained=$totalDrained samples")
            }

            delay(config.drainIntervalMs)
        }
    }
}
