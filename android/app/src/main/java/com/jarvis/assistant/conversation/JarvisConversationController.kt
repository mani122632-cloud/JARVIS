package com.jarvis.assistant.conversation

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.jarvis.assistant.brain.BrainResult
import com.jarvis.assistant.brain.JarvisBrain
import com.jarvis.assistant.command.JarvisAction
import com.jarvis.assistant.command.JarvisActionExecutor
import com.jarvis.assistant.core.JarvisCoreView
import com.jarvis.assistant.core.JarvisState
import com.jarvis.assistant.nlu.EndConversationDetector
import com.jarvis.assistant.online.Cancellable
import com.jarvis.assistant.online.Failure
import com.jarvis.assistant.online.OnlineBrain
import com.jarvis.assistant.online.OnlineFallbackPhrases
import com.jarvis.assistant.speech.CommandSpeechError
import com.jarvis.assistant.speech.JarvisPhrases
import com.jarvis.assistant.speech.JarvisSpeechController
import com.jarvis.assistant.speech.RequestGate
import com.jarvis.assistant.speech.SpeechInput

/**
 * Multi-turn conversation SESSION, started after wake word + "بله ارباب.":
 *
 *   COMMAND_LISTENING -> COMMAND_PROCESSING (Brain) -> RESPONDING (TTS [+ action]) -> COMMAND_LISTENING -> ...
 *
 * The user keeps talking WITHOUT the wake word. Every utterance goes through the Brain, which asks the EXISTING
 * command system first (JarvisCommandProcessor -> JarvisActionExecutor) and only then the offline conversation
 * brain. After a command or a reply the session stays open and JARVIS listens again.
 *
 * Incomplete requests are completed across turns: "آلارم بذار" -> JARVIS asks "چه ساعتی ارباب؟" -> the next
 * utterance ("هفت صبح") is the answer (the open question lives in [ConversationContext.pending]; the Brain
 * decides, this controller only speaks and listens again).
 *
 * The session ends (-> [Callback.onConversationFinished], the service then hides the overlay and resumes Vosk) when
 *  - the Brain recognizes the END_CONVERSATION intent (خداحافظ / فعلاً / من دیگه میرم / دیگه کاری ندارم / کافیه /
 *    بعداً صحبت می‌کنیم ... by sentence structure, not by a fixed list; see EndConversationDetector),
 *  - nothing was said for [FIRST_TURN_TIMEOUT_MS] (first wait) or [FOLLOW_UP_TIMEOUT_MS] (after a reply),
 *  - [MAX_SESSION_MS] have passed,
 *  - the STT is unusable (model missing, no permission, repeated audio failure),
 *  - the command was "dismiss" or "back" (their only effect is closing the assistant).
 *
 * The microphone belongs to [commandSpeech] for the whole session; the caller keeps Vosk suspended until
 * [Callback.onConversationFinished]. Main thread only.
 */
