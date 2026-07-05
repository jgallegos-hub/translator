package com.travel2chicago.gemmapipeline.ast

import app.cash.turbine.test
import com.travel2chicago.gemmapipeline.audio.AudioEvent
import com.travel2chicago.gemmapipeline.audio.AudioEventBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.filterIsInstance
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
 * Tests for [AstChunkRouter].
 *
 * Three dispatcher strategies, picked per-test:
 *
 *  1. **Pure logic tests** (no engine blocking): consumer on
 *     `UnconfinedTestDispatcher` so `runTest` virtual time advances
 *     deterministically.
 *
 *  2. **Drop-oldest test** (consumer blocked while producer keeps submitting):
 *     consumer on a single-thread executor so its `Thread`-level block does
 *     NOT freeze the `TestCoroutineScheduler`. UnconfinedTestDispatcher is
 *     single-threaded and would deadlock.
 *
 *  3. **No tests claim to prove "translate runs non-concurrently"** — that's
 *     a property of single-threaded `consumeEach`, not of router logic. Tests
 *     instead verify "translate called once per submitted chunk, in order".
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AstChunkRouterTest {

    /** Engine stub that returns a configurable result, throws, or blocks. */
    private class FakeEngine(
        var nextResult: AstResult? = AstResult("hello", 50L, "FAKE"),
        var throwOnNext: Throwable? = null,
        /**
         * If set, every `translate()` call **blocks the calling THREAD** on
         * this latch. Tests that use the latch MUST run the consumer on a
         * real OS thread (a single-thread executor dispatcher), not on
         * UnconfinedTestDispatcher — the latter would freeze the test
         * scheduler since it runs on the calling thread.
         */
        var gate: CountDownLatch? = null,
    ) : GemmaAstEngine {
        val translateCalls = AtomicInteger(0)
        private val seeds = mutableListOf<Int>()

        @Synchronized fun recordSeed(s: Int) { seeds += s }
        @Synchronized fun seedsCalled(): List<Int> = seeds.toList()

        override val isLoaded: Boolean = true
        override val backendUsed: String = "FAKE"
        override val loadTimeMs: Long = 0L

        override fun translate(wavBytes: ByteArray, prompt: String): AstResult {
            translateCalls.incrementAndGet()
            // The chunk encodes its seed in every sample (see [chunk] helper).
            // Skip the 44-byte WAV header to peek at the first int16 sample.
            val firstShort = ((wavBytes[44].toInt() and 0xff) or
                ((wavBytes[45].toInt() and 0xff) shl 8)).toShort()
            recordSeed(firstShort.toInt() / 100)
            gate?.await()
            throwOnNext?.let { throw it.also { throwOnNext = null } }
            return nextResult ?: AstResult("fallback", 1L, "FAKE")
        }
        override fun close() {}
    }

    private fun chunk(seed: Int = 1, ts: Long = seed.toLong() * 1000L) = AudioEvent.ChunkReady(
        samples = ShortArray(16_000) { (seed * 100).toShort() },  // 1 s @ 16 kHz
        durationMs = 1000,
        peak = seed * 100,
        timestampNs = ts,
    )

    private fun config(
        queueCapacity: Int = 4,
        rmsThreshold: Double = 0.0,
        metaTextPatterns: List<String> = emptyList(),
    ) = AstConfig(
        modelDirPath = "/sdcard/unused-in-test",
        prompt = "Translate.",
        queueCapacity = queueCapacity,
        // Filters default to OFF in tests so seed-based chunks (whose RMS
        // varies from 100 to a few thousand) always reach the engine.
        // The filter tests re-enable them explicitly.
        rmsThreshold = rmsThreshold,
        metaTextPatterns = metaTextPatterns,
    )

    @Test
    fun `single chunk produces TranslationReady with engine text`() = runTest(UnconfinedTestDispatcher()) {
        val bus = AudioEventBus()
        val engine = FakeEngine(nextResult = AstResult("Hello, world", 42L, "FAKE"))
        val router = AstChunkRouter(bus, engine, config(),
            ioDispatcher = UnconfinedTestDispatcher(testScheduler))

        router.start(backgroundScope)
        advanceUntilIdle()

        bus.events.filterIsInstance<AudioEvent.TranslationReady>().test {
            bus.emit(chunk(seed = 1))
            advanceUntilIdle()
            val tr = awaitItem()
            assertEquals("Hello, world", tr.text)
            assertEquals(42L, tr.latencyMs)
            assertEquals(1L * 1000L, tr.sourceChunkTimestampNs)
            cancelAndConsumeRemainingEvents()
        }
        assertEquals(1, engine.translateCalls.get())
        assertEquals(1L, router.totalTranslated)
        assertEquals(0L, router.totalErrors)
    }

    @Test
    fun `translate throwing produces AstError and router keeps consuming`() = runTest(UnconfinedTestDispatcher()) {
        val bus = AudioEventBus()
        val engine = FakeEngine(throwOnNext = RuntimeException("boom"))
        val router = AstChunkRouter(bus, engine, config(),
            ioDispatcher = UnconfinedTestDispatcher(testScheduler))

        router.start(backgroundScope)
        advanceUntilIdle()

        bus.events.filterIsInstance<AudioEvent.AstError>().test {
            bus.emit(chunk(seed = 1))
            advanceUntilIdle()
            val err = awaitItem()
            assertTrue(err.message.contains("boom"))
            assertEquals(1L * 1000L, err.sourceChunkTimestampNs)
            cancelAndConsumeRemainingEvents()
        }

        engine.nextResult = AstResult("recovered", 10L, "FAKE")
        bus.events.filterIsInstance<AudioEvent.TranslationReady>().test {
            bus.emit(chunk(seed = 2))
            advanceUntilIdle()
            val tr = awaitItem()
            assertEquals("recovered", tr.text)
            cancelAndConsumeRemainingEvents()
        }

        assertEquals(2, engine.translateCalls.get())
        assertEquals(1L, router.totalTranslated)
        assertEquals(1L, router.totalErrors)
    }

    @Test
    fun `chunks submitted faster than consumer empties get drop-oldest treatment`() {
        // Real-thread executor for the consumer so the gate's blocking await
        // doesn't freeze the test framework. The producer side still runs on
        // the test thread via runTest{}.
        val executor = Executors.newSingleThreadExecutor()
        val consumerDispatcher = executor.asCoroutineDispatcher()
        try {
            runTest(UnconfinedTestDispatcher()) {
                val bus = AudioEventBus()
                val gate = CountDownLatch(1)
                val engine = FakeEngine(gate = gate)
                val router = AstChunkRouter(bus, engine, config(queueCapacity = 2),
                    ioDispatcher = consumerDispatcher)

                router.start(backgroundScope)
                advanceUntilIdle()

                // Submit 6 chunks. The consumer thread is blocked on the gate
                // after picking up chunk #1. With capacity=2, the channel can
                // hold at most 2 queued + 1 in-flight; the rest must drop.
                for (i in 1..6) bus.emit(chunk(seed = i))
                advanceUntilIdle()

                // Give the executor a beat to settle (consumer is on another thread).
                Thread.sleep(50)

                assertTrue(
                    "expected drops > 0 with cap=2 and 6 submissions, got ${router.totalDropped}",
                    router.totalDropped > 0,
                )

                // Release and let the consumer drain so we don't leak the thread.
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
    fun `chunks are translated in submission order`() = runTest(UnconfinedTestDispatcher()) {
        val bus = AudioEventBus()
        val engine = FakeEngine()
        val router = AstChunkRouter(bus, engine, config(),
            ioDispatcher = UnconfinedTestDispatcher(testScheduler))

        router.start(backgroundScope)
        advanceUntilIdle()

        for (i in 1..3) {
            bus.emit(chunk(seed = i))
            advanceUntilIdle()
        }

        // FIFO: translate() should see seeds 1, 2, 3 in that exact order.
        assertEquals(listOf(1, 2, 3), engine.seedsCalled())
        assertEquals(3L, router.totalTranslated)
        router.cancel()
    }

    @Test
    fun `cancel hard-stops both producer and consumer and isRunning becomes false`() = runTest(UnconfinedTestDispatcher()) {
        val bus = AudioEventBus()
        val engine = FakeEngine()
        val router = AstChunkRouter(bus, engine, config(),
            ioDispatcher = UnconfinedTestDispatcher(testScheduler))

        router.start(backgroundScope)
        advanceUntilIdle()
        assertTrue(router.isRunning)

        router.cancel()
        advanceUntilIdle()
        assertEquals(false, router.isRunning)
    }

    /*
     * The two stopGracefully tests run on `runBlocking` (real time) rather
     * than `runTest` (virtual time). stopGracefully internally uses
     * `withTimeoutOrNull` + `Job.join()` to await a consumer that lives on
     * a real OS thread — virtual time would either fire the timeout
     * instantly or deadlock waiting for the real thread to advance.
     *
     * The router's producer/consumer launch on a separate `CoroutineScope`
     * backed by Dispatchers.Default so the runBlocking call itself never
     * holds the router's lifecycle.
     */

    @Test
    fun `stopGracefully drains pending chunks before returning`() = runBlocking {
        val executor = Executors.newSingleThreadExecutor()
        val consumerDispatcher = executor.asCoroutineDispatcher()
        val routerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val bus = AudioEventBus()
            val gate = CountDownLatch(1)
            val engine = FakeEngine(gate = gate)
            val router = AstChunkRouter(bus, engine, config(queueCapacity = 4),
                ioDispatcher = consumerDispatcher)
            router.start(routerScope)
            // bus.events is a SharedFlow with replay=0 — wait for the
            // producer's collect{} to subscribe before emitting, else
            // the events drop on the floor.
            Thread.sleep(100)

            // Submit 3 chunks. The consumer picks up #1 and blocks on the
            // gate; #2 and #3 sit in the channel (capacity=4, none dropped).
            for (i in 1..3) bus.emit(chunk(seed = i))
            Thread.sleep(100)  // let consumer pull #1 and reach gate.await()

            // Release the gate from a side thread so the consumer can make
            // progress while stopGracefully is awaiting the drain.
            Thread {
                Thread.sleep(50)
                gate.countDown()
            }.start()

            val drained = router.stopGracefully(drainTimeoutMs = 5_000L)

            assertEquals(3, drained)
            assertEquals(3, engine.translateCalls.get())
            assertEquals(listOf(1, 2, 3), engine.seedsCalled())
            assertEquals(3L, router.totalTranslated)
            assertEquals(false, router.isRunning)
        } finally {
            routerScope.cancel()
            executor.shutdownNow()
            executor.awaitTermination(1, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `stopGracefully times out and hard-cancels when consumer is stuck`() = runBlocking {
        val executor = Executors.newSingleThreadExecutor()
        val consumerDispatcher = executor.asCoroutineDispatcher()
        val routerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val gate = CountDownLatch(1)  // never released by the test body
        try {
            val bus = AudioEventBus()
            val engine = FakeEngine(gate = gate)
            val router = AstChunkRouter(bus, engine, config(queueCapacity = 4),
                ioDispatcher = consumerDispatcher)
            router.start(routerScope)
            // Wait for producer collect{} to subscribe (SharedFlow replay=0).
            Thread.sleep(100)

            bus.emit(chunk(seed = 1))
            Thread.sleep(100)  // let consumer pull chunk #1 and block

            val startNs = System.nanoTime()
            val drained = router.stopGracefully(drainTimeoutMs = 200L)
            val elapsedMs = (System.nanoTime() - startNs) / 1_000_000

            // Must return within a reasonable margin of the timeout — not
            // hang forever on the latch.
            assertTrue("stopGracefully blocked too long: ${elapsedMs}ms", elapsedMs < 1_500L)
            assertEquals(0, drained)
            assertEquals(false, router.isRunning)
        } finally {
            // Release the stuck consumer thread so the executor can shut down.
            gate.countDown()
            routerScope.cancel()
            executor.shutdownNow()
            executor.awaitTermination(1, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `averageLatencyMs is correct across multiple translations`() = runTest(UnconfinedTestDispatcher()) {
        val bus = AudioEventBus()
        // First call returns latency=100, second returns 200 → avg 150
        var n = 0
        val engine = object : GemmaAstEngine {
            override val isLoaded: Boolean = true
            override val backendUsed: String = "FAKE"
            override val loadTimeMs: Long = 0L
            override fun translate(wavBytes: ByteArray, prompt: String): AstResult {
                n += 1
                return AstResult("t$n", latencyMs = (n * 100L), backendUsed = "FAKE")
            }
            override fun close() {}
        }
        val router = AstChunkRouter(bus, engine, config(),
            ioDispatcher = UnconfinedTestDispatcher(testScheduler))

        router.start(backgroundScope)
        advanceUntilIdle()

        bus.emit(chunk(seed = 1))
        advanceUntilIdle()
        bus.emit(chunk(seed = 2))
        advanceUntilIdle()

        assertEquals(2L, router.totalTranslated)
        assertEquals(150.0, router.averageLatencyMs, 1e-9)
        router.cancel()
    }

    @Test
    fun `low-RMS chunks are dropped before reaching Gemma`() = runTest(UnconfinedTestDispatcher()) {
        val bus = AudioEventBus()
        val engine = FakeEngine(nextResult = AstResult("should not run", 1L, "FAKE"))
        // Threshold 500; silence chunk has RMS = 0 → discarded.
        val router = AstChunkRouter(
            bus, engine,
            config(rmsThreshold = 500.0),
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
        )
        router.start(backgroundScope)
        advanceUntilIdle()

        val silence = AudioEvent.ChunkReady(
            samples = ShortArray(16_000),  // all zeros
            durationMs = 1000,
            peak = 0,
            timestampNs = 42L,
        )
        bus.emit(silence)
        advanceUntilIdle()

        assertEquals(0, engine.translateCalls.get())
        assertEquals(0L, router.totalTranslated)
        assertEquals(1L, router.totalDiscardedLowEnergy)
        router.cancel()
    }

    @Test
    fun `high-RMS chunks pass the RMS gate and reach Gemma`() = runTest(UnconfinedTestDispatcher()) {
        val bus = AudioEventBus()
        val engine = FakeEngine(nextResult = AstResult("ok", 1L, "FAKE"))
        // seed=10 → constant sample 1000 → RMS 1000 > threshold 500.
        val router = AstChunkRouter(
            bus, engine,
            config(rmsThreshold = 500.0),
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
        )
        router.start(backgroundScope)
        advanceUntilIdle()

        bus.emit(chunk(seed = 10))
        advanceUntilIdle()

        assertEquals(1, engine.translateCalls.get())
        assertEquals(1L, router.totalTranslated)
        assertEquals(0L, router.totalDiscardedLowEnergy)
        router.cancel()
    }

    @Test
    fun `meta-text replies are dropped and never reach the bus`() = runTest(UnconfinedTestDispatcher()) {
        val bus = AudioEventBus()
        val engine = FakeEngine(
            nextResult = AstResult("Sorry, the audio was not provided.", 100L, "FAKE"),
        )
        val router = AstChunkRouter(
            bus, engine,
            config(metaTextPatterns = listOf("not provided", "please provide")),
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
        )
        router.start(backgroundScope)
        advanceUntilIdle()

        // TranslationReady must NOT arrive for this chunk.
        bus.events.filterIsInstance<AudioEvent.TranslationReady>().test {
            bus.emit(chunk(seed = 1))
            advanceUntilIdle()
            expectNoEvents()
            cancelAndConsumeRemainingEvents()
        }

        assertEquals(1, engine.translateCalls.get())
        assertEquals(1L, router.totalTranslated)  // Gemma ran; counter still climbs
        assertEquals(1L, router.totalDiscardedMeta)
        router.cancel()
    }

    @Test
    fun `meta-text match is case- and punctuation-insensitive via lowercase substring`() =
        runTest(UnconfinedTestDispatcher()) {
            val bus = AudioEventBus()
            val engine = FakeEngine(
                nextResult = AstResult("  I CANNOT TRANSLATE what wasn't spoken.  ", 1L, "FAKE"),
            )
            val router = AstChunkRouter(
                bus, engine,
                config(metaTextPatterns = listOf("cannot translate")),
                ioDispatcher = UnconfinedTestDispatcher(testScheduler),
            )
            router.start(backgroundScope)
            advanceUntilIdle()

            bus.emit(chunk(seed = 1))
            advanceUntilIdle()

            assertEquals(1L, router.totalDiscardedMeta)
            router.cancel()
        }

    @Test
    fun `clean replies still pass through when meta-text list is configured`() =
        runTest(UnconfinedTestDispatcher()) {
            val bus = AudioEventBus()
            val engine = FakeEngine(nextResult = AstResult("Hello, how are you?", 1L, "FAKE"))
            val router = AstChunkRouter(
                bus, engine,
                config(metaTextPatterns = listOf("not provided", "please provide")),
                ioDispatcher = UnconfinedTestDispatcher(testScheduler),
            )
            router.start(backgroundScope)
            advanceUntilIdle()

            bus.events.filterIsInstance<AudioEvent.TranslationReady>().test {
                bus.emit(chunk(seed = 1))
                advanceUntilIdle()
                val tr = awaitItem()
                assertEquals("Hello, how are you?", tr.text)
                cancelAndConsumeRemainingEvents()
            }

            assertEquals(0L, router.totalDiscardedMeta)
            router.cancel()
        }
}
