package com.jarvis.assistant.brain.llm

import android.util.Log
import com.jarvis.assistant.command.AppEntry
import com.jarvis.assistant.command.AppRegistry
import com.jarvis.assistant.command.JarvisAction
import com.jarvis.assistant.command.JarvisActionExecutor
import com.jarvis.assistant.command.ParamType
import com.jarvis.assistant.command.TorchMode
import com.jarvis.assistant.command.ToolArgs
import com.jarvis.assistant.command.ToolParam
import com.jarvis.assistant.command.ToolSpec
import com.jarvis.assistant.command.VolumeChange
import com.jarvis.assistant.memory.JarvisMemory
import com.jarvis.assistant.memory.MemoryException
import com.jarvis.assistant.nlu.PersianNormalizer

/**
 * What a tool call did. [message] goes to the LLM; [spoken] is the Persian sentence JARVIS may say if the LLM
 * cannot be reached afterwards. [deferredAction] is set for actions that end the session (go_back): the
 * conversation controller runs those itself.
 */
class ToolOutcome(
    val success: Boolean,
    val message: String,
    val spoken: String = message,
    val deferredAction: JarvisAction? = null
)

/**
 * The ONLY things the LLM can make happen. Every request is looked up by name here, validated against its
 * [ToolSpec], and executed through the existing [JarvisActionExecutor] / [JarvisTool]s (AlarmTool, TimerTool, ...)
 * or the [JarvisMemory]. An unknown name or invalid argument becomes a FAILED result, never an action.
 *
 * Tools exposing a [JarvisTool.spec] are picked up automatically from the executor's registry; the built-in
 * device actions (apps, settings, torch, volume, home, back) and memory are bound below.
 */
