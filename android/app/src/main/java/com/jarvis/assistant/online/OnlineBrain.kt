package com.jarvis.assistant.online

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** True when the active network has internet access (needs ACCESS_NETWORK_STATE). Never throws. */
internal object OnlineNetwork {
    fun isConnected(context: Context): Boolean = try {
        val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
        caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    } catch (e: RuntimeException) {
        false
    }
}

private fun mainPoster(): (Runnable) -> Unit {
    val h = Handler(Looper.getMainLooper())
    return { r -> h.post(r) }
}

enum class FailureKind { UNAVAILABLE, TIMEOUT, NETWORK, PROVIDER, LIMIT, EMPTY }

/** [lastToolMessage]: the last tool's spoken message when a tool already ran before the failure, else null. */
data class Failure(val kind: FailureKind, val lastToolMessage: String?)

/**
 * Agent infrastructure of the Online Brain (Stage 1):
 *
 *   request -> Provider (streaming) -> text deltas -> SentenceChunker -> [Listener.onSentence]
 *                                   -> tool call -> JarvisToolCatalog -> tool result -> next model round
 *
 *  - role based history (user / assistant / tool), about [HISTORY_MAX] messages, trimmed only at a USER
 *    boundary so a tool_call is never separated from its tool_result;
 *  - at most [MAX_ROUNDS] model rounds and [MAX_TOOL_CALLS] tool calls per turn (the last round is offered no
 *    tools, so it must answer); an identical tool call in a turn is never executed twice; every call keeps its
 *    `tool_call_id`;
 *  - cancellation, per-round and per-turn timeouts, stale-callback protection (turn + round ids), fallback via
 *    [Failure]; user text is never logged.
 *
 * Network and the agent loop run on one background thread; tools hop to the main thread inside the catalog.
 * Listener callbacks are delivered on the main thread, only while the turn is not cancelled.
 * Without a provider the brain is simply off ([isAvailable] is false); nothing crashes.
 */
