package com.jarvis.assistant.online

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.jarvis.assistant.command.AppRegistry
import com.jarvis.assistant.command.JarvisAction
import com.jarvis.assistant.command.JarvisActionExecutor
import com.jarvis.assistant.command.TorchMode
import com.jarvis.assistant.command.VolumeChange
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

enum class ToolErrorCode(val wire: String) {
    PERMISSION_DENIED("permission_denied"),
    NOT_FOUND("not_found"),
    AMBIGUOUS("ambiguous"),
    INVALID_ARGS("invalid_args"),
    TIMEOUT("timeout"),
    UNSUPPORTED("unsupported")
}

/** Structured tool result handed back to the model. The model may only claim success when [ok] is true. */
data class ToolResult(val ok: Boolean, val errorCode: ToolErrorCode?, val message: String) {
    fun toJson(): String = JSONObject()
        .put("ok", ok)
        .put("error_code", errorCode?.wire ?: JSONObject.NULL)
        .put("message", message)
        .toString()

    companion object {
        fun ok(message: String?) = ToolResult(true, null, message?.takeIf { it.isNotBlank() } ?: "انجام شد.")
        fun error(code: ToolErrorCode, message: String) = ToolResult(false, code, message)
    }
}

/** Result of validating a model tool call. A [Rejected] call is never executed. */
sealed class PreparedTool {
    /** [key] = tool name + canonical arguments (used to refuse a duplicate execution). */
    class Ready(val name: String, val action: JarvisAction, val key: String, val timeoutMs: Long) : PreparedTool()
    class Rejected(val result: ToolResult) : PreparedTool()
}

/**
 * The tools the online model may call: alarm, timer, call_contact, open_app, flashlight, set_volume,
 * open_settings, go_home, open_maps, web_answer (real internet lookup, Stage 3B-1). Each has a name, a description, a JSON Schema, argument validation, a conversion to a
 * [JarvisAction] and a REAL execution through the existing [JarvisActionExecutor] (no executor of its own).
 *
 * Threading: [prepare] is pure (any thread). [execute] is called from a background thread; the executor runs on
 * the main thread (Background -> Handler(Main) -> Executor -> callback -> [deliverOn] executor). Every
 * execution has a timeout (call_contact: [CALL_TIMEOUT_MS], mandatory). User arguments are never logged.
 */
