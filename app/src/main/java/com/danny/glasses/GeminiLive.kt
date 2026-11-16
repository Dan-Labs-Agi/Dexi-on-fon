package com.danny.glasses

import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

/**
 * Gemini Live API over a raw WebSocket (no SDK).
 * Mic audio in: 16 kHz PCM16. Reply audio out: 24 kHz PCM16.
 * Google's servers detect when you start and stop talking.
 */
class GeminiLive(
    private val apiKey: String,
    private val model: String,
    private val voice: String,
    private val systemPrompt: String,
    private val listener: Listener,
    private val tools: JSONArray? = null,                              // function declarations
    private val toolRunner: ((String, JSONObject) -> JSONObject)? = null,
    private val googleSearch: Boolean = false,                         // weather, news, anything current
) {
    /** True once Google accepted the setup (so a later close isn't a setup problem). */
    @Volatile var wasReady = false; private set
    private val toolExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    interface Listener {
        fun onLiveReady()
        fun onLiveAudio(pcm24k: ShortArray)
        fun onLiveInterrupted()
        fun onLiveTurnComplete(userText: String, replyText: String)
        fun onLiveClosed(reason: String)
        fun onLiveNotice(text: String)
    }

    companion object {
        private const val URL =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
        private const val CHUNK = 1600   // send mic audio in 100 ms pieces
    }

    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private var socket: WebSocket? = null
    @Volatile var ready = false; private set
    @Volatile private var closed = false
    private val micChunk = ShortArray(CHUNK)
    private var micLen = 0
    private val userText = StringBuilder()
    private val replyText = StringBuilder()

    fun start() {
        // The key goes only to Google, in the request URL as their docs require.
        val request = Request.Builder().url("$URL?key=$apiKey").build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                ws.send(setupMessage().toString())
            }

            override fun onMessage(ws: WebSocket, text: String) = handle(text)

            override fun onMessage(ws: WebSocket, bytes: ByteString) = handle(bytes.utf8())

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                ws.close(1000, null)
                finish(if (reason.isNotBlank()) "$reason (code $code)" else "closed (code $code)")
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                finish(if (reason.isNotBlank()) "$reason (code $code)" else "closed (code $code)")
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                val detail = response?.let { "HTTP ${it.code} ${it.body?.string()?.take(300) ?: ""}" }
                finish(detail ?: (t.message ?: t.javaClass.simpleName))
            }
        })
    }

    fun stop() {
        if (closed) return
        socket?.close(1000, "user stopped")
        finish("stopped")
    }

    private fun finish(reason: String) {
        if (closed) return
        closed = true
        ready = false
        listener.onLiveClosed(reason)
        client.dispatcher.executorService.shutdown()
        toolExecutor.shutdown()
    }

    private fun setupMessage(): JSONObject {
        val voiceConfig = JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", voice))
        val generation = JSONObject()
            .put("responseModalities", JSONArray().put("AUDIO"))
            .put("speechConfig", JSONObject().put("voiceConfig", voiceConfig))
        val setup = JSONObject()
            .put("model", if (model.startsWith("models/")) model else "models/$model")
            .put("generationConfig", generation)
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt))))
            .put("inputAudioTranscription", JSONObject())
            .put("outputAudioTranscription", JSONObject())
        val toolList = JSONArray()
        if (tools != null && tools.length() > 0) toolList.put(JSONObject().put("functionDeclarations", tools))
        if (googleSearch) toolList.put(JSONObject().put("googleSearch", JSONObject()))
        if (toolList.length() > 0) setup.put("tools", toolList)
        return JSONObject().put("setup", setup)
    }

    /** Glasses mic audio (16 kHz), any chunk size. Sent in 100 ms pieces once the session is ready. */
    fun sendAudio(pcm: ShortArray, count: Int) {
        if (!ready) return
        var i = 0
        while (i < count) {
            val n = minOf(count - i, CHUNK - micLen)
            System.arraycopy(pcm, i, micChunk, micLen, n)
            micLen += n
            i += n
            if (micLen == CHUNK) {
                val bytes = ByteBuffer.allocate(CHUNK * 2).order(ByteOrder.LITTLE_ENDIAN)
                for (s in micChunk) bytes.putShort(s)
                val audio = JSONObject()
                    .put("data", Base64.encodeToString(bytes.array(), Base64.NO_WRAP))
                    .put("mimeType", "audio/pcm;rate=16000")
                socket?.send(JSONObject().put("realtimeInput", JSONObject().put("audio", audio)).toString())
                micLen = 0
            }
        }
    }

    private fun handle(text: String) {
        val msg = try { JSONObject(text) } catch (e: Exception) { return }
        if (msg.has("setupComplete")) {
            ready = true
            wasReady = true
            listener.onLiveReady()
        }
        msg.optJSONObject("serverContent")?.let { content ->
            content.optJSONObject("modelTurn")?.optJSONArray("parts")?.let { parts ->
                for (p in 0 until parts.length()) {
                    val data = parts.optJSONObject(p)?.optJSONObject("inlineData")?.optString("data") ?: continue
                    if (data.isEmpty()) continue
                    val raw = Base64.decode(data, Base64.DEFAULT)
                    val buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
                    listener.onLiveAudio(ShortArray(raw.size / 2) { buf.getShort() })
                }
            }
            content.optJSONObject("inputTranscription")?.optString("text")?.let { userText.append(it) }
            content.optJSONObject("outputTranscription")?.optString("text")?.let { replyText.append(it) }
            if (content.optBoolean("interrupted")) listener.onLiveInterrupted()
            if (content.optBoolean("turnComplete")) {
                listener.onLiveTurnComplete(userText.toString().trim(), replyText.toString().trim())
                userText.clear()
                replyText.clear()
            }
        }
        // Gemini asks the app to run a function (Gmail, Calendar...): run it, send the result back.
        msg.optJSONObject("toolCall")?.optJSONArray("functionCalls")?.let { calls ->
            for (i in 0 until calls.length()) {
                val call = calls.getJSONObject(i)
                val id = call.optString("id")
                val name = call.optString("name")
                val args = call.optJSONObject("args") ?: JSONObject()
                listener.onLiveNotice("Dani is using: $name")
                toolExecutor.execute {
                    val result = toolRunner?.invoke(name, args) ?: JSONObject().put("error", "tools unavailable")
                    val response = JSONObject().put("id", id).put("name", name).put("response", result)
                    socket?.send(JSONObject().put("toolResponse",
                        JSONObject().put("functionResponses", JSONArray().put(response))).toString())
                }
            }
        }
        msg.optJSONObject("goAway")?.let { listener.onLiveNotice("Google will end this session soon (${it.opt("timeLeft")} left)") }
    }
}
