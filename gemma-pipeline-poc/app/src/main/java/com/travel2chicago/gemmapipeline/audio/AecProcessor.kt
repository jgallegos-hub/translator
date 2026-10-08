package com.travel2chicago.gemmapipeline.audio

import android.util.Log
import ru.theeasiestway.libaecm.AEC
import java.util.concurrent.atomic.AtomicLong

private const val TAG = "AecProcessor"

/**
 * Software acoustic echo cancellation via WebRTC AECM.
 *
 * ## Why this exists
 *
 * The HAL-level AEC engaged by `InputPreset::VoiceCommunication` (see
 * [AudioCaptureManager]) processes the internal mic + speaker path only.
 * When the user swaps in an external mic (e.g. USB Saramonic) and/or an
 * external speaker (JBL over BT A2DP), the HAL AEC has no reference
 * signal for the external path — echo comes through unreduced. This
 * processor runs a SOFTWARE echo canceller that we feed with:
 *
 *   - **Far-end reference** (`bufferFarend`): the PCM we are about to
 *     hand to the [com.travel2chicago.gemmapipeline.tts.TtsAudioPlayer]
 *     `AudioTrack.write()`. Called from
 *     [com.travel2chicago.gemmapipeline.tts.TtsAudioPlayer.play] right
 *     before the write, so timestamps line up.
 *   - **Near-end capture** (`process`): the raw mic PCM from Oboe,
 *     already decimated to 16 kHz by
 *     [com.travel2chicago.gemmapipeline.pipeline.VadChunkingPipeline].
 *
 * The AECM library returns the near-end with the estimated echo
 * subtracted. The VAD + chunker see the clean signal.
 *
 * ## Frame layout
 *
 * WebRTC AECM processes in fixed 10 ms frames — 160 samples at 16 kHz.
 * Neither the TTS PCM (whole Kokoro sentences of arbitrary length) nor
 * the mic drain (variable Oboe chunks) arrive frame-aligned. We buffer
 * both sides in [ArrayDeque]s and drain 160-sample frames on each call.
 * Any tail that doesn't complete a frame is carried over to the next
 * call — mirrors the pattern in [com.travel2chicago.gemmapipeline.vad.FrameReassembler].
 *
 * ## Resampling
 *
 * Kokoro emits at 24 kHz; the AEC runs at 16 kHz. `bufferFarend`
 * resamples 24 → 16 with linear interpolation at ratio 3:2 (for every 3
 * input samples, emit 2). Crude by DSP standards but adequate for AEC —
 * the reference doesn't need to be perceptually clean, only phase-aligned
 * with what the speaker will actually emit.
 *
 * ## Thread safety
 *
 * `TtsAudioPlayer.play` (calls [bufferFarend]) runs on `Dispatchers.IO`;
 * `VadChunkingPipeline.handleAudioData` (calls [process]) runs on
 * `Dispatchers.Default`. The underlying WebRTC AECM instance is NOT
 * thread-safe, so both public entry points synchronise on [aecLock].
 * Contention is negligible in practice: `bufferFarend` fires at ~1 call
 * per per-100ms of TTS audio, `process` at ~1 call per drain interval
 * (25 ms), both O(N) in the number of full 10 ms frames drained.
 *
 * ## POC scope
 *
 * This is the "proof of concept" implementation (per the session plan):
 *   - Only Kokoro path is instrumented — the Android system TTS path
 *     bypasses `AudioTrack` (the OS speaks directly), so we have no PCM
 *     to feed as far-end reference. When Fast mode is on, this processor
 *     is effectively a no-op even if the flag is on.
 *   - Delay hint is hardcoded (see [DEFAULT_DELAY_MS]). Auto-estimation
 *     would need paired timestamps between farend / process calls — out
 *     of scope for the POC.
 *   - Aggressive mode is fixed at [AEC.AggressiveMode.HIGH] — matches
 *     the "clean speech over noisy monitor" trade-off we want for the
 *     travel use case.
 *
 * If measurements on device show meaningful cancellation, the follow-up
 * is either (a) tune delay per-device, or (b) upgrade to WebRTC AEC3
 * (better delay tolerance for BT A2DP).
 */
class AecProcessor : SoftwareEchoCanceller {

    private val aecLock = Any()
    @Volatile private var aec: AEC? = null

