package com.travel2chicago.gemmapipeline.mt

import android.util.Log
import com.travel2chicago.gemmapipeline.tts.TtsPlayerSink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val TAG = "ServerVoice"

/**
 * Own relay (`backend/relay`): sends the Spanish transcript, receives the translated English
 * *speech* (Claude Haiku -> ElevenLabs, streamed per clause) and plays it on [sink] — Kokoro
 * is not involved. Frames: [type u8][len u32 BE][payload]; 1 clause text, 2 PCM s16le 24 kHz,
 * 3 end (JSON stats), 4 error.
 *
 * It never calls `onToken`, so the AST router emits nothing and the local TTS stays silent;
 * [onSpoken] reports the English text (UI + echo-text history). Playback runs on its own
 * coroutine so the next utterance can be translated while this one is still playing.
 * Throws only if the call fails before any audio arrived — then [FallbackTranslator] can
 * use local MT + Kokoro without ever repeating audio.
 */
class ServerVoiceTranslator(
    private val baseUrl: String,
    private val appToken: String,
    private val sink: TtsPlayerSink,
    private val onSpoken: (english: String) -> Unit,
    private val connectTimeoutMs: Int = 1_500,
    /** No first frame within this time -> fail (local fallback). */
    private val firstByteTimeoutMs: Int = 2_000,
    private val historySize: Int = 3,
) : TextTranslator {
    override val name = "Servidor (Haiku+Eric)"

    private val history = ArrayDeque<String>()

    private sealed interface Play {
        data object Begin : Play
        class Pcm(val samples: ShortArray) : Play
        data object End : Play
    }
    private val playScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val playQueue = Channel<Play>(Channel.UNLIMITED)

    init {
        playScope.launch {
            var open = 0
            try {
                for (p in playQueue) when (p) {
                    Play.Begin -> { sink.beginUtterance(); open++ }
                    Play.End -> if (open > 0) { sink.endUtterance(); open-- }
                    is Play.Pcm -> sink.play(p.samples)
                }
            } finally {
                withContext(NonCancellable) { repeat(open) { sink.endUtterance() } }
            }
        }
    }

    override suspend fun translate(spanish: String, onToken: suspend (String) -> Unit): Pair<String, Long> =
        withContext(Dispatchers.IO) {
            val t0 = System.nanoTime()
            val payload = JSONObject()
                .put("text", spanish)
                .put("history", JSONArray(synchronized(history) { history.toList() }))
                .toString().toByteArray(Charsets.UTF_8)
            val conn = (URL("$baseUrl/speak").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = connectTimeoutMs
                readTimeout = firstByteTimeoutMs
                doOutput = true
                setFixedLengthStreamingMode(payload.size)
                setRequestProperty("content-type", "application/json")
                setRequestProperty("x-app-token", appToken)
            }
            val english = StringBuilder()
            var audioStarted = false
            var firstAudioMs = -1L
            try {
                conn.outputStream.use { it.write(payload) }
                if (conn.responseCode != 200) error("HTTP ${conn.responseCode}")
                DataInputStream(conn.inputStream.buffered()).use { input ->
                    var carry: Byte? = null
                    loop@ while (true) {
                        val type = try { input.readUnsignedByte() } catch (_: EOFException) { break }
                        val len = input.readInt()
                        val data = ByteArray(len).also { input.readFully(it) }
                        when (type) {
                            1 -> english.append(if (english.isEmpty()) "" else " ").append(String(data, Charsets.UTF_8))
                            2 -> {
                                if (!audioStarted) {
                                    audioStarted = true
                                    firstAudioMs = (System.nanoTime() - t0) / 1_000_000
                                    conn.readTimeout = 5_000
                                    playQueue.send(Play.Begin)
                                }
                                playQueue.send(Play.Pcm(toShorts(data, carry).also { carry = it.second }.first))
                            }
                            3 -> { Log.i(TAG, "server stats ${String(data, Charsets.UTF_8)}"); break@loop }
                            4 -> Log.w(TAG, "server error: ${String(data, Charsets.UTF_8)}")
                        }
                    }
                }
            } catch (t: Throwable) {
                if (!audioStarted) throw t
                Log.w(TAG, "stream broke after audio started (${t.javaClass.simpleName}: ${t.message}) — keeping partial")
            } finally {
                if (audioStarted) playQueue.send(Play.End)
                conn.disconnect()
            }
            val text = english.toString().trim()
            synchronized(history) {
                history.addLast(spanish)
                while (history.size > historySize) history.removeFirst()
            }
            if (text.isNotEmpty()) onSpoken(text)
            Log.i(TAG, "SERVERVOICE firstAudioMs=$firstAudioMs totalMs=${(System.nanoTime() - t0) / 1_000_000} en='$text'")
            text to firstAudioMs
        }

    /** Asks the relay to warm Haiku's prompt cache and its API connections. Errors ignored. */
    fun warmUp() {
        runCatching {
            val conn = (URL("$baseUrl/health?warm=1").openConnection() as HttpURLConnection).apply {
                connectTimeout = 5_000; readTimeout = 10_000
                setRequestProperty("x-app-token", appToken)
            }
            val code = conn.responseCode
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            Log.i(TAG, "warm-up HTTP $code ($body)")
        }.onFailure { Log.w(TAG, "warm-up failed: ${it.message}") }
    }

    override fun close() {
        playQueue.close()
        playScope.cancel()
    }

    companion object {
        /** Not in the APK or git: pushed by adb ({"url": ..., "token": ...}). */
        const val CONFIG_PATH = "/sdcard/Download/translator/server.json"

        fun fromConfigFile(
            sink: TtsPlayerSink,
            onSpoken: (String) -> Unit,
            path: String = CONFIG_PATH,
        ): ServerVoiceTranslator {
            val json = JSONObject(File(path).readText())
            return ServerVoiceTranslator(json.getString("url").trimEnd('/'), json.getString("token"), sink, onSpoken)
        }

        /** PCM s16le bytes -> samples; an odd trailing byte is carried to the next frame. */
        internal fun toShorts(data: ByteArray, carry: Byte?): Pair<ShortArray, Byte?> {
            val bytes = if (carry != null) byteArrayOf(carry) + data else data
            val n = bytes.size / 2
            val out = ShortArray(n)
            ByteBuffer.wrap(bytes, 0, n * 2).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(out)
            return out to (if (bytes.size % 2 == 1) bytes.last() else null)
        }
    }
}
