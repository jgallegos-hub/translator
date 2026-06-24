package com.travel2chicago.gemmapipeline.vad

import android.util.Log
import com.travel2chicago.gemmapipeline.audio.AudioEvent
import com.travel2chicago.gemmapipeline.audio.AudioFormat
import com.travel2chicago.gemmapipeline.audio.VadState

private const val TAG = "SileroVadProcessor"

/**
 * State machine wrapper around a [SileroVadModel]. Port of `VADProcessor` in
 * `src/audio_manager/vad_processor.py`.
 *
 * Anti-flicker: the state only flips after [VadConfig.minSpeechMs] consecutive
 * SPEECH frames (going SILENCE → SPEECH) or [VadConfig.minSilenceMs]
 * consecutive SILENCE frames (going SPEECH → SILENCE). A single noisy frame
 * inside an utterance does not break the state.
 */
class SileroVadProcessor(
    private val config: VadConfig,
    private val format: AudioFormat,
    private val model: SileroVadModel,
) {
    private val minSpeechFrames: Int = config.framesFor(config.minSpeechMs, format)
    private val minSilenceFrames: Int = config.framesFor(config.minSilenceMs, format)

    private var _state: VadState = VadState.SILENCE
    private var _lastTransition: AudioEvent.VadTransition? = null
    private var _lastProbability: Float = 0f
    private var consecutiveSpeech: Int = 0
    private var consecutiveSilence: Int = 0

    val state: VadState get() = _state
    val lastTransition: AudioEvent.VadTransition? get() = _lastTransition
    /** Raw probability from the most recent [processFrame] call — updated every frame. */
    val lastProbability: Float get() = _lastProbability

    /**
     * Run [frame] through the model and update the state machine. Returns a
     * non-null [AudioEvent.VadTransition] only on the call that crosses the
     * threshold; intermediate calls return null.
     */
    fun processFrame(frame: ShortArray, timestampNs: Long = System.nanoTime()): AudioEvent.VadTransition? {
        val probability = model.probability(frame)
        _lastProbability = probability
        val isSpeech = probability >= config.threshold

        if (isSpeech) {
            consecutiveSpeech += 1
            consecutiveSilence = 0
        } else {
            consecutiveSilence += 1
            consecutiveSpeech = 0
        }

        var transition: AudioEvent.VadTransition? = null

        if (_state == VadState.SILENCE && consecutiveSpeech >= minSpeechFrames) {
            _state = VadState.SPEECH
            transition = AudioEvent.VadTransition(VadState.SPEECH, timestampNs, probability)
            _lastTransition = transition
            Log.d(TAG, "SILENCE → SPEECH (p=$probability)")
        } else if (_state == VadState.SPEECH && consecutiveSilence >= minSilenceFrames) {
            _state = VadState.SILENCE
            transition = AudioEvent.VadTransition(VadState.SILENCE, timestampNs, probability)
            _lastTransition = transition
            Log.d(TAG, "SPEECH → SILENCE (p=$probability)")
        }

        return transition
    }

    /** Return raw Silero probability without touching the state machine. Useful for the UI VU. */
    fun processFrameRaw(frame: ShortArray): Float = model.probability(frame)

    /** Clear counters + last transition + Silero internal LSTM state. */
    fun reset() {
        _state = VadState.SILENCE
        _lastTransition = null
        _lastProbability = 0f
        consecutiveSpeech = 0
        consecutiveSilence = 0
        model.reset()
    }
}