    /**
     * Far-end frames waiting to be handed to [AEC.farendBuffer]. Grown by
     * [bufferFarend] (after resampling 24→16); drained in 160-sample
     * chunks. `ArrayDeque<Short>` autoboxes; the frame counts are small
     * enough (<=1 s * 16000 = 16000 shorts) that the boxing overhead is
     * not measurable — the WebRTC native processing dominates.
     */
    private val farendTail = ArrayDeque<Short>()

    /**
     * Near-end tail carried across [process] calls when the input length
     * isn't a multiple of 160 samples.
     */
    private val nearendTail = ArrayDeque<Short>()

    // ── Diagnostic counters (best-effort; not synchronised) ────────────
    private val farendFramesFed = AtomicLong(0)
    private val nearendFramesProcessed = AtomicLong(0)

    override val isInitialized: Boolean get() = aec != null

    /**
     * Diagnostic: total 10 ms far-end frames handed to the WebRTC AECM
     * since [initialize] (or the last [reset]).
     */
    override val totalFarendFrames: Long get() = farendFramesFed.get()

    /**
     * Diagnostic: total 10 ms near-end frames run through AECM.
     */
    override val totalNearendFrames: Long get() = nearendFramesProcessed.get()

    /**
     * Bring up the WebRTC AECM instance. Idempotent — a second call is a
     * no-op unless [close] was invoked in between. Not thread-safe with
     * respect to [bufferFarend] / [process]; call this before starting
     * the pipeline.
     */
    fun initialize() {
        synchronized(aecLock) {
            if (aec != null) return
            val instance = AEC(AEC.SamplingFrequency.FS_16000Hz, AEC.AggressiveMode.HIGH)
            instance.prepare()
            aec = instance
            farendTail.clear()
            nearendTail.clear()
            farendFramesFed.set(0)
            nearendFramesProcessed.set(0)
            Log.i(TAG, "AecProcessor initialised (16 kHz, HIGH aggressive mode)")
        }
    }

    /**
     * Drop internal buffers + counters without closing the native AECM
     * instance. Use when the pipeline restarts (e.g. user toggles the
     * flag off then on again) but the same instance is fine.
     */
    override fun reset() {
        synchronized(aecLock) {
            farendTail.clear()
            nearendTail.clear()
            farendFramesFed.set(0)
            nearendFramesProcessed.set(0)
        }
    }

    /**
     * Register a chunk of audio that is about to be played through the
     * speaker as the AEC's far-end reference. Input can be at 24 kHz
     * (Kokoro's native rate) or 16 kHz — see [inputSampleRate].
     *
     * Called from [com.travel2chicago.gemmapipeline.tts.TtsAudioPlayer.play]
     * immediately before `AudioTrack.write()` so the timestamp of the
     * `bufferFarend` call is our best proxy for the moment audio enters
     * the output pipeline. The delay between this call and the mic
     * picking up the echo is the [DEFAULT_DELAY_MS] parameter passed to
     * [process].
     */
    override fun bufferFarend(pcm: ShortArray, inputSampleRate: Int) {
        val instance = aec ?: return
        if (pcm.isEmpty()) return
        val at16k = if (inputSampleRate == TARGET_SAMPLE_RATE) pcm
                    else resampleTo16k(pcm, inputSampleRate) ?: return
        synchronized(aecLock) {
            for (s in at16k) farendTail.addLast(s)
            drainFarendFrames(instance)
        }
    }

    /**
     * Run [nearEnd] through the echo canceller, returning the cleaned
     * output. `nearEnd` must be at 16 kHz (the pipeline decimates in
     * [com.travel2chicago.gemmapipeline.pipeline.VadChunkingPipeline] before
     * calling us).
     *
     * Frames that don't complete a full 160-sample AECM frame are
     * buffered internally and returned on a later call. When the AECM
     * instance isn't initialised (flag off, close() called), returns the
     * input unchanged so the caller can drop the AEC toggle without a
     * conditional at every callsite.
     *
     * @param delayMs the estimated one-way delay from [bufferFarend]
     *   time to the moment the corresponding echo reaches the mic. For
     *   BT A2DP this is typically 150–300 ms. Passed as
     *   `msInSndCardBuf` to WebRTC AECM.
     */
    override fun process(nearEnd: ShortArray): ShortArray = process(nearEnd, DEFAULT_DELAY_MS)

