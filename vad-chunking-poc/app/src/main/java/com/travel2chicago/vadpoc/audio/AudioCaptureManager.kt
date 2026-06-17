package com.travel2chicago.vadpoc.audio

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

    val isRunning: Boolean get() = drainJob?.isActive == true

    fun start(inputDeviceId: Int = 0): Boolean {
        if (isRunning) {
            Log.w(TAG, "start: already running, ignoring")
            return true
        }
        if (!engine.startCapture(inputDeviceId)) {
            Log.e(TAG, "engine.startCapture failed for deviceId=$inputDeviceId")
            return false
        }
        lastOverflowSeen = 0
        drainJob = scope.launch(Dispatchers.IO) { drainLoop() }
        Log.i(TAG, "Capture started (deviceId=$inputDeviceId, routed=${engine.captureRoutedDeviceId()})")
        return true
    }

    fun stop() {
        val job = drainJob ?: return
        drainJob = null
        job.cancel()
        engine.stopCapture()
        Log.i(TAG, "Capture stopped")
    }

    private suspend fun drainLoop() {
        val blockSize = config.format.blockSize
        val drainBuffer = ShortArray(blockSize * 4)

        while (currentCoroutineContext().isActive) {
            val n = engine.drainCapture(drainBuffer)
            if (n > 0) {
                val payload = drainBuffer.copyOfRange(0, n)
                bus.emit(
                    AudioEvent.AudioData(
                        samples = payload,
                        frameCount = n / config.format.channelCount,
                        timestampNs = System.nanoTime(),
                    ),
                )
            }

            val totalOverflow = engine.captureOverflowCount()
            if (totalOverflow > lastOverflowSeen) {
                val dropped = totalOverflow - lastOverflowSeen
                lastOverflowSeen = totalOverflow
                bus.emit(AudioEvent.BufferOverflow(dropped))
                Log.w(TAG, "Ring buffer dropped $dropped samples (total=$totalOverflow)")
            }

            delay(config.drainIntervalMs)
        }
    }
}
