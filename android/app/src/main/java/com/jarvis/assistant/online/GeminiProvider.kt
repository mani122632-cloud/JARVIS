package com.jarvis.assistant.online

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.Collections
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Online Brain Stage 2: the real Gemini provider (Google Gemini API, `generateContent` family over HTTPS).
 *
 *   OnlineBrain -> [stream] -> POST models/{model}:streamGenerateContent?alt=sse   (key in `x-goog-api-key`)
 *                           -> SSE chunks -> StreamEvent.TextDelta / ToolCallRequest -> Finished | Error
 *
 *  - ONE model round per [stream] call. The multi-step tool loop (tool result -> next round) stays in [OnlineBrain];
 *    every round re-sends the (short, trimmed) history, so tool results really reach Gemini.
 *  - Gemini 3 requires the `thoughtSignature` of every function call of the current turn to be sent back. The
 *    [ChatMessage] model has no field for it, so it is remembered here per generated call id ([calls]); a call whose
 *    signature is lost gets Google's documented dummy signature instead of a failing request.
 *  - Function declarations are reduced to the OpenAPI subset Gemini accepts (type/description/enum/properties/
 *    required/items). The real validation of the arguments stays in [JarvisToolCatalog.prepare].
 *  - Network: [HttpSseClient] (HTTPS only, background threads, connect/first-event/idle/total timeouts, `cancel()`
 *    disconnects at once). HTTP errors, timeouts, no network and malformed answers become ONE terminal
 *    [StreamEvent.Error]; neither the key, the URL, the request nor the answer text is ever logged.
 *  - No API key saved => [isAvailable] is false and the offline brain is used, exactly as before.
 */
