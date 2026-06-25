package com.travel2chicago.gemmapipeline.tts

import app.cash.turbine.test
import com.travel2chicago.gemmapipeline.audio.AudioEvent
import com.travel2chicago.gemmapipeline.audio.AudioEventBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tests for [TtsRouter]. Mirrors the patterns in
 * `AstChunkRouterTest` — Unconfined+runTest for the deterministic logic
 * tests, runBlocking+single-thread executor for any test that needs the
 * consumer to actually block on a real thread (drop-oldest, drain).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TtsRouterTest {

    /** Test stub for [KokoroTtsEngine] — returns configurable PCM or throws. */
    private class FakeEngine(
        var nextPcmSize: Int = 4_800,                  // 200 ms @ 24 kHz
        var nextLatencyMs: Long = 80L,
        var throwOnNext: Throwable? = null,
        var gate: CountDownLatch? = null,
    ) : KokoroTtsEngine {
        val synthesizeCalls = AtomicInteger(0)
        private val texts = mutableListOf<String>()

        @Synchronized fun recordText(t: String) { texts += t }
        @Synchronized fun textsCalled(): List<String> = texts.toList()

        override val isLoaded: Boolean = true
        override val loadTimeMs: Long = 0L
        override val availableVoices: Set<String> = setOf("af_heart")

        override fun synthesize(text: String, voice: String): TtsResult {
            synthesizeCalls.incrementAndGet()
            recordText(text)
            gate?.await()
            throwOnNext?.let { throw it.also { throwOnNext = null } }
            return TtsResult(
                pcm = ShortArray(nextPcmSize) { 0 },
                sampleRate = 24_000,
                latencyMs = nextLatencyMs,
            )
        }

        override fun close() {}
    }

    private fun translation(seed: Int = 1, ts: Long = seed.toLong() * 1000L) =
        AudioEvent.TranslationReady(
            text = "translation #$seed",
            sourceChunkTimestampNs = ts,
            sourceDurationMs = 3_000,
            sourcePeak = 5_000,
            latencyMs = 2_000L,
            timestampNs = ts,
        )

    private fun config(queueCapacity: Int = 4) = TtsConfig(queueCapacity = queueCapacity)

    @Test
    fun `single translation produces TtsAudioReady`() = runTest(UnconfinedTestDispatcher()) {
        val bus = AudioEventBus()
        val engine = FakeEngine(nextPcmSize = 2_400, nextLatencyMs = 42L)
        val router = TtsRouter(bus, engine, config(),
            ioDispatcher = UnconfinedTestDispatcher(testScheduler))

        router.start(backgroundScope)
        advanceUntilIdle()

        bus.events.filterIsInstance<AudioEvent.TtsAudioReady>().test {
            bus.emit(translation(seed = 1))
            advanceUntilIdle()
            val ready = awaitItem()
            assertEquals(2_400, ready.samples.size)
            assertEquals(24_000, ready.sampleRate)
            assertEquals(42L, ready.latencyMs)
            cancelAndConsumeRemainingEvents()
        }
        assertEquals(1, engine.synthesizeCalls.get())
        assertEquals(1L, router.totalSynthesized)
    }

    @Test
    fun `synthesize throwing produces TtsError and router keeps consuming`() = runTest(UnconfinedTestDispatcher()) {
        val bus = AudioEventBus()
        val engine = FakeEngine(throwOnNext = RuntimeException("oops"))
        val router = TtsRouter(bus, engine, config(),
            ioDispatcher = UnconfinedTestDispatcher(testScheduler))

        router.start(backgroundScope)
        advanceUntilIdle()

        bus.events.filterIsInstance<AudioEvent.TtsError>().test {
            bus.emit(translation(seed = 1))
            advanceUntilIdle()
            val err = awaitItem()
            assertTrue(err.message.contains("oops"))
            cancelAndConsumeRemainingEvents()
        }

        bus.events.filterIsInstance<AudioEvent.TtsAudioReady>().test {
            bus.emit(translation(seed = 2))
            advanceUntilIdle()
            val ok = awaitItem()
            assertEquals(24_000, ok.sampleRate)
            cancelAndConsumeRemainingEvents()
        }

        assertEquals(2, engine.synthesizeCalls.get())
        assertEquals(1L, router.totalSynthesized)
        assertEquals(1L, router.totalErrors)
    }

    @Test
    fun `translations are processed in submission order`() = runTest(UnconfinedTestDispatcher()) {
        val bus = AudioEventBus()
        val engine = FakeEngine()
        val router = TtsRouter(bus, engine, config(),
            ioDispatcher = UnconfinedTestDispatcher(testScheduler))

        router.start(backgroundScope)
        advanceUntilIdle()

        for (i in 1..3) {
            bus.emit(translation(seed = i))
            advanceUntilIdle()
        }
        assertEquals(listOf("translation #1", "translation #2", "translation #3"), engine.textsCalled())
        router.cancel()
    }

    @Test
    fun `cancel hard-stops both jobs and isRunning is false`() = runTest(UnconfinedTestDispatcher()) {
        val bus = AudioEventBus()
        val engine = FakeEngine()
        val router = TtsRouter(bus, engine, config(),
            ioDispatcher = UnconfinedTestDispatcher(testScheduler))
        router.start(backgroundScope)
        advanceUntilIdle()
        assertTrue(router.isRunning)
        router.cancel()
        advanceUntilIdle()
        assertEquals(false, router.isRunning)
    }

    @Test
    fun `translations submitted faster than synth get drop-oldest`() {
        val executor = Executors.newSingleThreadExecutor()
        val consumerDispatcher = executor.asCoroutineDispatcher()
        try {
            runTest(UnconfinedTestDispatcher()) {
                val bus = AudioEventBus()
                val gate = CountDownLatch(1)
                val engine = FakeEngine(gate = gate)
                val router = TtsRouter(bus, engine, config(queueCapacity = 2),
                    ioDispatcher = consumerDispatcher)
                router.start(backgroundScope)
                advanceUntilIdle()

                for (i in 1..6) bus.emit(translation(seed = i))
                advanceUntilIdle()
                Thread.sleep(50)

                assertTrue(
                    "expected drops > 0 with cap=2 and 6 submissions, got ${router.totalDropped}",
                    router.totalDropped > 0,
                )

                gate.countDown()
                Thread.sleep(50)
                router.cancel()
            }
        } finally {
            executor.shutdownNow()
            executor.awaitTermination(1, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `stopGracefully drains pending translations`() = runBlocking {
        val executor = Executors.newSingleThreadExecutor()
        val consumerDispatcher = executor.asCoroutineDispatcher()
        val routerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val bus = AudioEventBus()
            val gate = CountDownLatch(1)
            val engine = FakeEngine(gate = gate)
            val router = TtsRouter(bus, engine, config(queueCapacity = 4),
                ioDispatcher = consumerDispatcher)
            router.start(routerScope)
            Thread.sleep(100)

            for (i in 1..3) bus.emit(translation(seed = i))
            Thread.sleep(100)

            Thread {
                Thread.sleep(50)
                gate.countDown()
            }.start()

            val drained = router.stopGracefully(drainTimeoutMs = 5_000L)
            assertEquals(3, drained)
            assertEquals(3, engine.synthesizeCalls.get())
            assertEquals(3L, router.totalSynthesized)
            assertEquals(false, router.isRunning)
        } finally {
            routerScope.cancel()
            executor.shutdownNow()
            executor.awaitTermination(1, TimeUnit.SECONDS)
        }
    }
}
