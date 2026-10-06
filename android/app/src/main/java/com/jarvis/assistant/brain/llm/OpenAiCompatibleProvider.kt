package com.jarvis.assistant.brain.llm

import android.os.SystemClock
import android.util.Log
import com.jarvis.assistant.command.ParamType
import com.jarvis.assistant.command.ToolSpec
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.io.InputStream
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.util.TreeMap
import javax.net.ssl.SSLException
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * [LlmProvider] for any OpenAI-compatible `chat/completions` endpoint with function calling (llama.cpp server,
 * vLLM, Ollama's /v1, LM Studio, ...). No extra dependency. The config is read on every call, so a settings change
 * applies immediately. Neither the API key nor any request/response text is logged.
 *
 * Transport: `http://` (the local llama.cpp server on 127.0.0.1) goes through [LoopbackHttp], because
 * HttpURLConnection refuses cleartext HTTP, loopback included, under Android's default network security config;
 * `https://` uses HttpURLConnection.
 *
 * Tools: a server that was started without tool support (llama.cpp without --jinja answers HTTP 500/400 to any
 * request that carries `tools`) must still hold a normal conversation. In that case the same request is repeated
 * as plain chat, and [toolsAvailable] stays false for [TOOLS_RETRY_AFTER_MS] so the Brain neither offers tools nor
 * pays for a failing request on every utterance.
 *
 * Streaming ([generateStream]): the same endpoint with `"stream": true`; the reply arrives as Server-Sent Events and
 * its text is passed on while it is generated. If the server cannot stream (HTTP error before anything arrived, or a
 * non-SSE answer) the very same request is repeated through [generate], so the previous behaviour is the fallback.
 * Nothing is ever repeated once text was delivered. The streaming request uses the same timeouts as [generate].
 */
class OpenAiCompatibleProvider(
    private val configSource: () -> LlmConfig?
) : LlmProvider {

    @Volatile private var toolsRejectedUntil = 0L
    @Volatile private var toolsFailStreak = 0               // consecutive tool requests the server refused
    @Volatile private var streamRejectedUntil = 0L          // streaming refused for any request
    @Volatile private var streamToolsRejectedUntil = 0L     // streaming refused only together with tools

    override val isConfigured: Boolean
        get() = try { configSource()?.isUsable == true } catch (t: Throwable) { false }

    override val toolsAvailable: Boolean
        get() = SystemClock.elapsedRealtime() >= toolsRejectedUntil

    override fun generate(request: LlmRequest): LlmResponse {
        val cfg = try { configSource() } catch (t: Throwable) { null }
        if (cfg == null || !cfg.isUsable) return LlmResponse.Failure(LlmFailureKind.NOT_CONFIGURED)

        val withTools = request.tools.isNotEmpty() && toolsAvailable
        val first = post(cfg, request, withTools)
        if (withTools && first is LlmResponse.Success) toolsFailStreak = 0
        if (!withTools || !looksLikeToolsRejected(first)) return first

        // One refused request is not proof that the server has no tool support: a 1.5B model sometimes produces a
        // tool call llama.cpp cannot parse (HTTP 500). Tools are switched off only after two refusals in a row.
        Log.w(TAG, "LLM server rejected the tools; repeating the request as plain chat")
        val second = post(cfg, request, false)
        if (second is LlmResponse.Success && ++toolsFailStreak >= TOOLS_FAIL_LIMIT) {
            toolsRejectedUntil = SystemClock.elapsedRealtime() + TOOLS_RETRY_AFTER_MS
            toolsFailStreak = 0
        }
        return second
    }

    // ---- streaming -------------------------------------------------------------------------------

    override fun generateStream(request: LlmRequest, listener: LlmStreamListener, cancel: LlmCancel): LlmResponse {
        val cfg = try { configSource() } catch (t: Throwable) { null }
        if (cfg == null || !cfg.isUsable) return LlmResponse.Failure(LlmFailureKind.NOT_CONFIGURED)
        if (cancel.isCancelled) return LlmResponse.Failure(LlmFailureKind.UNKNOWN, "cancelled")

        val withTools = request.tools.isNotEmpty() && toolsAvailable
        val now = SystemClock.elapsedRealtime()
        if (now < streamRejectedUntil || (withTools && now < streamToolsRejectedUntil)) return generate(request)

        val acc = StreamAccumulator(listener, toolNames(request, withTools))
        val result = postStream(cfg, request, withTools, acc, cancel)
        if (result is LlmResponse.Success) {
            if (withTools) toolsFailStreak = 0
            return result
        }
        val failure = result as LlmResponse.Failure
        if (cancel.isCancelled) return LlmResponse.Failure(LlmFailureKind.UNKNOWN, "cancelled")

        // Text already reached the listener (and the voice): never ask again, keep what was said.
        if (acc.hasOutput) {
            Log.w(TAG, "LLM stream ended early (${failure.kind}); using the partial reply")
            return if (acc.text.isNotBlank()) LlmResponse.Success(acc.text, emptyList()) else failure
        }
        return when (failure.kind) {
            LlmFailureKind.HTTP_ERROR, LlmFailureKind.MALFORMED, LlmFailureKind.UNKNOWN -> {
                if (failure.detail == "HTTP 503") return failure          // model still loading: plain request would wait too
                Log.w(TAG, "LLM streaming failed (${failure.kind}); using the non-streaming request")
                val until = SystemClock.elapsedRealtime() + STREAM_RETRY_AFTER_MS
                if (withTools) streamToolsRejectedUntil = until else streamRejectedUntil = until
                generate(request)
            }
            // Timeout / connection refused / wrong key / rate limit: the plain request would fail the same way.
            else -> failure
        }
    }

    private fun postStream(
        cfg: LlmConfig,
        request: LlmRequest,
        withTools: Boolean,
        acc: StreamAccumulator,
        cancel: LlmCancel
    ): LlmResponse {
        var conn: HttpURLConnection? = null
        try {
            val body = buildBody(cfg, request, withTools).put("stream", true).toString().toByteArray(Charsets.UTF_8)
            val url = endpoint(cfg.baseUrl)
            val code: Int
            if (url.startsWith("http://", ignoreCase = true)) {
                val headers = LinkedHashMap<String, String>()
                headers["Content-Type"] = "application/json; charset=utf-8"
                headers["Accept"] = "text/event-stream"
                if (cfg.apiKey.isNotBlank()) headers["Authorization"] = "Bearer ${cfg.apiKey}"
                code = LoopbackHttp.postStream(
                    url, headers, body, cfg.connectTimeoutMs, cfg.readTimeoutMs, MAX_STREAM_BYTES, cancel
                ) { line -> acc.onLine(line) }
            } else {
                val c = URL(url).openConnection() as HttpURLConnection
                conn = c
                cancel.attach { try { c.disconnect() } catch (t: Throwable) { /* ignore */ } }
                c.requestMethod = "POST"
                c.connectTimeout = cfg.connectTimeoutMs
                c.readTimeout = cfg.readTimeoutMs
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                c.setRequestProperty("Accept", "text/event-stream")
                if (cfg.apiKey.isNotBlank()) c.setRequestProperty("Authorization", "Bearer ${cfg.apiKey}")
                c.outputStream.use { it.write(body) }
                code = c.responseCode
                if (code in 200..299) {
                    BufferedReader(InputStreamReader(c.inputStream, Charsets.UTF_8)).use { r ->
                        while (true) {
                            val line = r.readLine() ?: break
                            if (!acc.onLine(line)) break
                        }
                    }
                }
            }
            if (code !in 200..299) {
                Log.w(TAG, "LLM HTTP $code (stream)")
                return LlmResponse.Failure(kindForHttp(code), "HTTP $code")
            }
            return acc.result()
        } catch (t: Throwable) {
            return failureFor(t)
        } finally {
            cancel.detach()
            try { conn?.disconnect() } catch (t: Throwable) { /* ignore */ }
        }
    }

    private fun failureFor(e: Throwable): LlmResponse.Failure = when (e) {
        is SocketTimeoutException -> {
            Log.w(TAG, "LLM timed out")
            LlmResponse.Failure(LlmFailureKind.TIMEOUT)
        }
        is UnknownHostException -> LlmResponse.Failure(LlmFailureKind.NO_NETWORK)
        is ConnectException -> {
            Log.w(TAG, "LLM server not reachable (is llama.cpp running on the configured port?)")
            LlmResponse.Failure(LlmFailureKind.NO_NETWORK)
        }
        is MalformedURLException -> LlmResponse.Failure(LlmFailureKind.NOT_CONFIGURED, "bad URL")
        is SSLException -> LlmResponse.Failure(LlmFailureKind.HTTP_ERROR, "TLS error")
        is IOException -> {
            Log.w(TAG, "LLM I/O error: ${e.javaClass.simpleName}")     // class only: never the message or any text
            LlmResponse.Failure(LlmFailureKind.NO_NETWORK)
        }
        is JSONException -> LlmResponse.Failure(LlmFailureKind.MALFORMED)
        else -> {
            Log.w(TAG, "LLM call failed: ${e.javaClass.simpleName}")
            LlmResponse.Failure(LlmFailureKind.UNKNOWN, e.javaClass.simpleName)
        }
    }

    /** Pieces of one streamed tool call (a nested class is not allowed inside an inner class). */
    private class StreamCallBuilder {
        var id = ""
        var name = ""
        val args = StringBuilder()
    }

    /**
     * Collects a streamed completion line by line: `data: {chunk}` events with `delta.content` text and
     * `delta.tool_calls` fragments (assembled per index), ended by `data: [DONE]` or by the connection closing.
     * A server that ignores `stream` and answers with one plain JSON body is understood too ([result] parses it).
     * No text is logged.
     */
    private inner class StreamAccumulator(private val listener: LlmStreamListener, private val names: Set<String>) {
        val textBuf = StringBuilder()
        private val calls = TreeMap<Int, StreamCallBuilder>()
        private val raw = StringBuilder()
        private var sse = false
        private var done = false
        private var stoppedByListener = false
        private var serverError = false
        private var malformed = false
        private var toolStarted = false
        private var decided = false                      // is the kind of this reply (text / tool call as text) known?
        private var holding = false                      // text that looks like a tool call is kept back, not spoken

        val text: String get() = textBuf.toString()

        /** Text or a tool call reached the listener: the request must not be repeated. */
        val hasOutput: Boolean get() = (textBuf.isNotEmpty() && !holding) || toolStarted

        /** @return false to stop reading. */
        fun onLine(line: String): Boolean {
            if (done) return false
            if (line.isEmpty()) return true
            if (line.startsWith("data:")) {
                sse = true
                val payload = line.substring(5).trim()
                if (payload == "[DONE]") { done = true; return false }
                if (payload.isEmpty()) return true
                return onPayload(payload)
            }
            if (line.startsWith(":") || line.startsWith("event:") || line.startsWith("id:") || line.startsWith("retry:")) return true
            if (!sse) {                                      // not SSE: a plain JSON body (server ignored "stream")
                raw.append(line).append('\n')
                if (raw.length > MAX_RESPONSE_BYTES) { malformed = true; return false }
            }
            return true
        }

        private fun onPayload(payload: String): Boolean {
            val root = try { JSONObject(payload) } catch (e: JSONException) { malformed = true; return false }
            if (root.has("error") && !root.isNull("error")) { serverError = true; return false }
            val choices = root.optJSONArray("choices")
            if (choices == null || choices.length() == 0) return true
            val delta = choices.optJSONObject(0)?.optJSONObject("delta") ?: return true

            val content = delta.opt("content")
            if (content is String && content.isNotEmpty()) {
                textBuf.append(content)
                val wasDecided = decided
                if (!decided) {
                    // A small model may write its tool call as text (`<tool_call>{...}`) instead of `tool_calls`.
                    // Such text must not be spoken: it is held back and recovered in result().
                    val t = textBuf.toString().trimStart()
                    if (t.isNotEmpty()) {
                        val couldBeTag = names.isNotEmpty() && t.length < TOOL_OPEN.length && TOOL_OPEN.startsWith(t)
                        if (!couldBeTag) {
                            decided = true
                            holding = names.isNotEmpty() && (t[0] == '{' || t[0] == '[' || t.startsWith(TOOL_OPEN))
                        }
                    }
                }
                if (decided && !holding) {
                    val out = if (wasDecided) content else textBuf.toString().trimStart()
                    if (!listener.onTextDelta(out)) { stoppedByListener = true; return false }
                }
            }
            val arr = delta.optJSONArray("tool_calls")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val c = arr.optJSONObject(i) ?: continue
                    val index = if (c.has("index") && !c.isNull("index")) c.optInt("index", i) else i
                    val b = calls.getOrPut(index) { StreamCallBuilder() }
                    if (!c.isNull("id")) {
                        val id = c.optString("id", "")
                        if (id.isNotEmpty() && b.id.isEmpty()) b.id = id
                    }
                    val fn = c.optJSONObject("function")
                    if (fn != null) {
                        if (!fn.isNull("name")) {
                            val n = fn.optString("name", "")
                            if (n.isNotEmpty() && b.name.isEmpty()) b.name = n
                        }
                        if (fn.has("arguments") && !fn.isNull("arguments")) {
                            val a = fn.get("arguments")
                            b.args.append(if (a is String) a else a.toString())
                        }
                    }
                    if (!toolStarted) {
                        toolStarted = true
                        listener.onToolCallStarted()
                    }
                }
            }
            return true
        }

        fun result(): LlmResponse {
            if (serverError) return LlmResponse.Failure(LlmFailureKind.HTTP_ERROR, "error object")
            if (malformed) return LlmResponse.Failure(LlmFailureKind.MALFORMED)
            if (!sse) {
                // The server did not stream: parse the body as a normal completion (nothing was delivered live).
                if (raw.isBlank()) return LlmResponse.Failure(LlmFailureKind.MALFORMED, "empty stream")
                return try { parse(raw.toString(), names) } catch (e: JSONException) { LlmResponse.Failure(LlmFailureKind.MALFORMED) }
            }
            if (!done && !stoppedByListener && textBuf.isEmpty() && !toolStarted) {
                return LlmResponse.Failure(LlmFailureKind.MALFORMED, "empty stream")
            }
            val out = ArrayList<ToolCallRequest>()
            for (b in calls.values) {
                val n = b.name.trim()
                if (n.isEmpty()) continue
                out.add(toolCall(b.id, n, b.args.toString()))
            }
            if (holding && out.isEmpty()) {
                val s = salvageToolCalls(textBuf.toString(), names)
                if (s != null) {
                    listener.onToolCallStarted()
                    return LlmResponse.Success(s.text, s.calls)
                }
                return LlmResponse.Success("", out)       // unusable tool-call text: never spoken
            }
            return LlmResponse.Success(textBuf.toString(), out)
        }
    }

    private fun toolCall(id: String, name: String, rawArgs: String): ToolCallRequest {
        val parsed = parseArgs(rawArgs)
        return ToolCallRequest(
            id = id,
            name = name,
            arguments = parsed ?: emptyMap(),
            rawArguments = rawArgs,
            malformed = parsed == null
        )
    }

    // ---- plain request ---------------------------------------------------------------------------

    /** A quick HTTP error (not "model is still loading", 503) to a request that carried tools. */
    private fun looksLikeToolsRejected(r: LlmResponse): Boolean =
        r is LlmResponse.Failure && r.kind == LlmFailureKind.HTTP_ERROR &&
            r.detail.startsWith("HTTP ") && r.detail != "HTTP 503"

    private fun post(cfg: LlmConfig, request: LlmRequest, withTools: Boolean): LlmResponse {
        var conn: HttpURLConnection? = null
        try {
            val body = buildBody(cfg, request, withTools).toString().toByteArray(Charsets.UTF_8)
            val url = endpoint(cfg.baseUrl)
            val reply: LoopbackHttp.Reply
            if (url.startsWith("http://", ignoreCase = true)) {
                val headers = LinkedHashMap<String, String>()
                headers["Content-Type"] = "application/json; charset=utf-8"
                headers["Accept"] = "application/json"
                if (cfg.apiKey.isNotBlank()) headers["Authorization"] = "Bearer ${cfg.apiKey}"
                reply = LoopbackHttp.post(url, headers, body, cfg.connectTimeoutMs, cfg.readTimeoutMs, MAX_RESPONSE_BYTES)
            } else {
                conn = URL(url).openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = cfg.connectTimeoutMs
                conn.readTimeout = cfg.readTimeoutMs
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.setRequestProperty("Accept", "application/json")
                if (cfg.apiKey.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer ${cfg.apiKey}")
                conn.outputStream.use { it.write(body) }
                val c = conn.responseCode
                val stream = if (c in 200..299) conn.inputStream else conn.errorStream
                reply = LoopbackHttp.Reply(c, try { stream?.use { readLimited(it) } ?: "" } catch (e: IOException) { "" })
            }

            if (reply.code !in 200..299) {
                Log.w(TAG, "LLM HTTP ${reply.code}")
                return LlmResponse.Failure(kindForHttp(reply.code), "HTTP ${reply.code}")
            }
            return parse(reply.body, toolNames(request, withTools))
        } catch (e: SocketTimeoutException) {
            Log.w(TAG, "LLM timed out")
            return LlmResponse.Failure(LlmFailureKind.TIMEOUT)
        } catch (e: UnknownHostException) {
            return LlmResponse.Failure(LlmFailureKind.NO_NETWORK)
        } catch (e: ConnectException) {
            Log.w(TAG, "LLM server not reachable (is llama.cpp running on the configured port?)")
            return LlmResponse.Failure(LlmFailureKind.NO_NETWORK)
        } catch (e: MalformedURLException) {
            return LlmResponse.Failure(LlmFailureKind.NOT_CONFIGURED, "bad URL")
        } catch (e: SSLException) {
            return LlmResponse.Failure(LlmFailureKind.HTTP_ERROR, "TLS error")
        } catch (e: IOException) {
            Log.w(TAG, "LLM I/O error: ${e.javaClass.simpleName}")     // class only: never the message or any text
            return LlmResponse.Failure(LlmFailureKind.NO_NETWORK)
        } catch (e: JSONException) {
            return LlmResponse.Failure(LlmFailureKind.MALFORMED)
        } catch (t: Throwable) {
            Log.w(TAG, "LLM call failed: ${t.javaClass.simpleName}")
            return LlmResponse.Failure(LlmFailureKind.UNKNOWN, t.javaClass.simpleName)
        } finally {
            try { conn?.disconnect() } catch (t: Throwable) { /* ignore */ }
        }
    }

    // ---- request ---------------------------------------------------------------------------------

    private fun endpoint(baseUrl: String): String {
        val b = baseUrl.trim().trimEnd('/')
        return if (b.endsWith("/chat/completions")) b else "$b/chat/completions"
    }

    private fun buildBody(cfg: LlmConfig, request: LlmRequest, withTools: Boolean): JSONObject {
        val messages = JSONArray()
        for (m in request.messages) messages.put(messageJson(m))
        val body = JSONObject()
            .put("model", cfg.model)
            .put("messages", messages)
            .put("temperature", request.temperature.toDouble())
            .put(cfg.maxTokensField, request.maxTokens)
        if (withTools) {
            val tools = JSONArray()
            for (t in request.tools) tools.put(toolJson(t))
            body.put("tools", tools).put("tool_choice", "auto")
        }
        return body
    }

    private fun messageJson(m: LlmMessage): JSONObject {
        val o = JSONObject()
        when (m.role) {
            LlmRole.SYSTEM -> o.put("role", "system").put("content", m.content)
            LlmRole.USER -> o.put("role", "user").put("content", m.content)
            LlmRole.ASSISTANT -> {
                o.put("role", "assistant")
                o.put("content", m.content)    // "" (not null) when only tools were called: llama.cpp chat templates need a string
                if (m.toolCalls.isNotEmpty()) {
                    val calls = JSONArray()
                    for (c in m.toolCalls) {
                        val args = c.rawArguments.ifBlank { JSONObject(c.arguments as Map<*, *>).toString() }
                        calls.put(
                            JSONObject().put("id", c.id).put("type", "function")
                                .put("function", JSONObject().put("name", c.name).put("arguments", args))
                        )
                    }
                    o.put("tool_calls", calls)
                }
            }
            LlmRole.TOOL -> {
                o.put("role", "tool").put("tool_call_id", m.toolCallId ?: "").put("content", m.content)
            }
        }
        return o
    }

    private fun toolJson(t: ToolSpec): JSONObject {
        val props = JSONObject()
        val required = JSONArray()
        for (p in t.params) {
            val prop = JSONObject()
                .put("type", when (p.type) {
                    ParamType.STRING -> "string"
                    ParamType.INTEGER -> "integer"
                    ParamType.BOOLEAN -> "boolean"
                })
                .put("description", p.description)
            if (p.enumValues.isNotEmpty()) prop.put("enum", JSONArray(p.enumValues))
            p.min?.let { prop.put("minimum", it) }
            p.max?.let { prop.put("maximum", it) }
            props.put(p.name, prop)
            if (p.required) required.put(p.name)
        }
        val params = JSONObject().put("type", "object").put("properties", props)
        if (required.length() > 0) params.put("required", required)
        return JSONObject().put("type", "function").put(
            "function", JSONObject().put("name", t.name).put("description", t.description).put("parameters", params)
        )
    }

    // ---- response --------------------------------------------------------------------------------

    private fun parse(text: String, names: Set<String> = emptySet()): LlmResponse {
        val root = JSONObject(text)
        if (root.has("error") && !root.isNull("error")) return LlmResponse.Failure(LlmFailureKind.HTTP_ERROR, "error object")
        val choices = root.optJSONArray("choices")
        if (choices == null || choices.length() == 0) return LlmResponse.Failure(LlmFailureKind.MALFORMED, "no choices")
        val msg = choices.optJSONObject(0)?.optJSONObject("message")
            ?: return LlmResponse.Failure(LlmFailureKind.MALFORMED, "no message")

        val content = contentText(msg)
        val calls = ArrayList<ToolCallRequest>()
        val arr = msg.optJSONArray("tool_calls")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val c = arr.optJSONObject(i) ?: continue
                val fn = c.optJSONObject("function") ?: continue
                val name = fn.optString("name", "").trim()
                if (name.isEmpty()) continue
                val rawArgs = when {
                    !fn.has("arguments") || fn.isNull("arguments") -> ""
                    else -> fn.get("arguments").let { if (it is String) it else it.toString() }
                }
                calls.add(toolCall(if (c.isNull("id")) "" else c.optString("id", ""), name, rawArgs))
            }
        }
        if (calls.isEmpty()) {
            val s = salvageToolCalls(content, names)
            if (s != null) return LlmResponse.Success(s.text, s.calls)
        }
        return LlmResponse.Success(content, calls)
    }

    private fun toolNames(request: LlmRequest, withTools: Boolean): Set<String> =
        if (withTools) request.tools.map { it.name }.toSet() else emptySet()

    /** Tool calls the model wrote into its text, and the text that is left once they are removed. */
    private class Salvaged(val text: String, val calls: List<ToolCallRequest>)

    /**
     * Qwen2.5-1.5B often prints its call as `<tool_call>{"name": ..., "arguments": {...}}</tool_call>` (or as bare JSON)
     * in a form llama.cpp does not turn into `tool_calls`. Only a call to a tool that was offered in this request is
     * accepted; anything else stays plain text. Null = nothing usable.
     */
    private fun salvageToolCalls(text: String, names: Set<String>): Salvaged? {
        if (names.isEmpty() || text.isBlank()) return null
        val calls = ArrayList<ToolCallRequest>()
        val rest = StringBuilder()
        var sawTag = false
        var i = 0
        while (i < text.length) {
            val open = text.indexOf(TOOL_OPEN, i)
            if (open < 0) { rest.append(text, i, text.length); break }
            sawTag = true
            rest.append(text, i, open)
            val bodyStart = open + TOOL_OPEN.length
            val close = text.indexOf(TOOL_CLOSE, bodyStart)
            val end = if (close < 0) text.length else close
            calls.addAll(callsFromJson(text.substring(bodyStart, end), names))
            i = if (close < 0) text.length else close + TOOL_CLOSE.length
        }
        if (!sawTag) {
            val t = text.trim()
            val bare = (t.startsWith("{") && t.endsWith("}")) || (t.startsWith("[") && t.endsWith("]"))
            if (!bare) return null
            calls.addAll(callsFromJson(t, names))
            rest.setLength(0)
        }
        if (calls.isEmpty()) return null
        Log.i(TAG, "Recovered ${calls.size} tool call(s) written as text")
        return Salvaged(rest.toString().trim(), calls)
    }

    private fun callsFromJson(body: String, names: Set<String>): List<ToolCallRequest> {
        val out = ArrayList<ToolCallRequest>()
        var k = 0
        while (k < body.length) {
            val s = body.indexOf('{', k)
            if (s < 0) break
            val e = objectEnd(body, s)
            if (e < 0) break
            k = e + 1
            val obj = try { JSONObject(body.substring(s, e + 1)) } catch (ex: JSONException) { continue }
            val fn = obj.optJSONObject("function") ?: obj
            val name = fn.optString("name", "").trim()
            if (name !in names) continue
            val a: Any? = when {
                fn.has("arguments") && !fn.isNull("arguments") -> fn.get("arguments")
                fn.has("parameters") && !fn.isNull("parameters") -> fn.get("parameters")
                else -> null
            }
            out.add(toolCall("", name, if (a == null) "" else if (a is String) a else a.toString()))
        }
        return out
    }

    /** Index of the `}` that closes the object opened at [start]; -1 when it never closes (cut-off output). */
    private fun objectEnd(s: String, start: Int): Int {
        var depth = 0
        var inStr = false
        var esc = false
        for (i in start until s.length) {
            val c = s[i]
            if (inStr) {
                if (esc) esc = false else if (c == '\\') esc = true else if (c == '"') inStr = false
            } else when (c) {
                '"' -> inStr = true
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) return i }
            }
        }
        return -1
    }

    private fun contentText(msg: JSONObject): String {
        if (!msg.has("content") || msg.isNull("content")) return ""
        val c = msg.get("content")
        if (c is String) return c
        if (c is JSONArray) {
            val sb = StringBuilder()
            for (i in 0 until c.length()) {
                val part = c.optJSONObject(i) ?: continue
                if (!part.isNull("text")) sb.append(part.optString("text", ""))
            }
            return sb.toString()
        }
        return ""
    }

    /** Null = not a JSON object. A blank string is a call without arguments. */
    private fun parseArgs(raw: String): Map<String, String>? {
        if (raw.isBlank()) return emptyMap()
        val obj = try { JSONObject(raw) } catch (e: JSONException) { return null }
        val out = LinkedHashMap<String, String>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            if (obj.isNull(k)) continue
            val v = obj.get(k)
            out[k] = when (v) {
                is String -> v
                is Number -> if (v.toDouble() % 1.0 == 0.0 && Math.abs(v.toDouble()) < 1e15) v.toLong().toString() else v.toString()
                else -> v.toString()
            }
        }
        return out
    }

    private fun readLimited(input: InputStream): String {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > MAX_RESPONSE_BYTES) throw IOException("response too large")
        }
        return out.toString("UTF-8")
    }

    private fun kindForHttp(code: Int): LlmFailureKind = when (code) {
        401, 403 -> LlmFailureKind.AUTH
        429 -> LlmFailureKind.RATE_LIMIT
        else -> LlmFailureKind.HTTP_ERROR
    }

    private companion object {
        const val TAG = "OpenAiProvider"
        const val MAX_RESPONSE_BYTES = 256 * 1024
        const val MAX_STREAM_BYTES = 512 * 1024
        const val STREAM_RETRY_AFTER_MS = 5 * 60_000L
        const val TOOLS_RETRY_AFTER_MS = 2 * 60_000L
        const val TOOLS_FAIL_LIMIT = 2
        const val TOOL_OPEN = "<tool_call>"
        const val TOOL_CLOSE = "</tool_call>"
    }
}
