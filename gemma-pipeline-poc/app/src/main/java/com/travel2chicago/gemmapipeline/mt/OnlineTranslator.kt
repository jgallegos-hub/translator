package com.travel2chicago.gemmapipeline.mt

import android.util.Log
import com.travel2chicago.gemmapipeline.ast.CascadeAstEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "OnlineTranslator"

/**
 * Claude Haiku 5.5 through our Supabase Edge Function (`backend/supabase/functions/translate`),
 * which holds the Anthropic key. Streams English deltas as the function relays them.
 * Bench 2026-10-16: ~0.5-0.7 s per phrase, best slang quality, fixes ASR place names.
 *
 * Keeps the last [historySize] Spanish utterances as context (the function's "session" prompt).
 */
class OnlineTranslator(
    private val endpoint: String,
    private val appToken: String,
    private val connectTimeoutMs: Int = 1_500,
    /** No first byte within this time -> give up (the caller falls back to local MT). */
    private val firstByteTimeoutMs: Int = 1_800,
    private val historySize: Int = 3,
) : TextTranslator {
    override val name = "Claude Haiku"

    private val history = ArrayDeque<String>()

    override suspend fun translate(spanish: String, onToken: suspend (String) -> Unit): Pair<String, Long> =
        withContext(Dispatchers.IO) {
            val t0 = System.nanoTime()
            val payload = JSONObject()
                .put("text", spanish)
                .put("history", JSONArray(synchronized(history) { history.toList() }))
                .toString().toByteArray(Charsets.UTF_8)
            val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = connectTimeoutMs
                readTimeout = firstByteTimeoutMs
                doOutput = true
                setFixedLengthStreamingMode(payload.size)
                setRequestProperty("content-type", "application/json")
                setRequestProperty("x-app-token", appToken)
            }
            try {
                conn.outputStream.use { it.write(payload) }
                val code = conn.responseCode
                if (code != 200) error("HTTP $code")
                val out = StringBuilder()
                var firstMs = -1L
                InputStreamReader(conn.inputStream, Charsets.UTF_8).use { reader ->
                    val buf = CharArray(256)
                    while (true) {
                        val n = reader.read(buf)
                        if (n < 0) break
                        if (n == 0) continue
                        if (firstMs < 0) {
                            firstMs = (System.nanoTime() - t0) / 1_000_000
                            conn.readTimeout = 5_000 // the rest of the reply streams quickly
                        }
                        val delta = String(buf, 0, n)
                        out.append(delta)
                        onToken(delta)
                    }
                }
                synchronized(history) {
                    history.addLast(spanish)
                    while (history.size > historySize) history.removeFirst()
                }
                out.toString().trim() to (if (firstMs < 0) (System.nanoTime() - t0) / 1_000_000 else firstMs)
            } finally {
                conn.disconnect()
            }
        }

    /**
     * Boots the Edge Function so the first real phrase doesn't pay its cold
     * start (device r18: 1.9 s vs ~0.95 s warm). An empty text returns at once
     * without calling Anthropic. Errors are ignored.
     */
    fun warmUp() {
        runCatching {
            val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 5_000
                readTimeout = 5_000
                doOutput = true
                setRequestProperty("content-type", "application/json")
                setRequestProperty("x-app-token", appToken)
            }
            val t0 = System.nanoTime()
            conn.outputStream.use { it.write("""{"text":""}""".toByteArray()) }
            val code = conn.responseCode
            conn.disconnect()
            Log.i(TAG, "warm-up HTTP $code in ${(System.nanoTime() - t0) / 1_000_000} ms")
        }.onFailure { Log.w(TAG, "warm-up failed: ${it.message}") }
    }

    override fun close() = Unit

    companion object {
        /** Not in the APK or git: pushed by adb (url + app token). */
        const val CONFIG_PATH = "/sdcard/Download/translator/online.json"

        fun fromConfigFile(path: String = CONFIG_PATH): OnlineTranslator {
            val json = JSONObject(File(path).readText())
            return OnlineTranslator(json.getString("url"), json.getString("token"))
        }
    }
}

/**
 * Online first, local fallback. Falls back when the online call fails before
 * producing any text (no signal, timeout, HTTP error); after a failure the
 * online path is skipped for [cooldownMs] so a dead network doesn't add a
 * timeout to every phrase. If it fails mid-stream, the partial text stands.
 */
class FallbackTranslator(
    /** [OnlineTranslator] (text back, local Kokoro speaks) or [ServerVoiceTranslator]
     *  (server speaks; throws only before any audio, so falling back never repeats audio). */
    private val online: TextTranslator,
    /** Local MT chosen per phrase (Auto: ML Kit short / Gemma long). */
    private val local: (spanish: String) -> TextTranslator,
    private val cooldownMs: Long = 30_000,
) : TextTranslator {
    override val name = "${online.name}+fallback"

    @Volatile private var onlineDisabledUntil = 0L
    @Volatile var lastUsed: String = ""
        private set

    override suspend fun translate(spanish: String, onToken: suspend (String) -> Unit): Pair<String, Long> {
        if (System.currentTimeMillis() >= onlineDisabledUntil) {
            val partial = StringBuilder()
            try {
                val r = online.translate(spanish) { partial.append(it); onToken(it) }
                lastUsed = online.name
                return r
            } catch (t: Throwable) {
                if (partial.isNotEmpty()) {
                    Log.w(TAG, "online failed mid-stream (${t.javaClass.simpleName}: ${t.message}) — keeping partial")
                    lastUsed = online.name
                    return partial.toString().trim() to 0L
                }
                onlineDisabledUntil = System.currentTimeMillis() + cooldownMs
                Log.w(TAG, "online failed (${t.javaClass.simpleName}: ${t.message}) — local fallback, " +
                    "online paused ${cooldownMs / 1000}s")
            }
        }
        val mt = local(spanish)
        lastUsed = mt.name
        return if (mt is MlKitTranslator) {
            // Same treatment CascadeAstEngine gives ML Kit: punctuated input, closed output.
            val (raw, first) = mt.translate(CascadeAstEngine.finishSentence(spanish)) {}
            val english = CascadeAstEngine.finishSentence(raw)
            if (english.isNotEmpty()) onToken(english)
            english to first
        } else {
            mt.translate(spanish, onToken)
        }
    }

    override fun close() = online.close()
}