class JarvisConversationController(
    private val tts: JarvisSpeechController,
    private val commandSpeech: SpeechInput,
    private val brain: JarvisBrain,
    private val executor: JarvisActionExecutor,
    private val core: () -> JarvisCoreView?,
    private val callback: Callback,
    /** Bounded in-RAM history of this session (cleared at start and end). */
    val conversationContext: ConversationContext = ConversationContext(),
    /** Online Brain Stage 1. Null (or unavailable) = a [BrainResult.Escalate] falls back to the offline answer. */
    private val onlineBrain: OnlineBrain? = null,
    /** Stage 5C: barge-in is only allowed inside a valid wake-word session (supplied by the service). */
    private val isBargeInAllowed: () -> Boolean = { true }
) {
    enum class State { IDLE, COMMAND_LISTENING, COMMAND_PROCESSING, RESPONDING }

    interface Callback {
        fun onStateChanged(state: State) {}
        fun onConversationFinished()
    }

    var state = State.IDLE
        private set

    private val main = Handler(Looper.getMainLooper())
    private var generation = 0

    /**
     * Goodbye fix: true from the first valid goodbye until the next begin(). While set, nothing (late STT result,
     * barge-in, TTS/online/executor callback, timeout) may process input or re-open the session.
     */
    private var ending = false

    /**
     * Identity of the current online turn, independent of [generation]. Bumped (= every callback of the previous
     * online turn becomes stale) by begin, cancel, finish, release (via cancel) and every new utterance.
     */
    private var onlineTurnId = 0
    private var onlineHandle: Cancellable? = null
    private var onlineTurn: OnlineTurn? = null

    /** One streamed online answer being spoken: chunks wait here and are spoken ONE AT A TIME. */
    private class OnlineTurn(val id: Int, val fallback: BrainResult) {
        val queue = ArrayDeque<String>()
        var speaking = false          // a chunk is being spoken: no second speak() until its onDone
        var streamEnded = false
        var failure: Failure? = null
        var spoke = false             // at least one chunk was handed to the TTS
        var fullText = ""
    }

    /** Stage 5B: identity of the current request; async results of older requests are dropped. */
    private var requestId = 0

    /** Stage 5C: stale-request guard; cancelled by barge-in / cancel / finish. */
    private val gate = RequestGate()
    private var gateToken = 0

    /** Stage 5C: the mic is open DURING a reply only to detect the user speaking (barge-in). */
    private var bargeArmed = false
    private var bargeHits = 0
    private var bargeGen = 0
    private val bargeArmRunnable = Runnable { armBargeNow() }
    private var lastUtterance = ""
    private var lastUtteranceAt = 0L
    /** True until the first utterance after the wake word has been received. */
    private var firstTurn = true
    /** Idle timeout of the current wait: longer right after the wake word, shorter for follow-ups. */
    private var turnTimeoutMs = FIRST_TURN_TIMEOUT_MS

    private fun isFiller(text: String): Boolean {
        val t = text.trim().trimEnd('.', '،', '!', '؟', '?')
        return t.isEmpty() || t in FILLERS
    }

    /** Uptime when the current turn started waiting for the user (silent re-listens do not reset it). */
    private var turnStartedAt = 0L

    /** Consecutive utterances without usable text; "متوجه نشدم." is said once, then the session gives up quietly. */
    private var emptyStreak = 0

    /** Consecutive transient STT failures (BUSY / AUDIO / OTHER). */
    private var failedAttempts = 0

    /** Recognizer alternatives of the current final result; the Brain picks the one it can handle. */
    private var alternatives: List<String> = emptyList()

    /** Backup for a recognizer that never answers; the normal silence check happens on NO_SPEECH. */
    private val silenceWatchdog = Runnable { onSilenceWatchdog() }

    /** Uptime of begin(); bounds how long a still-loading recognizer may extend the silence watchdog. */
    private var sessionStartedAt = 0L

    private fun onSilenceWatchdog() {
        if (state != State.COMMAND_LISTENING) return
        // The STT model is still loading (first use after start-up): that is not the user's silence.
        if (commandSpeech.isPreparing && SystemClock.uptimeMillis() - sessionStartedAt < MAX_PREPARE_WAIT_MS) {
            main.postDelayed(silenceWatchdog, PREPARE_RECHECK_MS)
            return
        }
        Log.i(TAG, "Silence watchdog: ending session")
        finish()
    }

    private val maxSession = Runnable {
        Log.i(TAG, "Maximum session length reached")
        finish()
    }

    init {
        commandSpeech.setListener(object : SpeechInput.Listener {
            override fun onListeningStarted() { core()?.setState(JarvisState.LISTENING) }
            override fun onFinalAlternatives(texts: List<String>) { alternatives = texts }
            override fun onFinalResult(text: String) = guarded {
                if (ending || state == State.IDLE) { alternatives = emptyList(); return@guarded }   // closed session: nothing may come back to life
                // Goodbye wins in EVERY state (listening, processing, speaking/barge-in): one valid "خداحافظ" ends it all.
                if (isGoodbye(text)) { endNow("final result"); return@guarded }
                if (state == State.RESPONDING) { alternatives = emptyList(); onBargeSessionEnded() }   // no barge-in was triggered: not a command
                else onUtterance(text)
            }
            override fun onError(error: CommandSpeechError) = guarded { onSpeechError(error) }
            override fun onVoiceLevel(level: Float) {
                when (state) {
                    State.COMMAND_LISTENING -> core()?.setVoiceAmplitude(level)
                    State.RESPONDING -> guarded { onBargeLevel(level) }
                    else -> Unit
                }
            }
        })
    }

    /** Call when "بله ارباب." has finished. */
    fun begin() {
        if (state != State.IDLE) {
            // A stale session must never block a new one: drop it silently and start clean.
            Log.w(TAG, "begin() while $state: resetting the previous session")
            cancel()
        }
        ending = false
        conversationContext.clear()
        invalidateOnline(resetHistory = true)
        emptyStreak = 0
        failedAttempts = 0
        alternatives = emptyList()
        firstTurn = true
        bargeArmed = false
        bargeHits = 0
        lastUtterance = ""
        lastUtteranceAt = 0L
        turnTimeoutMs = FIRST_TURN_TIMEOUT_MS
        main.removeCallbacks(maxSession)
        main.postDelayed(maxSession, MAX_SESSION_MS)
        sessionStartedAt = SystemClock.uptimeMillis()
        listen(LISTEN_DELAY_MS, newTurn = true)
    }

    /** Aborts everything silently (no finished callback). */
    fun cancel() {
        generation++
        requestId++
        gate.cancel()
        bargeArmed = false
        bargeHits = 0
        alternatives = emptyList()
        invalidateOnline(resetHistory = true)
        main.removeCallbacksAndMessages(null)
        try { commandSpeech.stopListening() } catch (t: Throwable) { Log.w(TAG, "stopListening failed", t) }
        try { tts.stop() } catch (t: Throwable) { Log.w(TAG, "tts.stop failed", t) }
        core()?.setVoiceAmplitude(0f)
        conversationContext.clear()
        setState(State.IDLE)
    }

    /**
     * Runs a state-machine step. Anything thrown inside must not crash the app or leave the session dead:
     * the session keeps going (listens again) a few times, then ends cleanly and returns to the wake word.
     */
    private fun guarded(block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            Log.e(TAG, "Conversation step failed", t)
            recover()
        }
    }

    private fun recover() {
        try {
            if (state == State.IDLE) return
            failedAttempts++
            if (failedAttempts >= MAX_FAILED_ATTEMPTS) finish() else listen(retryDelay(), newTurn = false)
        } catch (t: Throwable) {
            Log.e(TAG, "Recovery failed", t)
            try { finish() } catch (t2: Throwable) { Log.e(TAG, "finish() failed", t2) }
        }
    }

    private fun retryDelay(): Long = RETRY_DELAY_MS * (failedAttempts + 1).coerceAtMost(4)

    // ---- listening ------------------------------------------------------------------------------

    private fun listen(delayMs: Long, newTurn: Boolean) {
        val gen = ++generation
        setState(State.COMMAND_LISTENING)
        core()?.setState(JarvisState.LISTENING)
        if (newTurn) {
            turnStartedAt = SystemClock.uptimeMillis()
            turnTimeoutMs = if (firstTurn) FIRST_TURN_TIMEOUT_MS else FOLLOW_UP_TIMEOUT_MS
            main.removeCallbacks(silenceWatchdog)
            main.postDelayed(silenceWatchdog, turnTimeoutMs + WATCHDOG_SLACK_MS)
        }
        // Short pause so the recognizer doesn't hear the tail of our own voice.
        main.postDelayed({
            if (gen == generation && state == State.COMMAND_LISTENING) guarded { commandSpeech.startListening() }
        }, delayMs)
    }

    /** Nothing usable was heard: listen again silently, unless this turn has been silent for too long. */
    private fun relistenOrTimeout() {
        if (SystemClock.uptimeMillis() - turnStartedAt >= turnTimeoutMs) {
            Log.i(TAG, "Silence timeout: ending session")
            finish()
        } else {
            listen(RETRY_DELAY_MS, newTurn = false)
        }
    }

    private fun onSpeechError(error: CommandSpeechError) {
        if (state == State.RESPONDING) {                   // the barge-in listener ended (nothing heard / error)
            val retry = error == CommandSpeechError.NO_SPEECH || error == CommandSpeechError.NO_MATCH
            alternatives = emptyList()
            onBargeSessionEnded(retry)
            return
        }
        if (state != State.COMMAND_LISTENING) return
        generation++
        when (error) {
            // Nothing said: stay quiet and keep listening until the silence timeout.
            CommandSpeechError.NO_SPEECH -> relistenOrTimeout()
            CommandSpeechError.NO_MATCH -> handleUnusable()
            CommandSpeechError.MODEL_MISSING -> speak("مدل تشخیص گفتار فارسی نصب نشده است.") { finish() }
            CommandSpeechError.NO_PERMISSION -> speak("اجازه میکروفون لازم است.") { finish() }
            // The recognizer could not load (the STT reloads itself on every attempt): retry with growing pauses and
            // only give up, with the spoken message, when several attempts in a row failed.
            CommandSpeechError.NOT_AVAILABLE -> {
                failedAttempts++
                Log.w(TAG, "STT not available (attempt $failedAttempts/$MAX_FAILED_ATTEMPTS)")
                if (failedAttempts >= MAX_FAILED_ATTEMPTS) {
                    speak("تشخیص گفتار روی این گوشی در دسترس نیست.") { finish() }
                } else {
                    listen(retryDelay() + NOT_AVAILABLE_EXTRA_DELAY_MS, newTurn = false)
                }
            }
            CommandSpeechError.NETWORK -> speak("برای تشخیص گفتار به اینترنت نیاز دارم.") { finish() }
            // Transient (microphone busy, audio glitch, unexpected error): controlled retries with growing pauses.
            CommandSpeechError.BUSY, CommandSpeechError.AUDIO, CommandSpeechError.OTHER -> {
                failedAttempts++
                Log.w(TAG, "STT error $error (attempt $failedAttempts/$MAX_FAILED_ATTEMPTS)")
                if (failedAttempts >= MAX_FAILED_ATTEMPTS) {
                    speak("مشکلی در شنیدن صدا پیش آمد.") { finish() }
                } else {
                    listen(retryDelay(), newTurn = false)
                }
            }
        }
    }

    /** Speech without usable text: retry silently; say "متوجه نشدم." only once in a row; give up after a few. */
    private fun handleUnusable() {
        emptyStreak++
        when {
            emptyStreak >= MAX_EMPTY_STREAK -> finish()
            emptyStreak % 2 == 1 -> speak(JarvisPhrases.NOT_UNDERSTOOD) { listen(LISTEN_DELAY_MS, newTurn = false) }
            else -> relistenOrTimeout()
        }
    }

    // ---- one utterance --------------------------------------------------------------------------

    private fun onUtterance(text: String) {
        if (ending || state != State.COMMAND_LISTENING) return       // late duplicate / session already ending
        // Defensive: a goodbye must never reach the duplicate filter, the Brain or the command pipeline.
        if (isGoodbye(text)) { endNow("utterance"); return }
        val now = SystemClock.uptimeMillis()
        val norm = text.trim()
        if (norm.isNotEmpty() && norm == lastUtterance && now - lastUtteranceAt < DUPLICATE_WINDOW_MS) {
            Log.i(TAG, "Duplicate utterance ignored")
            listen(RETRY_DELAY_MS, newTurn = false)
            return
        }
        // In a follow-up (no wake word) a lone filler sound is noise, not a request.
        if (!firstTurn && isFiller(norm)) {
            alternatives = emptyList()
            relistenOrTimeout()
            return
        }
        lastUtterance = norm
        lastUtteranceAt = now
        requestId++
        gateToken = gate.begin()
        firstTurn = false
        generation++
        invalidateOnline(resetHistory = false)             // a new utterance makes any older online turn stale
        main.removeCallbacks(silenceWatchdog)
        Log.i(TAG, "Utterance received (${text.length} chars)")   // text itself is never logged
        if (text.isBlank()) { alternatives = emptyList(); handleUnusable(); return }

        emptyStreak = 0
        failedAttempts = 0
        setState(State.COMMAND_PROCESSING)
        core()?.setVoiceAmplitude(0f)
        core()?.setState(JarvisState.THINKING)

        val candidates = (listOf(text) + alternatives).distinct()
        alternatives = emptyList()
        val best = try { brain.pickBest(candidates).ifBlank { text } } catch (e: Exception) { text }
        conversationContext.addUserUtterance(best)

        // The Brain decides everything, including the end of the conversation (an intent, not a string compare):
        // pending answer -> memory -> device command -> END_CONVERSATION -> clarification -> small talk.
        val result = try {
            brain.think(best, conversationContext)
        } catch (e: Exception) {
            Log.e(TAG, "Brain threw", e)
            BrainResult.Conversation(TOOL_FAILED)
        }
        Log.i(TAG, "Brain decided: ${result.kind}" +
            ((result as? BrainResult.Command)?.let { " ${it.action::class.simpleName} conf=${it.confidence}" } ?: ""))
        handleResult(result)
    }

    private fun handleResult(result: BrainResult) {
        when (result) {
            is BrainResult.Conversation -> {
                conversationContext.addResponse(result.responseText)
                speak(result.responseText) { listen(LISTEN_DELAY_MS, newTurn = true) }
            }
            // A question ("چه ساعتی ارباب؟"): the Brain has stored the partial request; the next utterance answers it.
            is BrainResult.Clarify -> {
                conversationContext.addResponse(result.responseText)
                speak(result.responseText) { listen(LISTEN_DELAY_MS, newTurn = true) }
            }
            is BrainResult.EndConversation -> {
                Log.i(TAG, "END_CONVERSATION intent")
                endNow("brain intent")                      // no lingering reply: the session closes right now
            }
            is BrainResult.Unknown -> handleUnusable()
            is BrainResult.Command -> runCommand(result)
            is BrainResult.Multi -> runMulti(result)
            is BrainResult.Escalate -> startOnline(result)
        }
    }

    // ---- online brain (Stage 1) -----------------------------------------------------------------

    /** Makes every callback of the running online turn stale and stops its request. Speech is stopped by the caller. */
    private fun invalidateOnline(resetHistory: Boolean) {
        onlineTurnId++
        main.removeCallbacks(onlineWatchdog)
        val h = onlineHandle
        onlineHandle = null
        onlineTurn = null
        if (h != null) try { h.cancel() } catch (t: Throwable) { Log.w(TAG, "online cancel failed", t) }
        if (resetHistory) try { onlineBrain?.resetSession() } catch (t: Throwable) { Log.w(TAG, "online reset failed", t) }
    }

    private val onlineWatchdog = Runnable {
        val turn = onlineTurn ?: return@Runnable
        Log.w(TAG, "Online turn watchdog fired")
        guarded { onlineFailed(turn, null) }
    }

    private fun startOnline(result: BrainResult.Escalate) {
        val fallback = result.offlineFallback.takeUnless { it is BrainResult.Escalate } ?: BrainResult.Unknown()
        val online = onlineBrain
        if (online == null || !online.isAvailable()) { handleResult(fallback); return }   // Online off: today's behaviour
        invalidateOnline(resetHistory = false)
        val id = onlineTurnId
        val turn = OnlineTurn(id, fallback)
        onlineTurn = turn
        main.postDelayed(onlineWatchdog, ONLINE_MAX_MS)
        Log.i(TAG, "Escalating to the online brain (turn $id)")
        onlineHandle = online.ask(result.text, object : OnlineBrain.Listener {
            override fun onSentence(text: String) { if (id == onlineTurnId) guarded { onlineSentence(turn, text) } }
            override fun onFinished(fullText: String) { if (id == onlineTurnId) guarded { onlineFinished(turn, fullText) } }
            override fun onFailed(failure: Failure) { if (id == onlineTurnId) guarded { onlineFailed(turn, failure) } }
        })
    }

    private fun onlineSentence(turn: OnlineTurn, text: String) {
        if (turn.id != onlineTurnId || state == State.IDLE) return
        turn.queue.addLast(text)
        if (!turn.speaking) playNextOnline(turn)
    }

    private fun onlineFinished(turn: OnlineTurn, fullText: String) {
        if (turn.id != onlineTurnId || state == State.IDLE) return
        turn.streamEnded = true
        turn.fullText = fullText
        if (!turn.speaking && turn.queue.isEmpty()) completeOnline(turn)
    }

    private fun onlineFailed(turn: OnlineTurn, failure: Failure?) {
        if (turn.id != onlineTurnId || state == State.IDLE) return
        turn.streamEnded = true
        turn.failure = failure ?: Failure(com.jarvis.assistant.online.FailureKind.TIMEOUT, null)
        // Stop the request, but keep the turn: chunks already queued are still spoken before the fallback.
        try { onlineHandle?.cancel() } catch (t: Throwable) { Log.w(TAG, "online cancel failed", t) }
        onlineHandle = null
        if (!turn.speaking && turn.queue.isEmpty()) completeOnline(turn)
    }

    /** Speaks the next queued chunk; called only when no chunk is being spoken (never two speak() at once). */
    private fun playNextOnline(turn: OnlineTurn) {
        if (turn.id != onlineTurnId || state == State.IDLE) return
        val next = turn.queue.removeFirstOrNull()
        if (next == null) {
            turn.speaking = false
            if (turn.streamEnded) completeOnline(turn) else setState(State.COMMAND_PROCESSING)
            return
        }
        turn.speaking = true
        turn.spoke = true
        speak(next) {                                   // `then` runs after this chunk's onDone (or its timeout)
            if (turn.id != onlineTurnId) return@speak
            turn.speaking = false
            playNextOnline(turn)
        }
    }

    /** Stream ended and the queue is empty: continue the session, or fall back if nothing could be said. */
    private fun completeOnline(turn: OnlineTurn) {
        if (turn.id != onlineTurnId) return
        main.removeCallbacks(onlineWatchdog)
        onlineHandle = null
        onlineTurn = null
        val failure = turn.failure
        val toolMessage: String? = failure?.lastToolMessage
        when {
            failure == null && turn.spoke -> {
                conversationContext.addResponse(turn.fullText)
                listen(LISTEN_DELAY_MS, newTurn = true)
            }
            // Failed after some speech: do not repeat or contradict it, just keep the session going.
            turn.spoke -> listen(LISTEN_DELAY_MS, newTurn = true)
            // A tool already ran: say its real result instead of pretending nothing happened.
            toolMessage != null -> {
                conversationContext.addResponse(toolMessage)
                speak(toolMessage) { listen(LISTEN_DELAY_MS, newTurn = true) }
            }
            // Every online provider failed AND the offline Brain has nothing usable: say so instead of "متوجه نشدم".
            failure != null && turn.fallback is BrainResult.Unknown -> {
                conversationContext.addResponse(OnlineFallbackPhrases.NO_AI)
                speak(OnlineFallbackPhrases.NO_AI) { listen(LISTEN_DELAY_MS, newTurn = true) }
            }
            // Nothing was said and nothing ran: exactly what the offline Brain would have done.
            else -> handleResult(turn.fallback)
        }
    }

    // ---- multi-command (Stage 5A) ----------------------------------------------------------------

    /** Runs the commands one after another; the first real failure stops the rest (nothing is faked). */
    private fun runMulti(result: BrainResult.Multi) {
        val start = { runMultiStep(result, 0, ArrayList(), false) }
        if (result.responseText.isBlank()) start() else speak(result.responseText) { start() }
    }

    private fun runMultiStep(result: BrainResult.Multi, index: Int, said: MutableList<String>, media: Boolean) {
        if (state == State.IDLE) return
        if (index >= result.commands.size) { finishMulti(result, said, media); return }
        val action = result.commands[index]
        val next = { outcome: JarvisActionExecutor.Outcome ->
            (outcome.message ?: if (!outcome.success) TOOL_FAILED else null)?.let { said.add(it) }
            if (outcome.success) {
                if (action is JarvisAction.CreateAlarm || action is JarvisAction.CreateTimer) conversationContext.lastAction = action
                runMultiStep(result, index + 1, said, media || (outcome.success && isMediaAction(action)))
            } else {
                // Stop here: the remaining commands did not run, and JARVIS says so.
                if (index + 1 < result.commands.size) said.add(MULTI_STOPPED)
                finishMulti(result.copy(trailing = null, notice = null), said, media)
            }
        }
        if (executor.runsAsync(action)) {
            val gen = ++generation
            val req = requestId
            val tok = gateToken
            try {
                executor.executeAsync(action) { o -> if (gen == generation && req == requestId && gate.isCurrent(tok) && state != State.IDLE) guarded { next(o) } }
            } catch (e: RuntimeException) {
                Log.e(TAG, "Executor threw", e)
                next(JarvisActionExecutor.Outcome(false, null))
            }
        } else {
            val o = try { executor.execute(action) } catch (e: RuntimeException) {
                Log.e(TAG, "Executor threw", e)
                JarvisActionExecutor.Outcome(false, null)
            }
            next(o)
        }
    }

    private fun finishMulti(result: BrainResult.Multi, said: List<String>, media: Boolean) {
        val parts = said.toMutableList()
        result.notice?.let { parts.add(it) }
        result.trailing?.let { parts.add(it.responseText) }
        val text = parts.joinToString(" ")
        if (text.isBlank()) { if (media) finish() else listen(AFTER_COMMAND_DELAY_MS, newTurn = true); return }
        conversationContext.addResponse(text)
        speak(text) { if (media && result.trailing == null) finish() else listen(LISTEN_DELAY_MS, newTurn = true) }
    }

    private fun runCommand(result: BrainResult.Command) {
        val action = result.action
        if (action is JarvisAction.Unknown) { handleUnusable(); return }
        // Alarm / timer speak their own result afterwards, so there is no "حتماً." in front of them.
        if (result.responseText.isBlank()) executeAndContinue(action)
        else speak(result.responseText) { executeAndContinue(action) }         // "حتماً."
    }

    private fun executeAndContinue(action: JarvisAction) {
        if (action == JarvisAction.DismissAssistant) { finish(); return }
        // call_contact: the contact lookup runs in the background (executeAsync); the outcome arrives on the main
        // thread and goes through the same spoken-reply path below. Runs exactly once per command.
        if (executor.runsAsync(action)) {
            val gen = ++generation
            val req = requestId
            val tok = gateToken
            try {
                executor.executeAsync(action) { outcome ->
                    if (gen != generation || req != requestId || !gate.isCurrent(tok) || state == State.IDLE) return@executeAsync   // session cancelled / finished meanwhile
                    guarded { handleOutcome(action, outcome) }
                }
            } catch (e: RuntimeException) {
                Log.e(TAG, "Executor threw", e)
                handleOutcome(action, JarvisActionExecutor.Outcome(false, null))
            }
            return
        }
        val outcome = try {
            executor.execute(action)
        } catch (e: RuntimeException) {                // executor promises not to throw; stay safe
            Log.e(TAG, "Executor threw", e)
            JarvisActionExecutor.Outcome(false, null)
        }
        handleOutcome(action, outcome)
    }

    /** Speaks the executor's result (or just listens again); shared by the sync and the async (call_contact) paths. */
    private fun handleOutcome(action: JarvisAction, outcome: JarvisActionExecutor.Outcome) {
        val message = outcome.message ?: if (!outcome.success) TOOL_FAILED else null
        if (outcome.success && (action is JarvisAction.CreateAlarm || action is JarvisAction.CreateTimer)) {
            conversationContext.lastAction = action
        }
        // The tool asked a question (which contact? which alarm?): the next answer, without the wake word, continues it.
        if (!outcome.success && message != null && message.trim().endsWith("؟")) {
            conversationContext.openQuestionAction = action
            conversationContext.openQuestionText = conversationContext.recentUserUtterances.lastOrNull()
        }
        when {
            // Back's only real effect is closing the assistant, so the session ends with it.
            action == JarvisAction.GoBack -> finish()
            // Media really started (song / movie / YouTube page): say it, then END the session. The microphone must not
            // keep listening to the music, and the player / video screen keeps running without the assistant.
            message != null && outcome.success && isMediaAction(action) -> {
                conversationContext.addResponse(message)
                speak(message) { finish() }
            }
            // The executor's sentence: the reason for a failure, or the confirmation of a real alarm / timer.
            message != null -> {
                conversationContext.addResponse(message)
                speak(message) { listen(LISTEN_DELAY_MS, newTurn = true) }
            }
            // The session stays open: JARVIS listens again so the user can keep talking.
            else -> listen(AFTER_COMMAND_DELAY_MS, newTurn = true)
        }
    }

    private fun isMediaAction(action: JarvisAction): Boolean =
        action is JarvisAction.ToolCall && action.tool == com.jarvis.assistant.command.MediaTool.NAME

    // ---- speaking -------------------------------------------------------------------------------

    /** Speaks [text]; [then] runs when done or failed, or if TTS never starts / hangs. Never blocks long. */
    private fun speak(text: String, then: () -> Unit) {
        val gen = ++generation
        setState(State.RESPONDING)
        var done = false
        val proceed = Runnable {
            if (done || gen != generation) return@Runnable
            done = true
            main.removeCallbacksAndMessages(SPEAK_TOKEN)
            guarded { then() }
        }
        val startTimeout = Runnable {
            Log.w(TAG, "TTS did not start; continuing without speech")
            tts.stop()
            proceed.run()
        }
        val maxTimeout = Runnable {
            if (done || gen != generation) return@Runnable
            Log.w(TAG, "TTS took too long; cutting it and continuing")
            try { tts.stop() } catch (t: Throwable) { Log.w(TAG, "tts.stop failed", t) }
            proceed.run()
        }
        val maxSpeak = (SPEAK_BASE_MS + text.length * SPEAK_PER_CHAR_MS).coerceAtMost(SPEAK_MAX_MS)
        val now = SystemClock.uptimeMillis()
        main.postAtTime(startTimeout, SPEAK_TOKEN, now + SPEAK_START_TIMEOUT_MS)
        main.postAtTime(maxTimeout, SPEAK_TOKEN, now + maxSpeak)
        try {
            tts.speak(text, object : JarvisSpeechController.Callback {
                override fun onStart() {
                    main.removeCallbacks(startTimeout)
                    if (gen == generation) {
                        core()?.setState(JarvisState.SPEAKING)
                        armBargeIn(gen)
                    }
                }
                override fun onDone(success: Boolean) { main.post(proceed) }
            })
        } catch (t: Throwable) {
            Log.e(TAG, "tts.speak threw", t)
            main.post(proceed)
        }
    }

    // ---- barge-in (Stage 5C) ------------------------------------------------------------------

    /** Opens the recognizer shortly after the reply's audio started, to hear the user talk over JARVIS. */
    private fun armBargeIn(gen: Int) {
        if (ending || !isBargeInAllowed()) return
        bargeGen = gen
        main.removeCallbacks(bargeArmRunnable)
        main.postDelayed(bargeArmRunnable, BARGE_ARM_DELAY_MS)
    }

    private fun armBargeNow() {
        if (ending || state != State.RESPONDING || bargeGen != generation || !isBargeInAllowed()) return
        bargeHits = 0
        if (commandSpeech.isListening) { bargeArmed = true; return }
        if (commandSpeech.isPreparing) return
        bargeArmed = true
        guarded { commandSpeech.startListening() }
    }

    /** The barge-in session ended without a trigger: re-open it while the same reply is still playing. */
    private fun onBargeSessionEnded(retry: Boolean = true) {
        if (!bargeArmed) return
        bargeArmed = false
        bargeHits = 0
        if (retry && state == State.RESPONDING) {
            main.removeCallbacks(bargeArmRunnable)
            main.postDelayed(bargeArmRunnable, BARGE_REARM_DELAY_MS)
        }
    }

    private fun onBargeLevel(level: Float) {
        if (!bargeArmed || state != State.RESPONDING) return
        if (level >= BARGE_LEVEL) { if (++bargeHits >= BARGE_HITS) bargeIn() } else bargeHits = 0
    }

    private fun disarmBargeIn() {
        main.removeCallbacks(bargeArmRunnable)
        if (!bargeArmed) return
        bargeArmed = false
        bargeHits = 0
        try { if (commandSpeech.isListening) commandSpeech.stopListening() } catch (t: Throwable) { Log.w(TAG, "stopListening failed", t) }
    }

    /**
     * The user started talking while JARVIS speaks: cut the voice, drop the old reply/task/online turn, and treat the
     * (already open) recognizer session as a normal command. Wake-word security: only inside an authorized session.
     */
    private fun bargeIn() {
        if (ending || state != State.RESPONDING || !bargeArmed || !isBargeInAllowed()) return
        Log.i(TAG, "Barge-in: user interrupted JARVIS")
        bargeArmed = false                                  // keep the recognizer open: it is now the command listener
        bargeHits = 0
        main.removeCallbacks(bargeArmRunnable)
        generation++                                        // old speak callbacks / timeouts / `then` become stale
        main.removeCallbacksAndMessages(SPEAK_TOKEN)
        requestId++
        gate.cancel()                                       // old async tool results are dropped
        invalidateOnline(resetHistory = false)              // stops the LLM request, drops queued chunks
        try { tts.interrupt() } catch (t: Throwable) { Log.w(TAG, "tts.interrupt failed", t) }
        core()?.setVoiceAmplitude(0f)
        firstTurn = false
        turnStartedAt = SystemClock.uptimeMillis()
        turnTimeoutMs = FOLLOW_UP_TIMEOUT_MS
        main.removeCallbacks(silenceWatchdog)
        main.postDelayed(silenceWatchdog, turnTimeoutMs + WATCHDOG_SLACK_MS)
        setState(State.COMMAND_LISTENING)                   // LISTENING; the final text goes through onUtterance()
    }

    /** True when [text] (or any recognizer alternative of the same result) is an unmistakable farewell. */
    private fun isGoodbye(text: String): Boolean {
        if (EndConversationDetector.isStrongFarewell(text)) return true
        return alternatives.any { EndConversationDetector.isStrongFarewell(it) }
    }

    /** First valid goodbye: hard-stop listening, TTS, request, gate, online turn and the session, right now. */
    private fun endNow(reason: String) {
        if (ending || state == State.IDLE) return
        Log.i(TAG, "Goodbye: ending the session immediately ($reason)")
        finish()
    }

    private fun finish() {
        if (state == State.IDLE) return                   // already finished: never report twice
        ending = true                                     // from here no late callback can process input or reopen the session
        generation++                                      // stale speak / listen / executor callbacks
        requestId++                                       // Stage 5B: stale results
        gate.cancel()                                     // Stage 5C: RequestGate
        invalidateOnline(resetHistory = true)
        main.removeCallbacksAndMessages(null)             // timeouts, watchdogs, barge-in arming
        bargeArmed = false
        bargeHits = 0
        alternatives = emptyList()
        lastUtterance = ""
        lastUtteranceAt = 0L
        try { commandSpeech.stopListening() } catch (t: Throwable) { Log.w(TAG, "stopListening failed", t) }
        try { tts.interrupt() } catch (t: Throwable) { Log.w(TAG, "tts.interrupt failed", t) }
        try { tts.stop() } catch (t: Throwable) { Log.w(TAG, "tts.stop failed", t) }
        core()?.setVoiceAmplitude(0f)
        conversationContext.sessionState = SessionState.ENDING
        conversationContext.clear()
        setState(State.IDLE)
        core()?.setState(JarvisState.READY)               // back to READY; the next interaction needs the wake word
        callback.onConversationFinished()
    }

    private fun setState(s: State) {
        conversationContext.sessionState = when (s) {
            State.IDLE -> SessionState.IDLE
            State.COMMAND_LISTENING -> SessionState.ACTIVE_LISTENING
            State.COMMAND_PROCESSING -> SessionState.PROCESSING
            State.RESPONDING -> SessionState.SPEAKING
        }
        if (state == s) return
        if (state == State.RESPONDING) disarmBargeIn()      // leaving a reply: close the barge-in listener
        state = s
        // Keep the visual state in step with the session state (SPEAKING is set by the TTS onStart).
        when (s) {
            State.COMMAND_LISTENING -> core()?.setState(JarvisState.LISTENING)
            State.COMMAND_PROCESSING -> core()?.setState(JarvisState.THINKING)
            else -> Unit
        }
        callback.onStateChanged(s)
    }

    fun release() {
        cancel()
        commandSpeech.destroy()
    }

    private companion object {
        val SPEAK_TOKEN = Any()
        const val TAG = "JarvisConversation"

        const val FIRST_TURN_TIMEOUT_MS = 25_000L        // right after the wake word
        const val FOLLOW_UP_TIMEOUT_MS = 15_000L         // after JARVIS answered: short window to continue without the wake word
        const val DUPLICATE_WINDOW_MS = 2_500L
        const val TOOL_FAILED = "نتوانستم این کار را انجام بدهم."
        val FILLERS = setOf("اوم", "هوم", "آها", "اها", "خب", "خو", "هان", "آهان", "اوهوم", "ام", "اِ", "عه")
        const val WATCHDOG_SLACK_MS = 20_000L            // covers one STT window (7 s wait + 12 s speech)
        const val MAX_SESSION_MS = 5 * 60_000L
        const val MAX_FAILED_ATTEMPTS = 5
        const val MAX_EMPTY_STREAK = 4
        const val MAX_PREPARE_WAIT_MS = 150_000L         // a loading STT model may extend the silence watchdog this long
        const val PREPARE_RECHECK_MS = 3_000L
        const val NOT_AVAILABLE_EXTRA_DELAY_MS = 1_000L

        const val LISTEN_DELAY_MS = 400L                 // lets the tail of our own voice die out
        const val RETRY_DELAY_MS = 400L
        const val AFTER_COMMAND_DELAY_MS = 700L          // an app may just have come to the front

        // Synthesis of a NEW sentence happens before onStart(), so this must be generous (Piper on a phone).
        const val SPEAK_START_TIMEOUT_MS = 12_000L
        const val SPEAK_BASE_MS = 14_000L
        const val SPEAK_PER_CHAR_MS = 90L
        const val SPEAK_MAX_MS = 40_000L

        const val BARGE_ARM_DELAY_MS = 800L              // after audio starts: lets the first words play, avoids the start click
        const val BARGE_REARM_DELAY_MS = 300L
        const val BARGE_LEVEL = 0.45f                    // voice level (0..1) that counts as the user speaking; tune on device
        const val BARGE_HITS = 3                         // consecutive level callbacks above BARGE_LEVEL

        const val MULTI_STOPPED = "بقیه دستورها اجرا نشد."
        const val ONLINE_MAX_MS = 80_000L                // safety net above the OnlineBrain's own turn timeout
    }
}