class LlmToolCatalog(
    private val executor: JarvisActionExecutor,
    private val memory: JarvisMemory,
    private val apps: AppRegistry = AppRegistry.default()
) {

    private class Binding(val spec: ToolSpec, val run: (Map<String, String>) -> ToolOutcome)

    private val builtIns: List<Binding> = buildBuiltIns()

    private fun bindings(): List<Binding> {
        val list = ArrayList<Binding>()
        for (tool in executor.registeredTools) {
            val spec = tool.spec ?: continue
            list.add(Binding(spec) { args -> runAction(tool.toAction(args)) })
        }
        for (b in builtIns) if (list.none { it.spec.name == b.spec.name }) list.add(b)
        return list
    }

    /** Tool schema sent to the LLM: real tools + the two control tools the Brain itself handles. */
    fun specs(): List<ToolSpec> = bindings().map { it.spec } + CONTROL_SPECS

    fun isControl(name: String): Boolean = name == ASK_USER || name == END_CONVERSATION

    /** Never throws. */
    fun invoke(call: ToolCallRequest): ToolOutcome {
        if (call.malformed) return failure("tool arguments were not valid JSON")
        val binding = bindings().firstOrNull { it.spec.name == call.name }
            ?: return failure("unknown tool '${call.name}'")
        val args = when (val v = binding.spec.validate(call.arguments)) {
            is ToolArgs.Valid -> v.values
            is ToolArgs.Invalid -> return failure("invalid arguments: ${v.reason}")
        }
        return try {
            binding.run(args)
        } catch (e: Exception) {
            Log.e(TAG, "Tool ${call.name} failed unexpectedly", e)
            failure("the tool failed unexpectedly")
        }
    }

    private fun failure(reason: String) = ToolOutcome(false, reason, GENERIC_FAILURE)

    private fun runAction(action: JarvisAction): ToolOutcome {
        val o = executor.execute(action)
        val message = o.message ?: if (o.success) DONE else GENERIC_FAILURE
        return ToolOutcome(o.success, message)
    }

    // ---- built-ins -------------------------------------------------------------------------------

    private fun buildBuiltIns(): List<Binding> {
        val appList = apps.entries.joinToString(", ") { "${it.id} (${it.label})" }
        return listOf(
            Binding(
                ToolSpec(
                    "open_app", "Open an installed app (باز کردن برنامه). Supported apps: $appList. Match by meaning: «اینستا» = instagram.",
                    listOf(ToolParam("app", ParamType.STRING, "App id from the supported list", required = true))
                ), this::openApp
            ),
            Binding(
                ToolSpec(
                    "open_settings", "Open a system settings screen (تنظیمات / وای‌فای / بلوتوث).",
                    listOf(ToolParam("section", ParamType.STRING, "Which screen, default general", enumValues = listOf("general", "wifi", "bluetooth")))
                ), this::openSettings
            ),
            Binding(
                ToolSpec(
                    "toggle_flashlight", "Turn the flashlight / phone torch on or off (چراغ قوه، نور گوشی).",
                    listOf(ToolParam("state", ParamType.STRING, "on, off, or toggle", required = true, enumValues = listOf("on", "off", "toggle")))
                ), this::flashlight
            ),
            Binding(
                ToolSpec(
                    "set_volume", "Change the media volume (صدا). up/down = one step, set = absolute percent.",
                    listOf(
                        ToolParam("direction", ParamType.STRING, "up, down or set", required = true, enumValues = listOf("up", "down", "set")),
                        ToolParam("percent", ParamType.INTEGER, "0-100, required when direction is set", min = 0, max = 100)
                    )
                ), this::volume
            ),
            Binding(
                ToolSpec(
                    "open_clock_screen", "Show the clock app's alarm list or timer list (only for 'show me my alarms/timers').",
                    listOf(ToolParam("screen", ParamType.STRING, "alarms or timers", required = true, enumValues = listOf("alarms", "timers")))
                )
            ) { a -> runAction(if (a["screen"] == "timers") JarvisAction.OpenTimerScreen else JarvisAction.OpenAlarmScreen) },
            Binding(ToolSpec("go_home", "Go to the phone's home screen (صفحه اصلی).")) { runAction(JarvisAction.GoHome) },
            Binding(ToolSpec("go_back", "Close the assistant and return to the previous screen (برگرد / بک).")) {
                ToolOutcome(true, "closing the assistant", "", JarvisAction.GoBack)
            },
            Binding(
                ToolSpec(
                    "memory_remember", "Store a fact the user EXPLICITLY asked you to remember (یادت باشه ...). key = short noun like «اسم», value = the fact.",
                    listOf(
                        ToolParam("key", ParamType.STRING, "Short subject, e.g. اسم", required = true),
                        ToolParam("value", ParamType.STRING, "The fact", required = true)
                    )
                ), this::remember
            ),
            Binding(
                ToolSpec(
                    "memory_recall", "Look up a stored fact about the user by its subject.",
                    listOf(ToolParam("key", ParamType.STRING, "Subject to look up", required = true))
                ), this::recall
            ),
            Binding(
                ToolSpec(
                    "memory_forget", "Delete one stored fact, only when the user explicitly asks to forget it.",
                    listOf(ToolParam("key", ParamType.STRING, "Subject to forget", required = true))
                ), this::forget
            )
        )
    }

    private fun openApp(a: Map<String, String>): ToolOutcome {
        val q = a.getValue("app")
        val entry = resolveApp(q)
            ?: return ToolOutcome(
                false, "app '$q' is not supported; supported apps: ${apps.entries.joinToString { it.id }}",
                "این برنامه را نمی‌توانم باز کنم."
            )
        return runAction(JarvisAction.OpenApp(entry.id, entry.label, entry.packageNames))
    }

    private fun resolveApp(query: String): AppEntry? {
        apps.findById(query.lowercase())?.let { return it }
        val n = PersianNormalizer.normalize(query)
        if (n.isEmpty()) return null
        return apps.entries.firstOrNull { e ->
            PersianNormalizer.normalize(e.label) == n || PersianNormalizer.normalize(e.id) == n ||
                e.aliases.any { PersianNormalizer.normalize(it) == n }
        }
    }

    private fun openSettings(a: Map<String, String>): ToolOutcome = runAction(
        when (a["section"]) {
            "wifi" -> JarvisAction.OpenWifiSettings
            "bluetooth" -> JarvisAction.OpenBluetoothSettings
            else -> JarvisAction.OpenSettings
        }
    )

    private fun flashlight(a: Map<String, String>): ToolOutcome = runAction(
        JarvisAction.ToggleFlashlight(
            when (a["state"]) {
                "on" -> TorchMode.ON
                "off" -> TorchMode.OFF
                else -> TorchMode.TOGGLE
            }
        )
    )

    private fun volume(a: Map<String, String>): ToolOutcome {
        val change = when (a["direction"]) {
            "up" -> VolumeChange.Up
            "down" -> VolumeChange.Down
            else -> {
                val p = a["percent"]?.toIntOrNull()
                    ?: return ToolOutcome(false, "percent is required when direction is 'set'", "درصد صدا را متوجه نشدم.")
                VolumeChange.Percent(p)
            }
        }
        return runAction(JarvisAction.SetVolume(change))
    }

    // ---- memory ----------------------------------------------------------------------------------

    private fun remember(a: Map<String, String>): ToolOutcome = try {
        memory.remember(a.getValue("key"), a.getValue("value"))
        ToolOutcome(true, "saved", "باشه، به خاطر سپردم.")
    } catch (e: MemoryException) {
        Log.w(TAG, "Memory write failed: ${e.message}")
        ToolOutcome(false, "memory write failed (${e.message})", MEMORY_ERROR)
    }

    private fun recall(a: Map<String, String>): ToolOutcome = try {
        val key = a.getValue("key")
        val value = memory.recall(key) ?: fuzzyRecall(key)
        if (value == null) ToolOutcome(true, "no stored fact about '$key'", "هنوز چیزی درباره‌اش نمی‌دانم.")
        else ToolOutcome(true, "$key: $value", "$key شما $value است.")
    } catch (e: MemoryException) {
        Log.w(TAG, "Memory read failed: ${e.message}")
        ToolOutcome(false, "memory read failed", MEMORY_ERROR)
    }

    /** Best match so far while scanning the stored facts. */
    private class MemoryMatch(val value: String, val score: Int)

    private fun fuzzyRecall(key: String): String? {
        val queryTokens: Set<String> = PersianNormalizer.tokens(key).toSet()
        if (queryTokens.isEmpty()) return null
        var best: MemoryMatch? = null
        val stored: Map<String, String> = memory.entries()
        for (entry in stored.entries) {
            var score = 0
            for (token in PersianNormalizer.tokens(entry.key)) {
                if (token in queryTokens) score++
            }
            val currentBest = best
            if (score > 0 && (currentBest == null || score > currentBest.score)) {
                best = MemoryMatch(entry.value, score)
            }
        }
        return best?.value
    }

    private fun forget(a: Map<String, String>): ToolOutcome = try {
        if (memory.forget(a.getValue("key"))) ToolOutcome(true, "forgotten", "فراموش کردم.")
        else ToolOutcome(true, "nothing was stored under that subject", "چیزی درباره‌اش به خاطر ندارم.")
    } catch (e: MemoryException) {
        Log.w(TAG, "Memory forget failed: ${e.message}")
        ToolOutcome(false, "memory update failed", MEMORY_ERROR)
    }

    companion object {
        const val ASK_USER = "ask_user"
        const val END_CONVERSATION = "end_conversation"

        private const val TAG = "LlmToolCatalog"
        private const val DONE = "انجام شد."
        private const val GENERIC_FAILURE = "نتوانستم این کار را انجام بدهم."
        private const val MEMORY_ERROR = "نتوانستم حافظه را به‌روزرسانی کنم."

        /** Handled by the Brain itself, never executed on the phone. */
        val CONTROL_SPECS: List<ToolSpec> = listOf(
            ToolSpec(
                ASK_USER,
                "Ask the user ONE short clarification question when a required parameter is missing or ambiguous. " +
                    "It is spoken and the user's next utterance is the answer. Describe the unfinished request in pending_tool / known_args / missing.",
                listOf(
                    ToolParam("question", ParamType.STRING, "The short Persian question", required = true),
                    ToolParam("pending_tool", ParamType.STRING, "Tool name this question is for, e.g. set_alarm"),
                    ToolParam("known_args", ParamType.STRING, "JSON object text with the parameters already known, e.g. {\"day_offset\":1,\"hour\":7}"),
                    ToolParam("missing", ParamType.STRING, "Comma-separated names of the missing parameters")
                )
            ),
            ToolSpec(
                END_CONVERSATION,
                "End the conversation when the user says goodbye or wants nothing more (خداحافظ، کافیه، دیگه کاری ندارم).",
                listOf(ToolParam("farewell", ParamType.STRING, "Short Persian farewell"))
            )
        )
    }
}
