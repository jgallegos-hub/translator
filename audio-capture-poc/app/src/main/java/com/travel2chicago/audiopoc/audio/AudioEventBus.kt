package com.travel2chicago.audiopoc.audio

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Coroutine-native replacement for the Python `EventBus` + `janus.Queue`.
 *
 * `tryEmit` is thread-safe and never blocks, so it is safe to call from
 * non-coroutine threads (in this POC: the JNI bridge or the Android
 * AudioDeviceCallback). `extraBufferCapacity` is sized for ~1 s of audio
 * events at 16 kHz / 512-sample blocks (≈ 32 events/s × 2 s = 64).
 */
class AudioEventBus(
    bufferCapacity: Int = 64,
) {
    private val _events = MutableSharedFlow<AudioEvent>(
        replay = 0,
        extraBufferCapacity = bufferCapacity,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Hot flow consumed by feature pipelines. Collect on the coroutine of your choice. */
    val events: SharedFlow<AudioEvent> = _events.asSharedFlow()

    /**
     * Emit non-blocking. Returns `false` if the buffer is saturated and the
     * oldest event had to be dropped (caller may want to log).
     */
    fun emit(event: AudioEvent): Boolean = _events.tryEmit(event)
}
