package com.jarvis.assistant.brain.llm

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.jarvis.assistant.brain.BrainResult
import com.jarvis.assistant.brain.JarvisBrain
import com.jarvis.assistant.brain.MemoryCommandParser
import com.jarvis.assistant.command.JarvisAction
import com.jarvis.assistant.command.SlotTarget
import com.jarvis.assistant.conversation.ConversationContext
import com.jarvis.assistant.conversation.PendingIntent
import com.jarvis.assistant.conversation.PendingToolIntent
import com.jarvis.assistant.conversation.SessionState
import com.jarvis.assistant.memory.JarvisMemory
import com.jarvis.assistant.nlu.EndConversationDetector
import com.jarvis.assistant.speech.JarvisPhrases
import java.time.LocalDateTime
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

/**
 * LLM-powered Brain (preferred when a provider is configured); the offline [fallback] brain stays in charge
 * whenever the LLM is unavailable.
 *
 *   utterance + ConversationContext + relevant memory -> LLM
 *     -> text                       => BrainResult.Conversation
 *     -> ask_user                   => BrainResult.Clarify (the open request is kept in context.llmPending)
 *     -> end_conversation           => BrainResult.EndConversation
 *     -> tool call(s)               => LlmToolCatalog (validated, executed through AlarmTool / TimerTool / executor)
 *                                      -> tool result -> LLM -> final text => BrainResult.Conversation
 *
 * Threads: requests are built and tools are executed on the MAIN thread; only [LlmProvider.generate] runs on the
 * background [worker]. [thinkAsync] calls its callback exactly once, on the main thread.
 *
 * Safety: only registered tools can run; the LLM never executes anything itself; a failed tool is reported to the
 * LLM as FAILED and, if the LLM cannot answer afterwards, JARVIS speaks the tool's own (Persian) message, so a
 * failure is never reported as success. Tools that already ran are never repeated by the offline fallback.
 * User text is never logged.
 */