class GeminiProvider(
    context: Context,
    private val config: GeminiConfigStore = GeminiConfigStore.get(context),
    private val http: HttpSseClient = HttpSseClient(firstEventTimeoutMs = FIRST_EVENT_TIMEOUT_MS)
) : OnlineProvider {

    override val id: String = "gemini"

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
            url = "$BASE_URL/$model:streamGenerateContent?alt=sse",
            headers = mapOf("x-goog-api-key" to key),
            body = body,
            listener = round
        )
        if (round.isCancelled) round.handle?.cancel()
        return round
    }

    // ---- one streamed round ----------------------------------------------------------------------

    private inner class Round(private val listener: StreamListener) : Cancellable, HttpSseClient.Listener {
        @Volatile var handle: Cancellable? = null
        private val cancelled = AtomicBoolean(false)
        private val done = AtomicBoolean(false)
        private var hadOutput = false
        private var finishReason: String? = null

        override val isCancelled: Boolean get() = cancelled.get()

        override fun cancel() {
            if (!cancelled.compareAndSet(false, true)) return
            done.set(true)                       // nothing more is delivered after a cancel
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
            val apiError = json.optJSONObject("error")
            if (apiError != null) {
                finishWithError(ErrorKind.HTTP, "api_error_${apiError.optInt("code", 0)}", cancelHttp = true)
                return
            }
            val candidates = json.optJSONArray("candidates")
            val candidate = candidates?.optJSONObject(0)
            if (candidate == null) {
                // A chunk without candidates: either usage metadata only, or the prompt was blocked.
                val block = json.optJSONObject("promptFeedback")?.str("blockReason")
                if (block != null) finishWithError(ErrorKind.PROTOCOL, "blocked", cancelHttp = true)
                return
            }
            val parts = candidate.optJSONObject("content")?.optJSONArray("parts")
            if (parts != null) {
                for (i in 0 until parts.length()) {
                    if (done.get()) return
                    val part = parts.optJSONObject(i) ?: continue
                    if (part.optBoolean("thought", false)) continue            // thought summaries are never spoken
                    val call = part.optJSONObject("functionCall")
                    if (call != null) {
                        if (!handleCall(call, part)) return
                        continue
                    }
                    val piece = part.str("text")
                    if (!piece.isNullOrEmpty()) {
                        hadOutput = true
                        listener.onEvent(StreamEvent.TextDelta(piece))
                    }
                }
            }
            candidate.str("finishReason")?.let { finishReason = it }
        }

        /** Returns false when the round was terminated because of this call. */
        private fun handleCall(call: JSONObject, part: JSONObject): Boolean {
            val name = call.str("name")
            if (name == null) {
                finishWithError(ErrorKind.PROTOCOL, "call_without_name", cancelHttp = true)
                return false
            }
            val args = call.optJSONObject("args")?.toString() ?: "{}"
            val signature = part.str("thoughtSignature") ?: part.str("thought_signature")
            val callId = "gc" + counter.incrementAndGet()
            calls[callId] = CallMeta(signature = signature, originalId = call.str("id"))
            hadOutput = true
            listener.onEvent(StreamEvent.ToolCallRequest(ToolCallRequest(callId, name, args)))
            return true
        }

        override fun onComplete() {
            if (!done.compareAndSet(false, true)) return
            val reason = finishReason
            if (hadOutput) {
                listener.onEvent(StreamEvent.Finished(reason))
            } else {
                val detail = if (reason != null && reason != "STOP") "finish_${reason.take(40)}" else "empty"
                Log.w(TAG, "Gemini round ended without output ($detail)")
                listener.onEvent(StreamEvent.Error(ErrorKind.PROTOCOL, detail))
            }
        }

        override fun onError(kind: ErrorKind, httpCode: Int?, detail: String?) {
            if (!done.compareAndSet(false, true)) return
            val d = if (kind == ErrorKind.HTTP) "http_${httpCode ?: 0}" else detail
            Log.w(TAG, "Gemini round failed: $kind ${d ?: ""}")
            listener.onEvent(StreamEvent.Error(kind, d))
        }

        private fun finishWithError(kind: ErrorKind, detail: String, cancelHttp: Boolean) {
            if (!done.compareAndSet(false, true)) return
            Log.w(TAG, "Gemini round failed: $kind $detail")
            if (cancelHttp) handle?.cancel()
            listener.onEvent(StreamEvent.Error(kind, detail))
        }
    }

    // ---- request building ------------------------------------------------------------------------

    private class Content(val role: String, val parts: JSONArray, val isResponse: Boolean)

    private fun buildBody(req: ChatRequest, model: String): JSONObject {
        val system = StringBuilder(req.systemPrompt)
        val contents = ArrayList<Content>()
        val names = HashMap<String, String>()            // tool_call_id -> tool name (for the functionResponse)
        val gemini3 = model.startsWith("gemini-3")

        for (m in req.messages) {
            when (m.role) {
                ChatRole.SYSTEM -> if (m.content.isNotBlank()) system.append('\n').append(m.content)
                ChatRole.USER -> {
                    if (m.content.isBlank()) continue
                    val part = JSONObject().put("text", m.content)
                    val last = contents.lastOrNull()
                    // Two user contents in a row (e.g. after an answer that was empty) are merged into one.
                    if (last != null && last.role == "user") last.parts.put(part)
                    else contents += Content("user", JSONArray().put(part), false)
                }
                ChatRole.ASSISTANT -> {
                    val parts = JSONArray()
                    if (m.content.isNotBlank()) parts.put(JSONObject().put("text", m.content))
                    var first = true
                    for (c in m.toolCalls) {
                        names[c.id] = c.name
                        val meta = calls[c.id]
                        val fc = JSONObject().put("name", c.name).put("args", parseObject(c.argumentsJson))
                        meta?.originalId?.let { fc.put("id", it) }
                        val part = JSONObject().put("functionCall", fc)
                        // Only the FIRST call of a step carries a signature; Gemini 3 rejects a step without one.
                        val signature = meta?.signature ?: if (first && gemini3) DUMMY_SIGNATURE else null
                        if (signature != null) part.put("thoughtSignature", signature)
                        first = false
                        parts.put(part)
                    }
                    if (parts.length() > 0) contents += Content("model", parts, false)
                }
                ChatRole.TOOL -> {
                    val callId = m.toolCallId ?: continue
                    val name = names[callId] ?: continue          // orphan result: never send it
                    val fr = JSONObject().put("name", name).put("response", parseResponse(m.content))
                    calls[callId]?.originalId?.let { fr.put("id", it) }
                    val part = JSONObject().put("functionResponse", fr)
                    val last = contents.lastOrNull()
                    // Parallel calls: all responses go into ONE user content, in call order.
                    if (last != null && last.isResponse) last.parts.put(part)
                    else contents += Content("user", JSONArray().put(part), true)
                }
            }
        }
        if (contents.isEmpty()) throw JSONException("no contents")

        val contentsJson = JSONArray()
        for (c in contents) contentsJson.put(JSONObject().put("role", c.role).put("parts", c.parts))

        val body = JSONObject()
        body.put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system.toString()))))
        body.put("contents", contentsJson)
        if (req.tools.isNotEmpty()) {
            val declarations = JSONArray()
            for (t in req.tools) declarations.put(declaration(t))
            body.put("tools", JSONArray().put(JSONObject().put("functionDeclarations", declarations)))
        }
        val generation = JSONObject().put("maxOutputTokens", MAX_OUTPUT_TOKENS)       // includes thinking tokens
        if (gemini3) generation.put("thinkingConfig", JSONObject().put("thinkingLevel", "low"))   // fast first chunk
        body.put("generationConfig", generation)
        return body
    }

    private fun declaration(tool: ToolSpec): JSONObject {
        val d = JSONObject().put("name", tool.name).put("description", tool.description)
        val schema = try { sanitize(JSONObject(tool.parametersJson)) } catch (e: JSONException) { null }
        val hasProperties = (schema?.optJSONObject("properties")?.length() ?: 0) > 0
        if (schema != null && hasProperties) d.put("parameters", schema)      // a no-argument tool has no parameters
        return d
    }

    private fun parseObject(json: String): JSONObject =
        try { if (json.isBlank()) JSONObject() else JSONObject(json) } catch (e: JSONException) { JSONObject() }

    private fun parseResponse(content: String): JSONObject =
        try { JSONObject(content) } catch (e: JSONException) { JSONObject().put("result", content) }

    // ---- state / helpers -------------------------------------------------------------------------

    private class CallMeta(val signature: String?, val originalId: String?)

    private val counter = AtomicLong()

    /** Bounded: only the current turn's calls are validated by Gemini, and the history is about 12 messages. */
    private val calls: MutableMap<String, CallMeta> = Collections.synchronizedMap(
        object : LinkedHashMap<String, CallMeta>(64, 0.75f, false) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CallMeta>?): Boolean = size > MAX_CALL_META
        }
    )

    private val errorExecutor: Executor by lazy {
        Executors.newSingleThreadExecutor { r -> Thread(r, "jarvis-gemini-error").apply { isDaemon = true } }
    }

    /** A terminal error that is delivered from a background thread, never from the caller of [stream]. */
    private fun failLater(listener: StreamListener, kind: ErrorKind, detail: String): Cancellable {
        val handle = SimpleCancellable()
        try {
            errorExecutor.execute {
                if (!handle.isCancelled) {
                    try { listener.onEvent(StreamEvent.Error(kind, detail)) } catch (e: RuntimeException) { /* ignore */ }
                }
            }
        } catch (e: RuntimeException) { /* executor rejected: nothing else to do */ }
        return handle
    }

    private class SimpleCancellable : Cancellable {
        private val flag = AtomicBoolean(false)
        override fun cancel() { flag.set(true) }
        override val isCancelled: Boolean get() = flag.get()
    }

    companion object {
        private const val TAG = "GeminiProvider"
        private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models"
        private const val FIRST_EVENT_TIMEOUT_MS = 12_000L
        private const val MAX_OUTPUT_TOKENS = 2048
        private const val MAX_CALL_META = 128

        /** Documented by Google for function calls that were not produced by the API (skips signature validation). */
        private const val DUMMY_SIGNATURE = "skip_thought_signature_validator"

        private val SCHEMA_KEYS = listOf("type", "description", "enum", "properties", "required", "items")

        /** Keeps only what the Gemini function-declaration schema accepts (no additionalProperties / min / max ...). */
        internal fun sanitize(schema: JSONObject): JSONObject {
            val out = JSONObject()
            for (key in SCHEMA_KEYS) {
                if (!schema.has(key) || schema.isNull(key)) continue
                when (key) {
                    "properties" -> {
                        val src = schema.optJSONObject(key) ?: continue
                        val props = JSONObject()
                        val names = src.keys()
                        while (names.hasNext()) {
                            val name = names.next()
                            val child = src.optJSONObject(name) ?: continue
                            props.put(name, sanitize(child))
                        }
                        out.put(key, props)
                    }
                    "items" -> schema.optJSONObject(key)?.let { out.put(key, sanitize(it)) }
                    "required" -> {
                        val arr = schema.optJSONArray(key)
                        if (arr != null && arr.length() > 0) out.put(key, arr)
                    }
                    else -> out.put(key, schema.get(key))
                }
            }
            return out
        }

        /** A non-null, non-empty string field, or null (org.json turns JSON null into the text "null"). */
        private fun JSONObject.str(key: String): String? {
            if (!has(key) || isNull(key)) return null
            val v = opt(key) as? String ?: return null
            return v.takeIf { it.isNotEmpty() }
        }
    }
}