class OnlineBrain(
    private val provider: OnlineProvider?,
    private val tools: JarvisToolCatalog?,
    private val isNetworkAvailable: () -> Boolean = { true },
    private val mainPost: (Runnable) -> Unit = mainPoster(),
    private val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    private val roundTimeoutMs: Long = ROUND_TIMEOUT_MS,
    private val turnTimeoutMs: Long = TURN_TIMEOUT_MS
) {
    interface Listener {
        /** A short chunk ready for TTS, in order. Main thread. */
        fun onSentence(text: String)
        /** Normal end; every sentence was already delivered. [fullText] is the whole answer. Main thread. */
        fun onFinished(fullText: String)
        /** The turn failed (sentences may already have been delivered). Main thread. */
        fun onFailed(failure: Failure)
    }

    private val agent: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "jarvis-online-agent").apply { isDaemon = true }
    }

    /** Session history. Touched only on the agent thread. */
    private val history = ArrayList<ChatMessage>()

    @Volatile private var current: Turn? = null

    fun isAvailable(): Boolean {
        val p = provider ?: return false
        return try { p.isAvailable() && isNetworkAvailable() } catch (e: RuntimeException) { false }
    }

    /** Starts one turn (cancelling any running one). The returned handle cancels it. */
    fun ask(text: String, listener: Listener, imageBase64: String? = null): Cancellable {
        current?.cancel()
        val turn = Turn(listener, text, imageBase64)
        current = turn
        if (!isAvailable()) { fail(turn, FailureKind.UNAVAILABLE); return turn }
        turn.turnTimer = scheduleSafe(turnTimeoutMs) { fail(turn, FailureKind.TIMEOUT) }
        if (!runAgent { begin(turn) }) fail(turn, FailureKind.UNAVAILABLE)
        return turn
    }

    /** Forgets the session history (call when a conversation session starts or ends). */
    fun resetSession() { runAgent { history.clear() } }

    fun release() {
        current?.cancel()
        current = null
        try { agent.shutdownNow() } catch (e: RuntimeException) { /* ignore */ }
        tools?.release()
    }

    // ---- turn ------------------------------------------------------------------------------------

    private inner class Turn(val listener: Listener, val userText: String, val imageBase64: String? = null) : Cancellable {
        val userContent: String = if (imageBase64 != null) VisionAttachment.attach(userText, imageBase64) else userText
        @Volatile var cancelledFlag = false
        @Volatile var provHandle: Cancellable? = null
        @Volatile var toolHandle: Cancellable? = null
        @Volatile var roundTimer: ScheduledFuture<*>? = null
        @Volatile var turnTimer: ScheduledFuture<*>? = null

        // Agent thread only:
        val messages = ArrayList<ChatMessage>()
        val results = HashMap<String, ToolResult>()
        val usedIds = HashSet<String>()
        val roundCalls = ArrayList<ToolCallRequest>()
        val roundText = StringBuilder()
        val full = StringBuilder()
        val chunker = SentenceChunker()
        var rounds = 0
        var roundId = 0
        var toolCalls = 0
        var roundOpen = false
        var ended = false
        var lastToolMessage: String? = null

        override val isCancelled: Boolean get() = cancelledFlag
        override fun cancel() {
            cancelledFlag = true
            provHandle?.cancel(); toolHandle?.cancel()
            roundTimer?.cancel(false); turnTimer?.cancel(false)
            VisionAttachment.release(userContent)
        }
    }

    private fun begin(turn: Turn) {
        if (turn.cancelledFlag) return
        turn.messages += ChatMessage(ChatRole.USER, turn.userContent)
        startRound(turn)
    }

    private fun startRound(turn: Turn) {
        if (turn.cancelledFlag || turn.ended) return
        val catalog = tools
        turn.rounds++
        val offerTools = catalog != null && turn.rounds < MAX_ROUNDS && turn.toolCalls < MAX_TOOL_CALLS
        if (turn.full.isNotEmpty() && !turn.full.last().isWhitespace()) turn.full.append(' ')
        val request = ChatRequest(
            systemPrompt = systemPrompt,
            messages = trimHistory(history + turn.messages),
            tools = if (offerTools) catalog!!.specs else emptyList()
        )
        val rid = ++turn.roundId
        turn.roundOpen = true
        turn.roundCalls.clear()
        turn.roundText.setLength(0)
        turn.chunker.reset()
        turn.roundTimer = scheduleSafe(roundTimeoutMs) { onRoundTimeout(turn, rid) }
        val handle = try {
            provider!!.stream(request, StreamListener { ev -> runAgent { onEvent(turn, rid, ev) } })
        } catch (e: RuntimeException) {
            Log.w(TAG, "Provider failed to start")
            endFailure(turn, FailureKind.PROVIDER)
            return
        }
        turn.provHandle = handle
        if (turn.cancelledFlag) handle.cancel()
    }

    private fun onEvent(turn: Turn, rid: Int, ev: StreamEvent) {
        if (turn.cancelledFlag || turn.ended || !turn.roundOpen || rid != turn.roundId) return   // stale
        when (ev) {
            is StreamEvent.TextDelta -> {
                turn.roundText.append(ev.text)
                turn.full.append(ev.text)
                for (c in turn.chunker.push(ev.text)) emitSentence(turn, c)
            }
            is StreamEvent.ToolCallRequest -> {
                var id = ev.call.id
                if (id.isBlank() || !turn.usedIds.add(id)) {
                    id = "call_${turn.toolCalls + turn.roundCalls.size + 1}_${rid}"
                    turn.usedIds.add(id)
                }
                turn.roundCalls += ev.call.copy(id = id)
            }
            is StreamEvent.Finished -> onRoundFinished(turn)
            is StreamEvent.Error -> endFailure(turn, when (ev.kind) {
                ErrorKind.TIMEOUT -> FailureKind.TIMEOUT
                ErrorKind.NETWORK -> FailureKind.NETWORK
                ErrorKind.UNAVAILABLE -> FailureKind.UNAVAILABLE
                else -> FailureKind.PROVIDER
            })
        }
    }

    private fun onRoundFinished(turn: Turn) {
        turn.roundOpen = false
        turn.roundTimer?.cancel(false)
        turn.provHandle = null
        for (c in turn.chunker.flush()) emitSentence(turn, c)
        val text = turn.roundText.toString()
        val catalog = tools
        if (turn.roundCalls.isEmpty()) {
            if (turn.full.isBlank()) endFailure(turn, FailureKind.EMPTY) else endSuccess(turn, text)
            return
        }
        val remaining = MAX_TOOL_CALLS - turn.toolCalls
        if (catalog == null || remaining <= 0) {
            // The model asked for tools although none are allowed any more: keep what it said, run nothing.
            if (turn.full.isBlank()) endFailure(turn, FailureKind.LIMIT) else endSuccess(turn, text)
            return
        }
        val calls = turn.roundCalls.take(remaining)       // never a tool_call without its result in the history
        turn.toolCalls += calls.size
        turn.messages += ChatMessage(ChatRole.ASSISTANT, text, calls)
        runTools(turn, catalog, calls, 0)
    }

    private fun runTools(turn: Turn, catalog: JarvisToolCatalog, calls: List<ToolCallRequest>, index: Int) {
        if (turn.cancelledFlag || turn.ended) return
        if (index >= calls.size) { startRound(turn); return }
        val call = calls[index]
        when (val prepared = catalog.prepare(call.name, call.argumentsJson)) {
            is PreparedTool.Rejected -> {
                record(turn, call, prepared.result)
                runTools(turn, catalog, calls, index + 1)
            }
            is PreparedTool.Ready -> {
                val previous = turn.results[prepared.key]
                if (previous != null) {          // identical call in this turn: not executed again
                    record(turn, call, previous.copy(message = "Duplicate call, not executed again. Earlier result: ${previous.message}"))
                    runTools(turn, catalog, calls, index + 1)
                    return
                }
                turn.toolHandle = catalog.execute(prepared, agent) { result ->
                    if (turn.cancelledFlag || turn.ended) return@execute
                    turn.toolHandle = null
                    turn.results[prepared.key] = result
                    record(turn, call, result)
                    runTools(turn, catalog, calls, index + 1)
                }
            }
        }
    }

    private fun record(turn: Turn, call: ToolCallRequest, result: ToolResult) {
        turn.messages += ChatMessage(ChatRole.TOOL, result.toJson(), toolCallId = call.id)
        // Raw web snippets are model input, never something to speak if a later round fails; a web ERROR is spoken.
        if (!(call.name == "web_answer" && result.ok)) turn.lastToolMessage = result.message
    }

    // ---- end of a turn -------------------------------------------------------------------------

    private fun endSuccess(turn: Turn, finalText: String) {
        if (turn.ended) return
        turn.ended = true
        stopTimers(turn)
        turn.messages += ChatMessage(ChatRole.ASSISTANT, finalText)
        commit(turn.messages)
        val full = turn.full.toString().trim()
        postMain(turn) { turn.listener.onFinished(full) }
    }

    private fun endFailure(turn: Turn, kind: FailureKind) {
        if (turn.ended) return
        turn.ended = true
        stopTimers(turn)
        turn.provHandle?.cancel(); turn.toolHandle?.cancel()
        val toolMessage = turn.lastToolMessage
        if (toolMessage != null) {                                  // a tool already acted: remember that
            var kept = consistentPrefix(turn.messages)
            if (kept.lastOrNull()?.role == ChatRole.TOOL) kept = kept + ChatMessage(ChatRole.ASSISTANT, toolMessage)
            if (kept.any { it.role == ChatRole.ASSISTANT }) commit(kept)
        }
        postMain(turn) { turn.listener.onFailed(Failure(kind, toolMessage)) }
    }

    /** From any thread (timers / unavailable): hops to the agent thread when it is running. */
    private fun fail(turn: Turn, kind: FailureKind) {
        if (!runAgent { endFailure(turn, kind) }) postMain(turn) { turn.listener.onFailed(Failure(kind, null)) }
    }

    private fun onRoundTimeout(turn: Turn, rid: Int) {
        if (turn.ended || !turn.roundOpen || rid != turn.roundId) return
        turn.provHandle?.cancel()
        endFailure(turn, FailureKind.TIMEOUT)
    }

    private fun stopTimers(turn: Turn) {
        VisionAttachment.release(turn.userContent)
        turn.roundTimer?.cancel(false); turn.turnTimer?.cancel(false)
        turn.chunker.reset()
    }

    private fun emitSentence(turn: Turn, text: String) = postMain(turn) { turn.listener.onSentence(text) }

    private fun postMain(turn: Turn, block: () -> Unit) {
        try {
            mainPost(Runnable { if (!turn.cancelledFlag) block() })
        } catch (e: RuntimeException) { /* looper gone */ }
    }

    // ---- history -------------------------------------------------------------------------------

    private fun commit(turnMessages: List<ChatMessage>) {
        // Vision: the (large) image is only needed during its own turn; it is never kept in the session history.
        history.addAll(turnMessages)
        val trimmed = trimHistory(history)
        if (trimmed.size != history.size) {
            val copy = ArrayList(trimmed)
            history.clear(); history.addAll(copy)
        }
    }

    // ---- helpers -------------------------------------------------------------------------------

    private fun runAgent(block: () -> Unit): Boolean = try {
        agent.execute { try { block() } catch (t: Throwable) { Log.e(TAG, "Agent step failed", t) } }
        true
    } catch (e: RejectedExecutionException) { false }

    private fun scheduleSafe(ms: Long, block: () -> Unit): ScheduledFuture<*>? = try {
        agent.schedule({ try { block() } catch (t: Throwable) { Log.e(TAG, "Timer step failed", t) } }, ms, TimeUnit.MILLISECONDS)
    } catch (e: RejectedExecutionException) { null }

    companion object {
        private const val TAG = "OnlineBrain"
        const val MAX_ROUNDS = 4
        const val MAX_TOOL_CALLS = 4
        const val HISTORY_MAX = 12
        const val ROUND_TIMEOUT_MS = 35_000L
        const val TURN_TIMEOUT_MS = 75_000L

        /**
         * At most [max] messages, cut only so the first kept message is a USER message: an assistant tool_call
         * and its tool_result(s) are never separated.
         */
        internal fun trimHistory(all: List<ChatMessage>, max: Int = HISTORY_MAX): List<ChatMessage> {
            if (all.size <= max) return all
            var start = all.size - max
            while (start < all.size && all[start].role != ChatRole.USER) start++
            if (start >= all.size) {
                val lastUser = all.indexOfLast { it.role == ChatRole.USER }
                return if (lastUser >= 0) all.subList(lastUser, all.size) else all.takeLast(max)
            }
            return all.subList(start, all.size)
        }

        /** Drops a trailing assistant message whose tool calls do not all have a tool result yet. */
        internal fun consistentPrefix(messages: List<ChatMessage>): List<ChatMessage> {
            val i = messages.indexOfLast { it.role == ChatRole.ASSISTANT && it.toolCalls.isNotEmpty() }
            if (i < 0) return messages
            val answered = messages.drop(i + 1).filter { it.role == ChatRole.TOOL }.mapNotNull { it.toolCallId }.toSet()
            return if (messages[i].toolCalls.all { it.id in answered }) messages else messages.subList(0, i)
        }

        const val DEFAULT_SYSTEM_PROMPT =
            "تو «جارویس» هستی، دستیار صوتی فارسی روی گوشی کاربر؛ کاربر را «ارباب» خطاب کن. " +
            "پاسخ‌ها کوتاه و محاوره‌ای باشند (۱ تا ۲ جمله) و فقط متن ساده، بدون مارک‌داون، فهرست و ایموجی، چون با صدا خوانده می‌شوند. " +
            "برای کارهای روی گوشی (آلارم، تایمر، تماس، باز کردن برنامه، چراغ‌قوه، صدا، تنظیمات، صفحه اصلی) فقط از ابزارها استفاده کن. " +
            "فقط اگر نتیجه‌ی ابزار ok:true بود بگو کار انجام شد؛ اگر ok:false بود دلیلش را کوتاه بگو و هرگز ادعای موفقیت نکن. " +
            "برای اطلاعات روز و آنلاین (قیمت طلا، سکه، دلار و ارز، رمزارز، خبر، آب‌وهوا، نتیجه‌ی ورزشی، رویدادهای جاری) حتماً ابزار web_answer را صدا بزن و فقط بر پایه‌ی نتیجه‌اش به فارسی کوتاه پاسخ بده؛ عدد را از خودت نساز و در صورت لزوم منبع یا زمان را بگو؛ اگر نتیجه کافی یا هم‌خوان نبود صادقانه بگو. " +
            "فقط وقتی کاربر صریحاً گفت «سرچ کن» یا «جستجو کن» از ابزار browser_search استفاده کن؛ این ابزار فقط مرورگر را باز می‌کند و پاسخی به تو نمی‌دهد، پس فقط اگر ok:true بود بگو جست‌وجو باز شد. " +
            "برای پخش آهنگ، باز کردن ویدیو یا یوتیوب از ابزار play_media استفاده کن و فقط اگر ok:true بود بگو درخواست انجام شد؛ هرگز نگو آهنگ قطعاً پخش شد مگر نتیجه‌ی ابزار همین را بگوید. " +
            "اگر web_answer خطا داد، صریح بگو اطلاعات آنلاین دریافت نشد و پاسخ حدسی نده. " +
            "اگر پارامتر لازم ابزار را نمی‌دانی از کاربر بپرس. چیزی را از خودت نساز؛ اگر نمی‌دانی، صادقانه بگو."
    }
}
