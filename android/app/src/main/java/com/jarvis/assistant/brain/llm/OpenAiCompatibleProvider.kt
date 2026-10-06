package com.jarvis.assistant.brain.llm

import android.os.SystemClock
import android.util.Log
import com.jarvis.assistant.command.ParamType
import com.jarvis.assistant.command.ToolSpec
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
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
 */
class OpenAiCompatibleProvider(
    private val configSource: () -> LlmConfig?
) : LlmProvider {

    @Volatile private var toolsRejectedUntil = 0L

    override val isConfigured: Boolean
        get() = try { configSource()?.isUsable == true } catch (t: Throwable) { false }

    override val toolsAvailable: Boolean
        get() = SystemClock.elapsedRealtime() >= toolsRejectedUntil

    override fun generate(request: LlmRequest): LlmResponse {
        val cfg = try { configSource() } catch (t: Throwable) { null }
        if (cfg == null || !cfg.isUsable) return LlmResponse.Failure(LlmFailureKind.NOT_CONFIGURED)

        val withTools = request.tools.isNotEmpty() && toolsAvailable
        val first = post(cfg, request, withTools)
        if (!withTools || !looksLikeToolsRejected(first)) return first

        Log.w(TAG, "LLM server rejected the tools; repeating the request as plain chat")
        val second = post(cfg, request, false)
        if (second is LlmResponse.Success) toolsRejectedUntil = SystemClock.elapsedRealtime() + TOOLS_RETRY_AFTER_MS
        return second
    }

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
            return parse(reply.body)
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

    private fun parse(text: String): LlmResponse {
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
                val parsed = parseArgs(rawArgs)
                calls.add(
                    ToolCallRequest(
                        id = if (c.isNull("id")) "" else c.optString("id", ""),
                        name = name,
                        arguments = parsed ?: emptyMap(),
                        rawArguments = rawArgs,
                        malformed = parsed == null
                    )
                )
            }
        }
        return LlmResponse.Success(content, calls)
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
        const val TOOLS_RETRY_AFTER_MS = 10 * 60_000L
    }
}
