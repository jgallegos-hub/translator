package com.travel2chicago.vadpoc.audio

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Coroutine-native event bus. `tryEmit` is thread-safe and never blocks, so it
 * is safe to call from the JNI bridge, the Android `AudioDeviceCallback`, or
 * any worker coroutine.
 *
 * `extraBufferCapacity` is sized so a few seconds of audio events fit even if
 * a slow collector is briefly back-pressured.
 */
class AudioEventBus(
    bufferCapacity: Int = 64,
) {
    private val _events = MutableSharedFlow<AudioEvent>(
        replay = 0,
        extraBufferCapacity = bufferCapacity,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val events: SharedFlow<AudioEvent> = _events.asSharedFlow()

    fun emit(event: AudioEvent): Boolean = _events.tryEmit(event)
}
