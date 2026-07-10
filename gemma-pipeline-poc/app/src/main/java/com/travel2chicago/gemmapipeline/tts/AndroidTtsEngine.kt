package com.travel2chicago.gemmapipeline.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "AndroidTtsEngine"

/**
 * Thin wrapper around Android's built-in `android.speech.tts.TextToSpeech`.
 *
 * Provides a "fast" TTS path (~100–300 ms observed on the Xiaomi 15T Pro)
 * alongside our high-quality Kokoro engine. The user toggles between them
 * via [TtsConfig.useFastMode] in the UI when speed matters more than voice
 * quality.
 *
 * Unlike Kokoro, this engine does NOT return PCM — Android's TTS speaks
 * directly to the system audio output (which routes to whatever the user
 * has as the default media output, typically the JBL over A2DP). That
 * means:
 *   - No handoff to [TtsAudioPlayer] — the OS handles playback.
 *   - The [TtsRouter] still runs the VAD-mute bookends (`beginUtterance`
 *     before `speak`, `endUtterance` after) so the mic doesn't re-capture
 *     the speaker output. The `TtsPlayerSink` interface handles the flag
 *     via [TtsAudioPlayer]'s depth counter exactly like the Kokoro path.
 *   - `firstAudioLatencyMs` is measured from the `onStart` callback
 *     (`UtteranceProgressListener.onStart`) which fires when the OS
 *     begins actually playing samples.
 *
 * Language is fixed to `Locale.US` since our pipeline outputs English
 * translations. Voice selection is delegated to whatever the system default
 * for en-US is; a selector could be added later if the user asks for it.
 */
class AndroidTtsEngine private constructor(
    private val tts: TextToSpeech,
    val loadTimeMs: Long,
) : AutoCloseable {

    /**
     * The utterance we're currently waiting on. `UtteranceProgressListener`
     * callbacks come in on a system thread with only a String id, so we
     * key our start/done coroutines against this ref to route callbacks to
     * the right waiter without a Map lookup (there's only one concurrent
     * utterance — the router's Mutex ensures that).
     */
    private data class Pending(
        val id: String,
        val onStart: () -> Unit,
        val onDone: CompletableDeferred<Unit>,
    )
    private val pending = AtomicReference<Pending?>(null)

    @Volatile private var closed: Boolean = false

    val isReady: Boolean get() = !closed

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                val p = pending.get() ?: return
                if (p.id != utteranceId) return
                try { p.onStart() } catch (t: Throwable) {
                    Log.w(TAG, "onStart callback threw — ignored", t)
                }
            }
            override fun onDone(utteranceId: String?) {
                val p = pending.get() ?: return
                if (p.id != utteranceId) return
                p.onDone.complete(Unit)
            }
            @Suppress("OVERRIDE_DEPRECATION")
            override fun onError(utteranceId: String?) { onError(utteranceId, -1) }
            override fun onError(utteranceId: String?, errorCode: Int) {
                val p = pending.get() ?: return
                if (p.id != utteranceId) return
                p.onDone.completeExceptionally(
                    RuntimeException("Android TTS error code=$errorCode for id=$utteranceId"),
                )
            }
        })
    }

    /**
     * Speak [text] on Android's system TTS and suspend until playback
     * completes. [onStart] fires exactly once when the OS reports playback
     * has begun — this is the anchor for the first-audio latency metric.
     *
     * The engine is single-flight: the [TtsRouter] serialises calls via
     * its consumer coroutine, so we don't guard against concurrent
     * `speak` calls here. Two overlapping callers would race on
     * [pending] — an accepted limitation for POC scope.
     *
     * @return wall-clock ms from call entry to `onDone`. Throws on any
     *   TTS error surfaced by `UtteranceProgressListener.onError`.
     */
    suspend fun speak(text: String, onStart: () -> Unit = {}): Long {
        check(!closed) { "AndroidTtsEngine is closed" }
        val id = "u-${System.nanoTime()}"
        val done = CompletableDeferred<Unit>()
        pending.set(Pending(id, onStart, done))
        val started = System.nanoTime()
        val res = tts.speak(text, TextToSpeech.QUEUE_ADD, null, id)
        if (res == TextToSpeech.ERROR) {
            pending.set(null)
            throw RuntimeException("tts.speak returned ERROR (engine ${tts.defaultEngine})")
        }
        try {
            done.await()
        } finally {
            pending.set(null)
        }
        return (System.nanoTime() - started) / 1_000_000
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { tts.stop() }
        runCatching { tts.shutdown() }
        Log.i(TAG, "Android TTS engine shut down")
    }

    companion object {
        /**
         * Bring up the system `TextToSpeech` engine and configure it for
         * `Locale.US`. Suspends until Android's `OnInitListener` fires and
         * language availability is confirmed. Typical cost on modern
         * devices: ~50–200 ms.
         *
         * Throws if the OS-level TTS init fails OR if en-US is not
         * available on the device (e.g. the user removed Google TTS data).
         */
        suspend fun load(context: Context): AndroidTtsEngine = suspendCancellableCoroutine { cont ->
            val startedNs = System.nanoTime()
            // Assigned inside the OnInitListener lambda — need the ref
            // BEFORE the callback body can reach it.
            val ttsRef = AtomicReference<TextToSpeech?>(null)
            val engine = TextToSpeech(context.applicationContext) { status ->
                val loadMs = (System.nanoTime() - startedNs) / 1_000_000
                val e = ttsRef.get()
                if (status != TextToSpeech.SUCCESS || e == null) {
                    Log.e(TAG, "Android TTS init failed with status=$status (${loadMs}ms)")
                    cont.resumeWithException(
                        RuntimeException("Android TTS init failed (status=$status)"),
                    )
                    return@TextToSpeech
                }
                val lang = e.setLanguage(Locale.US)
                if (lang == TextToSpeech.LANG_MISSING_DATA ||
                    lang == TextToSpeech.LANG_NOT_SUPPORTED
                ) {
                    Log.e(TAG, "Android TTS en-US not available (setLanguage=$lang)")
                    runCatching { e.shutdown() }
                    cont.resumeWithException(
                        RuntimeException("Android TTS: en-US not available (result=$lang)"),
                    )
                    return@TextToSpeech
                }
                Log.i(
                    TAG,
                    "Android TTS loaded in ${loadMs}ms " +
                        "(engine=${e.defaultEngine}, voice=${runCatching { e.voice?.name }.getOrNull() ?: "default"})",
                )
                cont.resume(AndroidTtsEngine(e, loadMs))
            }
            ttsRef.set(engine)
            cont.invokeOnCancellation { runCatching { engine.shutdown() } }
        }
    }
}
