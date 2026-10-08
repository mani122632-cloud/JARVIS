package com.jarvis.assistant.online

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Vision provider: Groq (OpenAI-compatible `chat/completions`, SSE stream) with an image in the last user turn.
 * Key comes from [GroqConfigStore] (never hard-coded, never logged). No tools. Every failure (no key, network,
 * timeout, HTTP, bad/oversized image, malformed answer) becomes ONE terminal [StreamEvent.Error]; nothing throws.
 * The regular Gemini/Groq text providers are untouched.
 */
class GroqVisionProvider(
    context: Context,
    private val config: GroqConfigStore = GroqConfigStore.get(context),
    private val http: HttpSseClient = HttpSseClient(firstEventTimeoutMs = FIRST_EVENT_TIMEOUT_MS)
) : OnlineProvider {

    override val id: String = "groq-vision"

    override fun isAvailable(): Boolean = config.hasApiKey()

    override fun stream(request: ChatRequest, listener: StreamListener): Cancellable {
        val key = config.getApiKey() ?: return failLater(listener, ErrorKind.UNAVAILABLE, "no_key")
        val body = try {
            buildBody(request).toString()
        } catch (e: JSONException) {
            return failLater(listener, ErrorKind.PROTOCOL, "bad_request")
        } catch (e: IllegalArgumentException) {
            return failLater(listener, ErrorKind.PROTOCOL, "bad_image")
        }
        val round = Round(listener)
        round.handle = try {
            http.post(
                url = URL,
                headers = mapOf("Authorization" to "Bearer $key"),
                body = body,
                listener = round
            )
        } catch (e: RuntimeException) {
            return failLater(listener, ErrorKind.NETWORK, "post_failed")
        }
        if (round.isCancelled) round.handle?.cancel()
        return round
    }

    private inner class Round(private val listener: StreamListener) : Cancellable, HttpSseClient.Listener {
        @Volatile var handle: Cancellable? = null
        private val cancelled = AtomicBoolean(false)
        private val done = AtomicBoolean(false)
        private var hadOutput = false
        private var finishReason: String? = null

        override val isCancelled: Boolean get() = cancelled.get()

        override fun cancel() {
            if (!cancelled.compareAndSet(false, true)) return
            done.set(true)
            handle?.cancel()
        }

        override fun onEvent(event: String?, data: String) {
            if (done.get()) return
            val text = data.trim()
            if (text.isEmpty() || text == "[DONE]") return
            val json = try { JSONObject(text) } catch (e: JSONException) { fail(ErrorKind.PROTOCOL, "bad_json"); return }
            if (json.optJSONObject("error") != null) { fail(ErrorKind.HTTP, "api_error"); return }
            val choice = json.optJSONArray("choices")?.optJSONObject(0) ?: return
            val piece = choice.optJSONObject("delta")?.let { if (it.isNull("content")) null else it.optString("content") }
            if (!piece.isNullOrEmpty()) {
                hadOutput = true
                listener.onEvent(StreamEvent.TextDelta(piece))
            }
            if (!choice.isNull("finish_reason")) finishReason = choice.optString("finish_reason").takeIf { it.isNotEmpty() }
        }

        override fun onComplete() {
            if (!done.compareAndSet(false, true)) return
            if (hadOutput) listener.onEvent(StreamEvent.Finished(finishReason))
            else {
                Log.w(TAG, "Groq vision round ended without output")
                listener.onEvent(StreamEvent.Error(ErrorKind.PROTOCOL, "empty"))
            }
        }

        override fun onError(kind: ErrorKind, httpCode: Int?, detail: String?) {
            if (!done.compareAndSet(false, true)) return
            val d = if (kind == ErrorKind.HTTP) "http_${httpCode ?: 0}" else detail
            Log.w(TAG, "Groq vision round failed: $kind ${d ?: ""}")
            listener.onEvent(StreamEvent.Error(kind, d))
        }

        private fun fail(kind: ErrorKind, detail: String) {
            if (!done.compareAndSet(false, true)) return
            Log.w(TAG, "Groq vision round failed: $kind $detail")
            handle?.cancel()
            listener.onEvent(StreamEvent.Error(kind, detail))
        }
    }

    /** OpenAI-style messages; the image (via [VisionAttachment]) goes only into the LAST user turn that carries one. */
    private fun buildBody(req: ChatRequest): JSONObject {
        val system = StringBuilder(req.systemPrompt)
        val msgs = JSONArray()
        for (m in req.messages) {
            when (m.role) {
                ChatRole.SYSTEM -> if (m.content.isNotBlank()) system.append('\n').append(m.content)
                ChatRole.USER -> {
                    val (text, img) = VisionAttachment.extract(m.content)
                    if (text.isBlank() && img == null) continue
                    if (img == null) { msgs.put(JSONObject().put("role", "user").put("content", text)); continue }
                    if (img.length > MAX_IMAGE_B64) throw IllegalArgumentException("image too large")
                    val parts = JSONArray()
                        .put(JSONObject().put("type", "text").put("text", text.ifBlank { "این تصویر را توضیح بده." }))
                        .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$img")))
                    msgs.put(JSONObject().put("role", "user").put("content", parts))
                }
                ChatRole.ASSISTANT -> if (m.content.isNotBlank()) msgs.put(JSONObject().put("role", "assistant").put("content", m.content))
                ChatRole.TOOL -> Unit
            }
        }
        if (msgs.length() == 0) throw JSONException("no messages")
        val all = JSONArray().put(JSONObject().put("role", "system").put("content", system.toString()))
        for (i in 0 until msgs.length()) all.put(msgs.get(i))
        return JSONObject()
            .put("model", MODEL)
            .put("messages", all)
            .put("stream", true)
            .put("temperature", 0.3)
            .put("max_completion_tokens", MAX_OUTPUT_TOKENS)
    }

    private val errorExecutor: Executor by lazy {
        Executors.newSingleThreadExecutor { r -> Thread(r, "jarvis-groq-vision-error").apply { isDaemon = true } }
    }

    private fun failLater(listener: StreamListener, kind: ErrorKind, detail: String): Cancellable {
        val flag = AtomicBoolean(false)
        try {
            errorExecutor.execute {
                if (!flag.get()) try { listener.onEvent(StreamEvent.Error(kind, detail)) } catch (e: RuntimeException) { /* ignore */ }
            }
        } catch (e: RuntimeException) { /* executor rejected */ }
        return object : Cancellable {
            override fun cancel() { flag.set(true) }
            override val isCancelled: Boolean get() = flag.get()
        }
    }

    companion object {
        private const val TAG = "GroqVision"
        private const val URL = "https://api.groq.com/openai/v1/chat/completions"
        /** Groq vision-capable model (the saved Groq text model may not accept images). */
        const val MODEL = "meta-llama/llama-4-scout-17b-16e-instruct"
        private const val FIRST_EVENT_TIMEOUT_MS = 20_000L
        private const val MAX_OUTPUT_TOKENS = 400
        /** Groq limit for a base64 image request is 4 MB. */
        private const val MAX_IMAGE_B64 = 3_800_000
    }
}
