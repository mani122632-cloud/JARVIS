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
 * Groq provider (OpenAI-compatible Chat Completions over HTTPS + SSE), the second step of
 * Gemini -> Groq -> Offline.
 *
 *   POST https://api.groq.com/openai/v1/chat/completions   (key in `Authorization: Bearer`, stream = true)
 *
 *  - ONE model round per [stream] call; the tool loop stays in [OnlineBrain]. Tool calls arrive as fragments
 *    (`delta.tool_calls[index]`), are assembled here and emitted as [StreamEvent.ToolCallRequest] when the round ends,
 *    so [JarvisToolCatalog] always sees complete JSON arguments.
 *  - The history ([ChatMessage]) is converted 1:1: assistant tool_calls keep their ids, tool results are sent as
 *    `role: tool` with the same `tool_call_id`; a tool result without a matching call is never sent.
 *  - Reasoning text (`reasoning` field, or `<think>` blocks inside the content) is never spoken.
 *  - Same safety rules as Gemini: HTTPS only (HttpSseClient), background threads, timeouts, cancel() disconnects,
 *    exactly one terminal event, and neither the key, the URL, the request nor the answer text is ever logged.
 *  - No API key => [isAvailable] is false.
 */
class GroqProvider(
    context: Context,
    private val config: GroqConfigStore = GroqConfigStore.get(context),
    private val http: HttpSseClient = HttpSseClient(firstEventTimeoutMs = FIRST_EVENT_TIMEOUT_MS)
) : OnlineProvider {

    override val id: String = "groq"

    override fun isAvailable(): Boolean = config.hasApiKey()

    override fun stream(request: ChatRequest, listener: StreamListener): Cancellable {
        val key = config.getApiKey() ?: return failLater(listener, ErrorKind.UNAVAILABLE, "no_key")
        val model = config.getModel()
        val body = try {
            buildBody(request, model).toString()
        } catch (e: JSONException) {
            return failLater(listener, ErrorKind.PROTOCOL, "bad_request")
        }
        val round = Round(listener)
        round.handle = http.post(
            url = URL,
            headers = mapOf("Authorization" to "Bearer $key"),
            body = body,
            listener = round
        )
        if (round.isCancelled) round.handle?.cancel()
        return round
    }

    // ---- one streamed round ----------------------------------------------------------------------

    private class PendingCall {
        var id: String? = null
        var name: String? = null
        val args = StringBuilder()
    }

    private inner class Round(private val listener: StreamListener) : Cancellable, HttpSseClient.Listener {
        @Volatile var handle: Cancellable? = null
        private val cancelled = AtomicBoolean(false)
        private val done = AtomicBoolean(false)
        private var hadOutput = false
        private var finishReason: String? = null
        private val pending = java.util.TreeMap<Int, PendingCall>()
        private val think = ThinkFilter()

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
            val json = try {
                JSONObject(text)
            } catch (e: JSONException) {
                finishWithError(ErrorKind.PROTOCOL, "bad_json", cancelHttp = true)
                return
            }
            if (json.optJSONObject("error") != null) {
                finishWithError(ErrorKind.HTTP, "api_error", cancelHttp = true)
                return
            }
            val choice = json.optJSONArray("choices")?.optJSONObject(0) ?: return
            val delta = choice.optJSONObject("delta")
            if (delta != null) {
                val piece = delta.str("content")
                if (piece != null) {
                    val speakable = think.push(piece)
                    if (speakable.isNotEmpty()) {
                        hadOutput = true
                        listener.onEvent(StreamEvent.TextDelta(speakable))
                    }
                }
                val calls = delta.optJSONArray("tool_calls")
                if (calls != null) {
                    for (i in 0 until calls.length()) {
                        val c = calls.optJSONObject(i) ?: continue
                        val index = c.optInt("index", i)
                        val p = pending.getOrPut(index) { PendingCall() }
                        c.str("id")?.let { p.id = it }
                        val fn = c.optJSONObject("function")
                        if (fn != null) {
                            fn.str("name")?.let { p.name = it }
                            fn.str("arguments")?.let { p.args.append(it) }
                        }
                    }
                }
            }
            choice.str("finish_reason")?.let { finishReason = it }
        }

        override fun onComplete() {
            if (!done.compareAndSet(false, true)) return
            think.flush().takeIf { it.isNotEmpty() }?.let {
                hadOutput = true
                listener.onEvent(StreamEvent.TextDelta(it))
            }
            var n = 0
            for ((_, p) in pending) {
                val name = p.name ?: continue
                n++
                val args = p.args.toString().ifBlank { "{}" }
                val callId = p.id ?: "gq${System.nanoTime()}_$n"
                hadOutput = true
                listener.onEvent(StreamEvent.ToolCallRequest(ToolCallRequest(callId, name, args)))
            }
            val reason = finishReason
            if (hadOutput) {
                listener.onEvent(StreamEvent.Finished(reason))
            } else {
                val detail = if (reason != null && reason != "stop") "finish_${reason.take(40)}" else "empty"
                Log.w(TAG, "Groq round ended without output ($detail)")
                listener.onEvent(StreamEvent.Error(ErrorKind.PROTOCOL, detail))
            }
        }

        override fun onError(kind: ErrorKind, httpCode: Int?, detail: String?) {
            if (!done.compareAndSet(false, true)) return
            val d = if (kind == ErrorKind.HTTP) "http_${httpCode ?: 0}" else detail
            Log.w(TAG, "Groq round failed: $kind ${d ?: ""}")
            listener.onEvent(StreamEvent.Error(kind, d))
        }

        private fun finishWithError(kind: ErrorKind, detail: String, cancelHttp: Boolean) {
            if (!done.compareAndSet(false, true)) return
            Log.w(TAG, "Groq round failed: $kind $detail")
            if (cancelHttp) handle?.cancel()
            listener.onEvent(StreamEvent.Error(kind, detail))
        }
    }

    /** Removes `<think>...</think>` blocks from streamed content (reasoning models that inline their reasoning). */
    internal class ThinkFilter {
        private var inThink = false
        private val carry = StringBuilder()

        fun push(piece: String): String {
            carry.append(piece)
            val out = StringBuilder()
            while (true) {
                val s = carry.toString()
                if (inThink) {
                    val end = s.indexOf(CLOSE)
                    if (end < 0) { keepTail(s, CLOSE); return out.toString() }
                    carry.setLength(0); carry.append(s.substring(end + CLOSE.length)); inThink = false
                } else {
                    val start = s.indexOf(OPEN)
                    if (start < 0) {
                        val keep = partialSuffix(s, OPEN)
                        out.append(s, 0, s.length - keep)
                        carry.setLength(0); carry.append(s.substring(s.length - keep))
                        return out.toString()
                    }
                    out.append(s, 0, start)
                    carry.setLength(0); carry.append(s.substring(start + OPEN.length)); inThink = true
                }
            }
        }

        fun flush(): String {
            val rest = if (inThink) "" else carry.toString()
            carry.setLength(0)
            return rest
        }

        private fun keepTail(s: String, token: String) {
            val keep = partialSuffix(s, token)
            carry.setLength(0); carry.append(s.substring(s.length - keep))
        }

        /** Length of the longest suffix of [s] that is a proper prefix of [token]. */
        private fun partialSuffix(s: String, token: String): Int {
            for (len in minOf(s.length, token.length - 1) downTo 1) {
                if (s.endsWith(token.substring(0, len))) return len
            }
            return 0
        }

        private companion object {
            const val OPEN = "<think>"
            const val CLOSE = "</think>"
        }
    }

    // ---- request building ------------------------------------------------------------------------

    private fun buildBody(req: ChatRequest, model: String): JSONObject {
        val system = StringBuilder(req.systemPrompt)
        val messages = JSONArray()
        val known = HashSet<String>()                      // tool_call ids sent so far (orphan results are dropped)
        val rest = ArrayList<JSONObject>()

        for (m in req.messages) {
            when (m.role) {
                ChatRole.SYSTEM -> if (m.content.isNotBlank()) system.append('\n').append(m.content)
                ChatRole.USER -> {
                    if (m.content.isBlank()) continue
                    rest += JSONObject().put("role", "user").put("content", m.content)
                }
                ChatRole.ASSISTANT -> {
                    if (m.toolCalls.isEmpty() && m.content.isBlank()) continue
                    val o = JSONObject().put("role", "assistant")
                    if (m.content.isNotBlank()) o.put("content", m.content) else o.put("content", JSONObject.NULL)
                    if (m.toolCalls.isNotEmpty()) {
                        val arr = JSONArray()
                        for (c in m.toolCalls) {
                            known += c.id
                            arr.put(
                                JSONObject().put("id", c.id).put("type", "function").put(
                                    "function",
                                    JSONObject().put("name", c.name).put("arguments", normalizeArgs(c.argumentsJson))
                                )
                            )
                        }
                        o.put("tool_calls", arr)
                    }
                    rest += o
                }
                ChatRole.TOOL -> {
                    val callId = m.toolCallId ?: continue
                    if (callId !in known) continue
                    rest += JSONObject().put("role", "tool").put("tool_call_id", callId).put("content", m.content)
                }
            }
        }
        if (rest.none { it.optString("role") == "user" }) throw JSONException("no user message")

        messages.put(JSONObject().put("role", "system").put("content", system.toString()))
        for (o in rest) messages.put(o)

        val body = JSONObject()
        body.put("model", model)
        body.put("messages", messages)
        body.put("stream", true)
        body.put("max_completion_tokens", MAX_OUTPUT_TOKENS)
        if (model.startsWith("openai/gpt-oss")) body.put("reasoning_effort", "low")     // fast first token
        if (req.tools.isNotEmpty()) {
            val tools = JSONArray()
            for (t in req.tools) tools.put(toolJson(t))
            body.put("tools", tools)
            body.put("tool_choice", "auto")
        }
        return body
    }

    private fun toolJson(tool: ToolSpec): JSONObject {
        val params = try {
            JSONObject(tool.parametersJson)
        } catch (e: JSONException) {
            JSONObject().put("type", "object").put("properties", JSONObject())
        }
        if (!params.has("type")) params.put("type", "object")
        if (!params.has("properties")) params.put("properties", JSONObject())
        return JSONObject().put("type", "function").put(
            "function",
            JSONObject().put("name", tool.name).put("description", tool.description).put("parameters", params)
        )
    }

    /** Groq expects the arguments as a JSON object text. */
    private fun normalizeArgs(json: String): String =
        try { if (json.isBlank()) "{}" else JSONObject(json).toString() } catch (e: JSONException) { "{}" }

    // ---- helpers ---------------------------------------------------------------------------------

    private val errorExecutor: Executor by lazy {
        Executors.newSingleThreadExecutor { r -> Thread(r, "jarvis-groq-error").apply { isDaemon = true } }
    }

    /** A terminal error delivered from a background thread, never from the caller of [stream]. */
    private fun failLater(listener: StreamListener, kind: ErrorKind, detail: String): Cancellable {
        val handle = SimpleCancellable()
        try {
            errorExecutor.execute {
                if (!handle.isCancelled) {
                    try { listener.onEvent(StreamEvent.Error(kind, detail)) } catch (e: RuntimeException) { /* ignore */ }
                }
            }
        } catch (e: RuntimeException) { /* executor rejected */ }
        return handle
    }

    private class SimpleCancellable : Cancellable {
        private val flag = AtomicBoolean(false)
        override fun cancel() { flag.set(true) }
        override val isCancelled: Boolean get() = flag.get()
    }

    companion object {
        private const val TAG = "GroqProvider"
        private const val URL = "https://api.groq.com/openai/v1/chat/completions"
        private const val FIRST_EVENT_TIMEOUT_MS = 8_000L
        private const val MAX_OUTPUT_TOKENS = 2048

        /** A non-null, non-empty string field, or null (org.json turns JSON null into the text "null"). */
        private fun JSONObject.str(key: String): String? {
            if (!has(key) || isNull(key)) return null
            val v = opt(key) as? String ?: return null
            return v.takeIf { it.isNotEmpty() }
        }
    }
}
