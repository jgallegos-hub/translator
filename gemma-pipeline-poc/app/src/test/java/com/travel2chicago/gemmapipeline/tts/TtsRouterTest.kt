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

        /** Emits one PCM callback per '.'-delimited sentence found in [text]
         *  so tests can drive multi-sentence streaming with a single call. */
        override suspend fun synthesizeStreaming(
            text: String,
            voice: String,
            onSentence: suspend (pcm: ShortArray, sampleRate: Int, sentenceIndex: Int) -> Unit,
        ): TtsResult {
            synthesizeCalls.incrementAndGet()
            recordText(text)
            gate?.await()
            throwOnNext?.let { throw it.also { throwOnNext = null } }
            val sentences = text.split(".").filter { it.isNotBlank() }
            val count = sentences.size.coerceAtLeast(1)
            val perSize = (nextPcmSize / count).coerceAtLeast(1)
            for (idx in 0 until count) {
                onSentence(ShortArray(perSize) { 0 }, 24_000, idx)
            }
            return TtsResult(
                pcm = ShortArray(0),
                sampleRate = 24_000,
                latencyMs = nextLatencyMs,
            )
        }

        override fun close() {}
    }

    /**
     * Records every begin/end/play call ordered by invocation time. The
     * `playCalls` list stores per-play PCM sizes so tests can verify the
     * router forwarded them in the expected order and count. `flag` is
     * exposed so tests can spot-check "was ttsPlaying true right after
     * begin?" without wrapping in a real `AtomicBoolean` sink.
     */
    private class RecordingSink : TtsPlayerSink {
        val beginCount = AtomicInteger(0)
        val endCount = AtomicInteger(0)
        private val playSizes = mutableListOf<Int>()
        private val order = mutableListOf<String>()

        @Synchronized fun recordPlay(size: Int) {
            playSizes += size
            order += "play($size)"
        }
        @Synchronized fun playCalledWith(): List<Int> = playSizes.toList()
        @Synchronized fun events(): List<String> = order.toList()

        override fun beginUtterance() {
            beginCount.incrementAndGet()
            @Suppress("UNUSED_PARAMETER")
            synchronized(this) { order += "begin" }
        }

        override fun endUtterance() {
            endCount.incrementAndGet()
            synchronized(this) { order += "end" }
        }

        override suspend fun play(pcm: ShortArray) {
            recordPlay(pcm.size)
        }
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

    private fun config(
        queueCapacity: Int = 4,
        streamingEnabled: Boolean = false,
    ) = TtsConfig(
        queueCapacity = queueCapacity,
        streamingEnabled = streamingEnabled,
    )

    private fun streamingTranslation(
        text: String,
        sourceChunkTs: Long,
        sentenceIndex: Int?,
        isFinal: Boolean,
        ts: Long = sourceChunkTs + 1_000L,
    ) = AudioEvent.TranslationReady(
        text = text,
        sourceChunkTimestampNs = sourceChunkTs,
        sourceDurationMs = 3_000,
        sourcePeak = 5_000,
        latencyMs = 2_000L,
        timestampNs = ts,
        sentenceIndex = sentenceIndex,
        isFinal = isFinal,
    )

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

    // ── Fase 6 Stage B — streaming path tests ────────────────────────────

    @Test
    fun `streaming multi-sentence input yields one play per sentence with bookends`() =
        runTest(UnconfinedTestDispatcher()) {
            val bus = AudioEventBus()
            val engine = FakeEngine(nextPcmSize = 3_600)  // 3 sentences → 1200 each
            val sink = RecordingSink()
            val router = TtsRouter(
                bus, engine, config(streamingEnabled = true),
                ioDispatcher = UnconfinedTestDispatcher(testScheduler),
                player = sink,
            )
            router.start(backgroundScope)
            advanceUntilIdle()

            // One TranslationReady with a 3-sentence text (Stage A OFF style).
            bus.emit(
                streamingTranslation(
                    text = "Hola. Como estas. Adios.",
                    sourceChunkTs = 42L,
                    sentenceIndex = null,   // legacy shape
                    isFinal = true,
                ),
            )
            advanceUntilIdle()

            // 1 begin, 3 plays, 1 end — in that order.
            assertEquals(1, sink.beginCount.get())
            assertEquals(3, sink.playCalledWith().size)
            assertEquals(1, sink.endCount.get())
            assertEquals(
                listOf("begin", "play(1200)", "play(1200)", "play(1200)", "end"),
                sink.events(),
            )
            assertEquals(1L, router.totalSynthesized)
            router.cancel()
        }

    @Test
    fun `streaming Stage-A style two events for one utterance bookend once`() =
        runTest(UnconfinedTestDispatcher()) {
            val bus = AudioEventBus()
            val engine = FakeEngine(nextPcmSize = 800)
            val sink = RecordingSink()
            val router = TtsRouter(
                bus, engine, config(streamingEnabled = true),
                ioDispatcher = UnconfinedTestDispatcher(testScheduler),
                player = sink,
            )
            router.start(backgroundScope)
            advanceUntilIdle()

            // Two per-sentence TranslationReady events sharing sourceChunkTs.
            // The first is non-final; the second is final.
            bus.emit(streamingTranslation("Hola.", sourceChunkTs = 42L, sentenceIndex = 0, isFinal = false))
            advanceUntilIdle()
            bus.emit(streamingTranslation("Adios.", sourceChunkTs = 42L, sentenceIndex = 1, isFinal = true))
            advanceUntilIdle()

            // Exactly ONE begin at the utterance start, ONE end after the
            // final event — even though two events arrived.
            assertEquals(1, sink.beginCount.get())
            assertEquals(1, sink.endCount.get())
            assertEquals(2, sink.playCalledWith().size)  // 1 play per event
            assertEquals(
                listOf("begin", "play(800)", "play(800)", "end"),
                sink.events(),
            )
            router.cancel()
        }

    @Test
    fun `streaming new utterance without prior isFinal triggers defensive endUtterance`() =
        runTest(UnconfinedTestDispatcher()) {
            val bus = AudioEventBus()
            val engine = FakeEngine(nextPcmSize = 800)
            val sink = RecordingSink()
            val router = TtsRouter(
                bus, engine, config(streamingEnabled = true),
                ioDispatcher = UnconfinedTestDispatcher(testScheduler),
                player = sink,
            )
            router.start(backgroundScope)
            advanceUntilIdle()

            // Non-final for utterance A; then a completely different
            // sourceChunkTs arrives without utterance A ever getting its
            // isFinal (e.g. DROP_OLDEST removed the final event).
            bus.emit(streamingTranslation("A.", sourceChunkTs = 100L, sentenceIndex = 0, isFinal = false))
            advanceUntilIdle()
            bus.emit(streamingTranslation("B.", sourceChunkTs = 200L, sentenceIndex = 0, isFinal = true))
            advanceUntilIdle()

            // begin A → play A → (defensive end A + begin B) → play B → end B
            assertEquals(2, sink.beginCount.get())
            assertEquals(2, sink.endCount.get())  // one defensive, one real
            assertEquals(2, sink.playCalledWith().size)
            assertEquals(
                listOf("begin", "play(800)", "end", "begin", "play(800)", "end"),
                sink.events(),
            )
            router.cancel()
        }

    @Test
    fun `streaming synthesize exception closes the utterance and emits TtsError`() =
        runTest(UnconfinedTestDispatcher()) {
            val bus = AudioEventBus()
            val engine = FakeEngine(throwOnNext = RuntimeException("stream boom"))
            val sink = RecordingSink()
            val router = TtsRouter(
                bus, engine, config(streamingEnabled = true),
                ioDispatcher = UnconfinedTestDispatcher(testScheduler),
                player = sink,
            )
            router.start(backgroundScope)
            advanceUntilIdle()

            bus.events.filterIsInstance<AudioEvent.TtsError>().test {
                bus.emit(streamingTranslation("Boom.", sourceChunkTs = 42L, sentenceIndex = 0, isFinal = true))
                advanceUntilIdle()
                val err = awaitItem()
                assertTrue(err.message.contains("stream boom"))
                cancelAndConsumeRemainingEvents()
            }

            // begin fired, then the streaming call threw — router's finally
            // still runs endUtterance because tr.isFinal = true.
            assertEquals(1, sink.beginCount.get())
            assertEquals(1, sink.endCount.get())
            assertEquals(0, sink.playCalledWith().size)  // never got a play
            assertEquals(1L, router.totalErrors)
            router.cancel()
        }

    @Test
    fun `non-streaming path still emits TtsAudioReady and never touches the sink`() =
        runTest(UnconfinedTestDispatcher()) {
            val bus = AudioEventBus()
            val engine = FakeEngine(nextPcmSize = 4_800)
            val sink = RecordingSink()
            val router = TtsRouter(
                bus, engine, config(streamingEnabled = false),
                ioDispatcher = UnconfinedTestDispatcher(testScheduler),
                player = sink,
            )
            router.start(backgroundScope)
            advanceUntilIdle()

            bus.events.filterIsInstance<AudioEvent.TtsAudioReady>().test {
                bus.emit(translation(seed = 1))
                advanceUntilIdle()
                val ready = awaitItem()
                assertEquals(4_800, ready.samples.size)
                cancelAndConsumeRemainingEvents()
            }
            // Router took the legacy branch: sink was NEVER touched.
            assertEquals(0, sink.beginCount.get())
            assertEquals(0, sink.endCount.get())
            assertEquals(0, sink.playCalledWith().size)
            router.cancel()
        }
}