class JarvisToolCatalog(
    private val executor: JarvisActionExecutor,
    private val main: Handler = Handler(Looper.getMainLooper()),
    private val apps: AppRegistry = AppRegistry.default()
) {
    private val timer: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "jarvis-tool-timeout").apply { isDaemon = true }
    }

    /** Web Answer downloads run here (never on the main thread, never through the phone executor). */
    private val webPool: ExecutorService = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "jarvis-web-answer").apply { isDaemon = true }
    }
    private val webClient = WebAnswerClient()

    val specs: List<ToolSpec> = listOf(
        spec("alarm", "Set a real alarm at a clock time (next occurrence, within 24 hours).",
            obj(
                "hour" to intProp("Hour 0-23", 0, 23),
                "minute" to intProp("Minute 0-59, default 0", 0, 59),
                "day_offset" to intProp("0 = next occurrence, 1 = explicitly tomorrow", 0, 1),
                required = listOf("hour")
            )),
        spec("timer", "Start a countdown timer.",
            obj("seconds" to intProp("Duration in seconds, 1-86400", 1, 86_400), required = listOf("seconds"))),
        spec("call_contact", "Call a saved contact by name.",
            obj("name" to strProp("Contact name as spoken", maxLen = 60), required = listOf("name"))),
        spec("send_sms", "Send a real SMS to a saved contact by name. Only call when the message text is known.",
            obj(
                "name" to strProp("Contact name as spoken", maxLen = 60),
                "text" to strProp("Exact message text to send", maxLen = 500),
                required = listOf("name", "text")
            )),
        spec("open_app", "Open ANY app installed on the phone by its name, Persian or English (e.g. تلگرام, Telegram, گالری).",
            obj("app" to strProp("App name as spoken, Persian or English", maxLen = 60), required = listOf("app"))),
        spec("flashlight", "Turn the flashlight on, off or toggle it.",
            obj("mode" to enumProp("on, off or toggle", listOf("on", "off", "toggle")), required = listOf("mode"))),
        spec("set_volume", "Change the media volume: up, down, or set to an absolute percent.",
            obj(
                "change" to enumProp("up, down or set", listOf("up", "down", "set")),
                "percent" to intProp("0-100, only with change=set", 0, 100),
                required = listOf("change")
            )),
        spec("open_settings", "Open the system settings screen (general, wifi or bluetooth).",
            obj("section" to enumProp("general (default), wifi or bluetooth", listOf("general", "wifi", "bluetooth")))),
        spec("go_home", "Go to the phone's home screen.", obj()),
        spec("open_maps", "Open the Google Maps app.", obj()),
        spec("web_answer", "Search the live internet for up-to-date facts you cannot know (today's gold / coin / currency / crypto prices, news, weather, sports results, current events) and get real result snippets. Answer in Persian only from the returned results. Not for phone actions.",
            obj("query" to strProp("Short search query, Persian or English, e.g. \"قیمت طلا امروز\"", maxLen = 200), required = listOf("query"))),
        spec("play_media", "Really play music or open a video / YouTube on the phone through the installed apps. kind=music: play a song by name; kind=video: open a video by name on YouTube; kind=youtube: just open YouTube (no query).",
            obj(
                "kind" to enumProp("music, video or youtube", listOf("music", "video", "youtube")),
                "query" to strProp("Song or video name as spoken; omit for kind=youtube", maxLen = 200),
                required = listOf("kind")
            )),
        spec("browser_search", "Open Chrome on a real Google search page. Only when the user explicitly asks to search / google something (سرچ کن، جستجو کن). Shows results in the browser; it does NOT return an answer to you.",
            obj("query" to strProp("What to search for, Persian or English", maxLen = 200), required = listOf("query")))
    )

    /** Validates [argumentsJson] for tool [name]; nothing is executed here. */
    fun prepare(name: String, argumentsJson: String): PreparedTool {
        val args = try {
            if (argumentsJson.isBlank()) JSONObject() else JSONObject(argumentsJson)
        } catch (e: JSONException) {
            return bad("arguments are not a JSON object")
        }
        return try {
            when (name) {
                "alarm" -> {
                    allowOnly(args, "hour", "minute", "day_offset")
                    val h = int(args, "hour", 0, 23, required = true)!!
                    val m = int(args, "minute", 0, 59) ?: 0
                    val d = int(args, "day_offset", 0, 1) ?: 0
                    ready(name, JarvisAction.CreateAlarm(h, m, d), "h=$h,m=$m,d=$d", DEFAULT_TIMEOUT_MS)
                }
                "timer" -> {
                    allowOnly(args, "seconds")
                    val s = int(args, "seconds", 1, 86_400, required = true)!!
                    ready(name, JarvisAction.CreateTimer(s), "s=$s", DEFAULT_TIMEOUT_MS)
                }
                "call_contact" -> {
                    allowOnly(args, "name")
                    val n = str(args, "name", 60, required = true)!!
                    ready(name, JarvisAction.ToolCall("call_contact", mapOf("name" to n)), "n=$n", CALL_TIMEOUT_MS)
                }
                "send_sms" -> {
                    allowOnly(args, "name", "text")
                    val n = str(args, "name", 60, required = true)!!
                    val t = str(args, "text", 500, required = true)!!
                    ready(name, JarvisAction.ToolCall("send_sms", mapOf("name" to n, "text" to t)), "n=$n|t=$t", SMS_TIMEOUT_MS)
                }
                "open_app" -> {
                    allowOnly(args, "app")
                    val id = str(args, "app", 60, required = true)!!
                    val entry = apps.findById(id)
                    if (entry != null) ready(name, JarvisAction.OpenApp(entry.id, entry.label, entry.packageNames), "a=${entry.id}", DEFAULT_TIMEOUT_MS)
                    else ready(name, JarvisAction.OpenAppByName(id), "n=$id", DEFAULT_TIMEOUT_MS)
                }
                "flashlight" -> {
                    allowOnly(args, "mode")
                    val mode = when (str(args, "mode", 10, required = true)) {
                        "on" -> TorchMode.ON
                        "off" -> TorchMode.OFF
                        "toggle" -> TorchMode.TOGGLE
                        else -> return bad("mode must be on, off or toggle")
                    }
                    ready(name, JarvisAction.ToggleFlashlight(mode), "m=$mode", DEFAULT_TIMEOUT_MS)
                }
                "set_volume" -> {
                    allowOnly(args, "change", "percent")
                    val percent = int(args, "percent", 0, 100)
                    val change = when (str(args, "change", 10, required = true)) {
                        "up" -> { if (percent != null) return bad("percent only with change=set"); VolumeChange.Up }
                        "down" -> { if (percent != null) return bad("percent only with change=set"); VolumeChange.Down }
                        "set" -> VolumeChange.Percent(percent ?: return bad("percent is required with change=set"))
                        else -> return bad("change must be up, down or set")
                    }
                    ready(name, JarvisAction.SetVolume(change), "c=$change", DEFAULT_TIMEOUT_MS)
                }
                "open_settings" -> {
                    allowOnly(args, "section")
                    val action = when (str(args, "section", 12) ?: "general") {
                        "general" -> JarvisAction.OpenSettings
                        "wifi" -> JarvisAction.OpenWifiSettings
                        "bluetooth" -> JarvisAction.OpenBluetoothSettings
                        else -> return bad("section must be general, wifi or bluetooth")
                    }
                    ready(name, action, "s=${action::class.simpleName}", DEFAULT_TIMEOUT_MS)
                }
                "go_home" -> {
                    allowOnly(args)
                    ready(name, JarvisAction.GoHome, "home", DEFAULT_TIMEOUT_MS)
                }
                "open_maps" -> {
                    allowOnly(args)
                    ready(name, JarvisAction.OpenApp("maps", "گوگل مپ", listOf("com.google.android.apps.maps")), "maps", DEFAULT_TIMEOUT_MS)
                }
                WEB_ANSWER -> {
                    allowOnly(args, "query")
                    val q = str(args, "query", 200, required = true)!!
                    ready(name, JarvisAction.ToolCall(WEB_ANSWER, mapOf("query" to q)), "q=${q.lowercase()}", WEB_TIMEOUT_MS)
                }
                "play_media" -> {
                    allowOnly(args, "kind", "query")
                    val kind = str(args, "kind", 10, required = true)!!
                    if (kind != "music" && kind != "video" && kind != "youtube") return bad("kind must be music, video or youtube")
                    val q = str(args, "query", 200)
                    if (kind != "youtube" && q == null) return bad("query is required for music and video")
                    ready(name, JarvisAction.ToolCall("play_media", mapOf("kind" to kind, "query" to (q ?: ""))), "k=$kind|q=${q?.lowercase().orEmpty()}", DEFAULT_TIMEOUT_MS)
                }
                "browser_search" -> {
                    allowOnly(args, "query")
                    val q = str(args, "query", 200, required = true)!!
                    ready(name, JarvisAction.ToolCall("browser_search", mapOf("query" to q)), "q=${q.lowercase()}", DEFAULT_TIMEOUT_MS)
                }
                else -> PreparedTool.Rejected(ToolResult.error(ToolErrorCode.UNSUPPORTED, "unknown tool"))
            }
        } catch (e: ArgException) {
            PreparedTool.Rejected(ToolResult.error(ToolErrorCode.INVALID_ARGS, e.message ?: "invalid arguments"))
        }
    }

    /**
     * Runs a [PreparedTool.Ready] through the existing executor. [onResult] is invoked exactly once, on
     * [deliverOn], unless the returned handle was cancelled first (then never, and a not-yet-started action is
     * not executed at all).
     */
    fun execute(tool: PreparedTool.Ready, deliverOn: Executor, onResult: (ToolResult) -> Unit): Cancellable {
        val handle = Execution(deliverOn, onResult)
        try {
            handle.timeout = timer.schedule({
                handle.deliver(ToolResult.error(ToolErrorCode.TIMEOUT, TIMEOUT_MESSAGE))
            }, tool.timeoutMs, TimeUnit.MILLISECONDS)
            if (tool.name == WEB_ANSWER) {
                val query = (tool.action as? JarvisAction.ToolCall)?.args?.get("query").orEmpty()
                val webCall = WebAnswerClient.Call()
                handle.webCall = webCall
                webPool.execute {
                    val result = try {
                        webClient.answer(query, webCall)
                    } catch (t: Throwable) {
                        Log.w(TAG, "Web answer failed")
                        ToolResult.error(ToolErrorCode.UNSUPPORTED, "دریافت اطلاعات از اینترنت با خطا روبه‌رو شد.")
                    }
                    handle.deliver(result)
                }
                return handle
            }
            val posted = main.post { handle.runOnMain(tool.action) }
            if (!posted) handle.deliver(ToolResult.error(ToolErrorCode.UNSUPPORTED, "main thread unavailable"))
        } catch (e: RuntimeException) {
            handle.deliver(ToolResult.error(ToolErrorCode.UNSUPPORTED, "could not start the tool"))
        }
        return handle
    }

    fun release() { timer.shutdownNow(); webPool.shutdownNow() }

    private inner class Execution(
        private val deliverOn: Executor,
        private val onResult: (ToolResult) -> Unit
    ) : Cancellable {
        private val cancelled = AtomicBoolean(false)
        private val done = AtomicBoolean(false)
        @Volatile var timeout: java.util.concurrent.ScheduledFuture<*>? = null
        @Volatile var webCall: Cancellable? = null

        override val isCancelled: Boolean get() = cancelled.get()
        override fun cancel() { cancelled.set(true); timeout?.cancel(false); webCall?.cancel() }

        /** Main thread. */
        fun runOnMain(action: JarvisAction) {
            if (cancelled.get() || done.get()) return          // stale: do not touch the phone
            try {
                if (executor.runsAsync(action)) {
                    executor.executeAsync(action) { outcome -> deliver(map(outcome)) }
                } else {
                    deliver(map(executor.execute(action)))
                }
            } catch (e: RuntimeException) {
                Log.w(TAG, "Tool execution failed", e)
                deliver(ToolResult.error(ToolErrorCode.UNSUPPORTED, "execution failed"))
            }
        }

        fun deliver(result: ToolResult) {
            if (cancelled.get() || !done.compareAndSet(false, true)) return
            timeout?.cancel(false)
            webCall?.cancel()                                   // a timed-out download must not keep running
            try {
                deliverOn.execute { if (!cancelled.get()) onResult(result) }
            } catch (e: RuntimeException) { /* executor shut down */ }
        }
    }

    // ---- outcome -> structured result -------------------------------------------------------------

    /** The executor reports (success, message) only, so a failure is classified from its fixed Persian message. */
    private fun map(o: JarvisActionExecutor.Outcome): ToolResult {
        if (o.success) return ToolResult.ok(o.message)
        val m = o.message ?: "نتوانستم این کار را انجام بدهم."
        val code = when {
            "چند برنامه" in m -> ToolErrorCode.AMBIGUOUS
            "اجازه" in m -> ToolErrorCode.PERMISSION_DENIED
            "چند مخاطب" in m -> ToolErrorCode.AMBIGUOUS
            "متن پیام" in m -> ToolErrorCode.INVALID_ARGS
            "مخاطبی" in m || "پیدا نشد" in m || "پیدا نکردم" in m -> ToolErrorCode.NOT_FOUND
            "درست متوجه نشدم" in m || "۲۴ ساعت" in m || "نام مخاطب را متوجه" in m -> ToolErrorCode.INVALID_ARGS
            else -> ToolErrorCode.UNSUPPORTED
        }
        return ToolResult.error(code, m)
    }

    // ---- validation helpers -----------------------------------------------------------------------

    private class ArgException(message: String) : Exception(message)

    private fun bad(msg: String) = PreparedTool.Rejected(ToolResult.error(ToolErrorCode.INVALID_ARGS, msg))

    private fun ready(name: String, action: JarvisAction, canon: String, timeoutMs: Long) =
        PreparedTool.Ready(name, action, "$name|$canon", timeoutMs)

    private fun allowOnly(args: JSONObject, vararg keys: String) {
        val it = args.keys()
        while (it.hasNext()) { val k = it.next(); if (k !in keys) throw ArgException("unknown argument: $k") }
    }

    private fun int(args: JSONObject, key: String, min: Int, max: Int, required: Boolean = false): Int? {
        if (!args.has(key) || args.isNull(key)) { if (required) throw ArgException("$key is required"); return null }
        val raw = args.get(key)
        val v = when (raw) {
            is Int -> raw
            is Long -> raw.toInt().takeIf { it.toLong() == raw }
            is Double -> raw.toInt().takeIf { it.toDouble() == raw }
            is String -> raw.trim().toIntOrNull()
            else -> null
        } ?: throw ArgException("$key must be an integer")
        if (v !in min..max) throw ArgException("$key must be in $min..$max")
        return v
    }

    private fun str(args: JSONObject, key: String, maxLen: Int, required: Boolean = false): String? {
        if (!args.has(key) || args.isNull(key)) { if (required) throw ArgException("$key is required"); return null }
        val raw = args.get(key) as? String ?: throw ArgException("$key must be a string")
        val v = raw.trim()
        if (v.isEmpty()) { if (required) throw ArgException("$key must not be empty"); return null }
        if (v.length > maxLen) throw ArgException("$key is too long")
        return v
    }

    // ---- schema helpers ---------------------------------------------------------------------------

    private fun spec(name: String, description: String, schema: JSONObject) =
        ToolSpec(name, description, schema.toString())

    private fun obj(vararg props: Pair<String, JSONObject>, required: List<String> = emptyList()): JSONObject {
        val p = JSONObject()
        for ((k, v) in props) p.put(k, v)
        return JSONObject().put("type", "object").put("properties", p)
            .put("required", JSONArray(required)).put("additionalProperties", false)
    }

    private fun intProp(desc: String, min: Int, max: Int) =
        JSONObject().put("type", "integer").put("description", desc).put("minimum", min).put("maximum", max)

    private fun strProp(desc: String, maxLen: Int) =
        JSONObject().put("type", "string").put("description", desc).put("minLength", 1).put("maxLength", maxLen)

    private fun enumProp(desc: String, values: List<String>) =
        JSONObject().put("type", "string").put("description", desc).put("enum", JSONArray(values))

    companion object {
        private const val TAG = "JarvisToolCatalog"
        const val DEFAULT_TIMEOUT_MS = 4_000L
        /** Mandatory timeout of call_contact (contact lookup is blocking work on a background thread). */
        const val CALL_TIMEOUT_MS = 8_000L
        /** send_sms: contact lookup + radio confirmation (SmsTool itself gives up after 15 s). */
        const val SMS_TIMEOUT_MS = 20_000L
        const val WEB_ANSWER = "web_answer"
        /** web_answer: two real downloads (search + news), each with its own connect / read timeout. */
        const val WEB_TIMEOUT_MS = 25_000L
        private const val TIMEOUT_MESSAGE = "نتیجه‌ی این کار به‌موقع مشخص نشد؛ نمی‌دانم انجام شد یا نه."
    }
}