class LlmJarvisBrain(
    private val provider: LlmProvider,
    private val catalog: LlmToolCatalog,
    private val memory: JarvisMemory,
    private val fallback: JarvisBrain,
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "jarvis-llm").apply { isDaemon = true }
    },
    private val main: Handler = Handler(Looper.getMainLooper()),
    private val now: () -> LocalDateTime = { LocalDateTime.now() },
    private val clock: () -> Long = { SystemClock.elapsedRealtime() }
) : JarvisBrain {

    /** After a provider failure the LLM is skipped for a while, so every utterance does not wait for a timeout. */
    @Volatile private var unavailableUntil = 0L

    private fun llmAvailable(): Boolean = provider.isConfigured && clock() >= unavailableUntil

    // ---- JarvisBrain -----------------------------------------------------------------------------

    /** Synchronous callers (no network on the main thread) get the offline brain. */
    override fun think(text: String): BrainResult = fallback.think(text)

    override fun think(text: String, context: ConversationContext): BrainResult = offline(text, context)

    override fun thinkAsync(text: String, context: ConversationContext, onResult: (BrainResult) -> Unit) {
        Run(text, context, onResult).start()
    }

    /** With a working LLM nothing runs from a partial result; the offline fast path is used only as fallback. */
    override fun isConfidentCommand(partialText: String): Boolean =
        if (llmAvailable()) false else fallback.isConfidentCommand(partialText)

    override fun pickBest(candidates: List<String>): String = fallback.pickBest(candidates)

    // ---- offline fallback ------------------------------------------------------------------------

    private fun offline(text: String, ctx: ConversationContext): BrainResult {
        syncPendingToLegacy(ctx)
        return try {
            fallback.think(text, ctx)
        } catch (e: Exception) {
            Log.e(TAG, "Offline brain failed", e)
            BrainResult.Unknown()
        }
    }

    /** An alarm / timer question the LLM asked is continued by the offline brain through the legacy pending slot. */
    private fun syncPendingToLegacy(ctx: ConversationContext) {
        val p = ctx.llmPending ?: return
        if (ctx.pending != null) return
        val missingTime = p.missing.isEmpty() || p.missing.any { it in TIME_SLOTS }
        if (!missingTime) return
        ctx.pending = when (p.tool) {
            "set_alarm" -> PendingIntent(SlotTarget.ALARM, dayOffset = p.knownArgs["day_offset"]?.toIntOrNull() ?: 0)
            "set_timer" -> PendingIntent(SlotTarget.TIMER)
            else -> null
        }
    }

    private fun noteFailure(kind: LlmFailureKind) {
        // A refused connection / quick HTTP error costs nothing to repeat (loopback), so the LLM is tried again on the
        // very next utterance: JARVIS recovers as soon as the llama.cpp server is up. Only failures that cost real
        // waiting time (timeouts) or can never succeed without a settings change (wrong key) pause the LLM.
        val pause = when (kind) {
            LlmFailureKind.AUTH, LlmFailureKind.NOT_CONFIGURED -> LONG_PAUSE_MS
            LlmFailureKind.TIMEOUT, LlmFailureKind.RATE_LIMIT -> SHORT_PAUSE_MS
            LlmFailureKind.HTTP_ERROR, LlmFailureKind.UNKNOWN -> BRIEF_PAUSE_MS
            LlmFailureKind.NO_NETWORK, LlmFailureKind.MALFORMED -> 0L
        }
        if (pause > 0) unavailableUntil = clock() + pause
    }

    // ---- one utterance ---------------------------------------------------------------------------

    private inner class Run(
        private val text: String,
        private val ctx: ConversationContext,
        private val onResult: (BrainResult) -> Unit
    ) {
        private val finished = AtomicBoolean(false)
        private val messages = ArrayList<LlmMessage>()
        private val executed = ArrayList<ToolOutcome>()      // tools that really ran this utterance (main thread)
        private var totalCalls = 0
        private val ranCalls = HashSet<String>()             // identical tool calls run once per utterance
        private var idCounter = 0
        private val watchdog = Runnable { onWatchdog() }

        fun start() {
            try {
                if (MemoryCommandParser.parse(text) != null) {
                    // An explicit memory command: the deterministic offline path handles it.
                    finish(offline(text, ctx))
                    return
                }
                endOfConversation()?.let { ctx.llmPending = null; finish(it); return }
                if (!llmAvailable()) {
                    Log.w(TAG, "LLM skipped (${if (!provider.isConfigured) "not configured" else "paused after a failure"}): offline brain answers")
                    finish(offline(text, ctx))
                    return
                }
                if (!provider.toolsAvailable && fallback.isConfidentCommand(text)) {
                    // The server cannot do tool calls: a clear device command goes through the existing command path.
                    finish(offline(text, ctx))
                    return
                }
                messages.addAll(buildMessages())
                main.postDelayed(watchdog, OVERALL_TIMEOUT_MS)
                request(0)
            } catch (e: Exception) {
                Log.e(TAG, "LLM turn failed to start", e)
                failOver()
            }
        }

        /**
         * The END_CONVERSATION intent is decided by the existing [EndConversationDetector] (sentence structure), never by
         * the LLM: a 1.5B model does not reliably call end_conversation for «خدا نگهدار». A device command always wins
         * («تایمر ۵ دقیقه»), exactly as in the offline brain.
         */
        private fun endOfConversation(): BrainResult? {
            val end = EndConversationDetector.detect(text) ?: return null
            if (fallback.isConfidentCommand(text)) return null
            return BrainResult.EndConversation(if (end.goodNight) GOODNIGHT_FAREWELL else JarvisPhrases.GOODBYE)
        }

        private fun buildMessages(): List<LlmMessage> {
            val turns = ctx.turns
            val last = turns.lastOrNull()
            val history = if (last != null && last.fromUser && last.text == text) turns.dropLast(1) else turns
            val recentUser = ctx.recentUserUtterances.takeLast(3)
            val facts = RelevantMemory.select(memory, recentUser + text)
            val system = LlmPrompt.build(now(), facts, RelevantMemory.count(memory), ctx.llmPending, provider.toolsAvailable)
            val out = ArrayList<LlmMessage>()
            out.add(LlmMessage(LlmRole.SYSTEM, system))
            for (t in history) out.add(LlmMessage(if (t.fromUser) LlmRole.USER else LlmRole.ASSISTANT, t.text))
            out.add(LlmMessage(LlmRole.USER, text))
            return out
        }

        /** Background provider call; the answer is handled back on the main thread. */
        private fun request(round: Int) {
            // Tools are offered only for the first request of an utterance. The follow-up after a tool result is just the
            // spoken confirmation: offering tools again lets a small model repeat the same action (a flashlight toggled twice).
            val tools = if (round == 0 && provider.toolsAvailable) catalog.specs() else emptyList()
            val req = LlmRequest(ArrayList(messages), tools)
            try {
                worker.execute {
                    val resp = try {
                        provider.generate(req)
                    } catch (t: Throwable) {
                        LlmResponse.Failure(LlmFailureKind.UNKNOWN, t.javaClass.simpleName)
                    }
                    main.post { if (!finished.get()) guarded { onResponse(resp, round) } }
                }
            } catch (e: RejectedExecutionException) {
                failOver()
            }
        }

        private fun guarded(block: () -> Unit) {
            try {
                block()
            } catch (e: Exception) {
                Log.e(TAG, "LLM turn failed", e)
                failOver()
            }
        }

        private fun onResponse(resp: LlmResponse, round: Int) {
            // The session ended (or was cancelled) while the LLM was thinking: do nothing more.
            if (ctx.sessionState != SessionState.PROCESSING) {
                abandon()
                return
            }
            when (resp) {
                is LlmResponse.Failure -> {
                    Log.w(TAG, "LLM failure: ${resp.kind}")
                    noteFailure(resp.kind)
                    failOver()
                }
                is LlmResponse.Success -> onSuccess(resp, round)
            }
        }

        private fun onSuccess(resp: LlmResponse.Success, round: Int) {
            val reply = clean(resp.text)
            if (resp.toolCalls.isEmpty()) {
                if (reply.isBlank()) {
                    noteFailure(LlmFailureKind.MALFORMED)
                    failOver()
                } else {
                    ctx.llmPending = null
                    finish(BrainResult.Conversation(reply))
                }
                return
            }
            if (round >= MAX_ROUNDS) {
                finish(BrainResult.Conversation(if (executed.isEmpty()) JarvisPhrases.NOT_UNDERSTOOD else summary()))
                return
            }
            processCalls(resp.toolCalls, reply, round)
        }

        private fun processCalls(calls: List<ToolCallRequest>, reply: String, round: Int) {
            val echoed = ArrayList<ToolCallRequest>()
            val results = ArrayList<ToolCallResult>()
            var endRequested = false
            var farewell = ""
            var deferred: JarvisAction? = null

            for (raw in calls) {
                val call = if (raw.id.isBlank()) raw.copy(id = "call_${++idCounter}") else raw
                echoed.add(call)
                if (totalCalls >= MAX_TOTAL_CALLS) {
                    results.add(ToolCallResult(call.id, call.name, false, "skipped: too many tool calls"))
                    continue
                }
                totalCalls++
                val callKey = call.name + call.arguments.toString()
                if (call.name != LlmToolCatalog.ASK_USER && call.name != LlmToolCatalog.END_CONVERSATION && !ranCalls.add(callKey)) {
                    results.add(ToolCallResult(call.id, call.name, true, "already done in this turn"))
                    continue
                }
                when (call.name) {
                    LlmToolCatalog.ASK_USER -> {
                        val question = clean(call.arguments["question"].orEmpty())
                        if (question.isBlank()) {
                            results.add(ToolCallResult(call.id, call.name, false, "question is required"))
                        } else {
                            ctx.llmPending = pendingFrom(call.arguments)
                            finish(BrainResult.Clarify(question))
                            return
                        }
                    }
                    LlmToolCatalog.END_CONVERSATION -> {
                        endRequested = true
                        farewell = clean(call.arguments["farewell"].orEmpty())
                        results.add(ToolCallResult(call.id, call.name, true, "ok"))
                    }
                    else -> {
                        val outcome = catalog.invoke(call)
                        Log.i(TAG, "Tool ${call.name}: ${if (outcome.success) "ok" else "failed"}")
                        if (outcome.deferredAction != null) {
                            deferred = outcome.deferredAction
                        } else {
                            executed.add(outcome)
                        }
                        results.add(ToolCallResult(call.id, call.name, outcome.success, outcome.message))
                    }
                }
            }
            ctx.llmPending = null

            if (endRequested) {
                val bye = farewell.ifBlank { reply }.ifBlank { JarvisPhrases.GOODBYE }
                val done = if (executed.isEmpty()) "" else summary() + " "
                finish(BrainResult.EndConversation(done + bye))
                return
            }
            val action = deferred
            if (action != null) {
                // go_back closes the assistant: the controller runs it (and ends the session).
                finish(BrainResult.Command(action, reply, TOOL_CONFIDENCE))
                return
            }

            // Give the tool results back to the LLM so its final sentence reflects what really happened.
            messages.add(LlmMessage(LlmRole.ASSISTANT, reply, toolCalls = echoed))
            for (r in results) {
                val body = (if (r.success) "SUCCESS: " else "FAILED: ") + r.message
                messages.add(LlmMessage(LlmRole.TOOL, body, toolCallId = r.callId, toolName = r.name))
            }
            request(round + 1)
        }

        private fun pendingFrom(args: Map<String, String>): PendingToolIntent? {
            val tool = args["pending_tool"].orEmpty().trim()
            if (tool.isEmpty()) return null
            val known = LinkedHashMap<String, String>()
            try {
                val raw = args["known_args"].orEmpty()
                if (raw.isNotBlank()) {
                    val obj = JSONObject(raw)
                    val keys = obj.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        if (!obj.isNull(k)) known[k.take(40)] = obj.get(k).toString().take(100)
                    }
                }
            } catch (e: Exception) { /* a bad known_args only loses the hint */ }
            val missing = args["missing"].orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.take(6)
            return PendingToolIntent(tool.take(40), known, missing)
        }

        /** What JARVIS says when the LLM cannot give the final sentence: the tools' own Persian messages. */
        private fun summary(): String =
            executed.map { it.spoken }.filter { it.isNotBlank() }.distinct().joinToString(" ")
                .ifBlank { JarvisPhrases.NOT_UNDERSTOOD }

        /** LLM unusable. Before any tool ran: the offline brain. After: only report what happened, never re-run. */
        private fun failOver() {
            if (finished.get()) return
            if (executed.isEmpty()) finish(offline(text, ctx))
            else finish(BrainResult.Conversation(summary()))
        }

        private fun onWatchdog() {
            if (finished.get()) return
            if (ctx.sessionState != SessionState.PROCESSING) { abandon(); return }
            Log.w(TAG, "LLM turn timed out")
            noteFailure(LlmFailureKind.TIMEOUT)
            guarded { failOver() }
        }

        private fun abandon() {
            if (finished.compareAndSet(false, true)) main.removeCallbacks(watchdog)
        }

        private fun finish(result: BrainResult) {
            if (!finished.compareAndSet(false, true)) return
            main.removeCallbacks(watchdog)
            try {
                onResult(result)
            } catch (e: Exception) {
                Log.e(TAG, "Brain callback failed", e)
            }
        }
    }

    /**
     * Makes the model output speakable: removes reasoning / tool-call markup and chat-template tokens, markdown and line
     * breaks, drops sentences in a script the Persian voice cannot read (a small model sometimes slips into Chinese),
     * and bounds the length. A blank result is treated as "no usable answer".
     */
    private fun clean(raw: String): String {
        var s = raw
        s = THINK_BLOCK.replace(s, " ")
        s = TOOL_BLOCK.replace(s, " ")
        s = TEMPLATE_TOKEN.replace(s, " ")
        s = s.replace(Regex("[*#`_>~|]+"), " ").replace(Regex("\\s+"), " ").trim()
        if (s.startsWith("{") || s.startsWith("[")) return ""          // a tool call printed as text, not an answer
        if (FOREIGN_SCRIPT.containsMatchIn(s)) {
            s = s.split(Regex("(?<=[.!؟?؛])\\s*")).filter { !FOREIGN_SCRIPT.containsMatchIn(it) }.joinToString(" ").trim()
        }
        if (s.length <= MAX_REPLY_CHARS) return s
        val cut = s.substring(0, MAX_REPLY_CHARS)
        val end = cut.lastIndexOfAny(charArrayOf('.', '!', '؟', '?', '؛'))
        return if (end > MAX_REPLY_CHARS / 3) cut.substring(0, end + 1) else cut
    }

    private companion object {
        const val TAG = "LlmJarvisBrain"
        const val MAX_ROUNDS = 3
        const val MAX_TOTAL_CALLS = 4
        const val OVERALL_TIMEOUT_MS = 58_000L      // must stay below the controller's 65 s PROCESSING_TIMEOUT_MS
        const val BRIEF_PAUSE_MS = 5_000L
        const val SHORT_PAUSE_MS = 15_000L
        const val LONG_PAUSE_MS = 5 * 60_000L
        const val MAX_REPLY_CHARS = 320
        const val GOODNIGHT_FAREWELL = "شب بخیر ارباب. هر وقت لازم شد صدایم کنید."
        val THINK_BLOCK = Regex("(?s)<think>.*?(</think>|$)")
        val TOOL_BLOCK = Regex("(?s)<tool_call>.*?(</tool_call>|$)")
        val TEMPLATE_TOKEN = Regex("<\\|[^>]*?\\|>")
        val FOREIGN_SCRIPT = Regex("[\\u3040-\\u30FF\\u3400-\\u4DBF\\u4E00-\\u9FFF\\uAC00-\\uD7AF]")
        const val TOOL_CONFIDENCE = 0.95f
        val TIME_SLOTS = setOf("hour", "time", "minute", "minutes", "hours", "seconds", "duration", "period")
    }
}