    fun process(nearEnd: ShortArray, delayMs: Int): ShortArray {
        val instance = aec ?: return nearEnd
        if (nearEnd.isEmpty()) return nearEnd

        synchronized(aecLock) {
            for (s in nearEnd) nearendTail.addLast(s)

            val availableFrames = nearendTail.size / FRAME_SIZE
            if (availableFrames == 0) {
                // No complete frame yet — return empty so the caller's
                // downstream stages know there's nothing to process. The
                // buffered samples reappear on the next call once more
                // arrive.
                return EMPTY_SHORT_ARRAY
            }

            val out = ShortArray(availableFrames * FRAME_SIZE)
            val scratchIn = ShortArray(FRAME_SIZE)
            var outOff = 0
            for (i in 0 until availableFrames) {
                for (j in 0 until FRAME_SIZE) scratchIn[j] = nearendTail.removeFirst()
                val cleaned = try {
                    instance.echoCancellation(scratchIn, FRAME_SIZE, delayMs)
                } catch (t: Throwable) {
                    // AECM sometimes throws on state anomalies (mismatched
                    // farend / nearend cadence). Fall back to passing the
                    // frame through unchanged so the pipeline stays alive.
                    Log.w(TAG, "echoCancellation threw — passing frame through", t)
                    scratchIn.copyOf()
                }
                if (cleaned == null || cleaned.size != FRAME_SIZE) {
                    // Defensive: some builds return null on failure.
                    System.arraycopy(scratchIn, 0, out, outOff, FRAME_SIZE)
                } else {
                    System.arraycopy(cleaned, 0, out, outOff, FRAME_SIZE)
                }
                outOff += FRAME_SIZE
                nearendFramesProcessed.incrementAndGet()
            }
            return out
        }
    }

    override fun close() {
        synchronized(aecLock) {
            val instance = aec ?: return
            aec = null
            runCatching { instance.close() }
            farendTail.clear()
            nearendTail.clear()
            Log.i(TAG, "AecProcessor closed (farendFrames=${farendFramesFed.get()}, " +
                "nearendFrames=${nearendFramesProcessed.get()})")
        }
    }

    /**
     * Drain the farend tail in 160-sample chunks and feed each to WebRTC
     * AECM. Must be called while holding [aecLock].
     */
    private fun drainFarendFrames(instance: AEC) {
        val frames = farendTail.size / FRAME_SIZE
        if (frames == 0) return
        val scratch = ShortArray(FRAME_SIZE)
        for (i in 0 until frames) {
            for (j in 0 until FRAME_SIZE) scratch[j] = farendTail.removeFirst()
            try {
                instance.farendBuffer(scratch, FRAME_SIZE)
                farendFramesFed.incrementAndGet()
            } catch (t: Throwable) {
                Log.w(TAG, "farendBuffer threw — skipping frame", t)
            }
        }
    }

    /**
     * Linear resample from any input rate down to [TARGET_SAMPLE_RATE].
     * Only integer downsampling from rates >= 16 kHz is exercised in the
     * POC (24 kHz Kokoro → 16 kHz). Rates below 16 kHz would need
     * upsampling — skipped and logged.
     */
    private fun resampleTo16k(pcm: ShortArray, inputSampleRate: Int): ShortArray? {
        if (inputSampleRate < TARGET_SAMPLE_RATE) {
            Log.w(TAG, "Upsampling from $inputSampleRate Hz not supported — skipping frame")
            return null
        }
        val ratio = inputSampleRate.toDouble() / TARGET_SAMPLE_RATE.toDouble()
        val outLen = (pcm.size / ratio).toInt()
        if (outLen <= 0) return null
        val out = ShortArray(outLen)
        for (i in 0 until outLen) {
            val srcPos = i * ratio
            val lo = srcPos.toInt().coerceIn(0, pcm.size - 1)
            val hi = (lo + 1).coerceAtMost(pcm.size - 1)
            val frac = srcPos - lo
            val v = pcm[lo] * (1.0 - frac) + pcm[hi] * frac
            out[i] = v.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return out
    }

    companion object {
        /** WebRTC AECM works at 8 kHz or 16 kHz; we run at 16 kHz to match
         *  the rest of the pipeline (Silero VAD, chunker, WAV to Gemma). */
        const val TARGET_SAMPLE_RATE: Int = 16_000

        /** 10 ms at 16 kHz. WebRTC AECM's fixed frame size. */
        const val FRAME_SIZE: Int = 160

        /**
         * Hardcoded delay estimate for the POC. BT A2DP typical range is
         * 150–300 ms; 200 ms is a middle-of-the-road value that keeps
         * AECM in its useful cancellation window. Tuning per-device or
         * auto-estimating from paired timestamps is a follow-up.
         */
        const val DEFAULT_DELAY_MS: Int = 200

        private val EMPTY_SHORT_ARRAY = ShortArray(0)
    }
}
