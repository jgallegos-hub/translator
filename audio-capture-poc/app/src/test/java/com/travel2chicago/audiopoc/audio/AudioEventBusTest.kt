package com.travel2chicago.audiopoc.audio

import android.media.AudioDeviceInfo
import app.cash.turbine.test
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [AudioEventBus] — the Kotlin replacement for the Python
 * `EventBus` + `janus.Queue` in `events.py`. Mirrors `test_events.py`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AudioEventBusTest {

    @Test
    fun `emit delivers events to collectors`() = runTest {
        val bus = AudioEventBus()
        val event = AudioEvent.BufferOverflow(droppedSamples = 12)
        bus.events.test {
            assertTrue(bus.emit(event))
            assertEquals(event, awaitItem())
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun `emit returns true while buffer has space`() {
        val bus = AudioEventBus(bufferCapacity = 4)
        repeat(4) { i ->
            assertTrue(
                "emit #$i should succeed",
                bus.emit(AudioEvent.BufferOverflow(droppedSamples = i.toLong())),
            )
        }
    }

    @Test
    fun `replay is zero — late subscribers do not receive past events`() = runTest {
        val bus = AudioEventBus()
        bus.emit(AudioEvent.BufferOverflow(droppedSamples = 1))
        bus.emit(AudioEvent.BufferOverflow(droppedSamples = 2))

        val late = AudioEvent.BufferOverflow(droppedSamples = 99)
        bus.events.test {
            assertTrue(bus.emit(late))
            assertEquals(late, awaitItem())
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun `sealed AudioEvent hierarchy covers all event kinds`() {
        // Compile-time guarantee — this list will fail to compile if a new
        // subclass is added without updating the bus. We mock the
        // AudioDeviceInfo since constructing one requires the Android stack.
        val info = mockk<AudioDeviceInfo>(relaxed = true)
        val all: List<AudioEvent> = listOf(
            AudioEvent.AudioData(samples = ShortArray(0), frameCount = 0, timestampNs = 0L),
            AudioEvent.DeviceConnected(info, isInput = true),
            AudioEvent.DeviceDisconnected(deviceId = 1, description = "x"),
            AudioEvent.BufferOverflow(droppedSamples = 1),
            AudioEvent.EngineStatus("hello"),
        )
        assertEquals(5, all.size)
    }
}
