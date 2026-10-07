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
import com.jarvis.assistant.online.Cancellable
import com.jarvis.assistant.online.Failure
import com.jarvis.assistant.online.OnlineBrain
import com.jarvis.assistant.online.OnlineFallbackPhrases
import com.jarvis.assistant.speech.CommandSpeechError
import com.jarvis.assistant.speech.JarvisPhrases
import com.jarvis.assistant.speech.JarvisSpeechController
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
 *  - nothing was said for [SILENCE_TIMEOUT_MS],
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
    private val onlineBrain: OnlineBrain? = null
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
            override fun onFinalResult(text: String) = guarded { onUtterance(text) }
            override fun onError(error: CommandSpeechError) = guarded { onSpeechError(error) }
            override fun onVoiceLevel(level: Float) { if (state == State.COMMAND_LISTENING) core()?.setVoiceAmplitude(level) }
        })
    }

    /** Call when "بله ارباب." has finished. */
    fun begin() {
        if (state != State.IDLE) {
            // A stale session must never block a new one: drop it silently and start clean.
            Log.w(TAG, "begin() while $state: resetting the previous session")
            cancel()
        }
        conversationContext.clear()
        invalidateOnline(resetHistory = true)
        emptyStreak = 0
        failedAttempts = 0
        alternatives = emptyList()
        main.removeCallbacks(maxSession)
        main.postDelayed(maxSession, MAX_SESSION_MS)
        sessionStartedAt = SystemClock.uptimeMillis()
        listen(LISTEN_DELAY_MS, newTurn = true)
    }

    /** Aborts everything silently (no finished callback). */
    fun cancel() {
        generation++
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
            main.removeCallbacks(silenceWatchdog)
            main.postDelayed(silenceWatchdog, SILENCE_TIMEOUT_MS + WATCHDOG_SLACK_MS)
        }
        // Short pause so the recognizer doesn't hear the tail of our own voice.
        main.postDelayed({
            if (gen == generation && state == State.COMMAND_LISTENING) guarded { commandSpeech.startListening() }
        }, delayMs)
    }

    /** Nothing usable was heard: listen again silently, unless this turn has been silent for too long. */
    private fun relistenOrTimeout() {
        if (SystemClock.uptimeMillis() - turnStartedAt >= SILENCE_TIMEOUT_MS) {
            Log.i(TAG, "Silence timeout: ending session")
            finish()
        } else {
            listen(RETRY_DELAY_MS, newTurn = false)
        }
    }

    private fun onSpeechError(error: CommandSpeechError) {
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
        if (state != State.COMMAND_LISTENING) return       // late duplicate
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
            BrainResult.Unknown()
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
                conversationContext.sessionState = SessionState.ENDING
                speak(result.responseText) { finish() }
            }
            is BrainResult.Unknown -> handleUnusable()
            is BrainResult.Command -> runCommand(result)
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
            if (turn.streamEnded) completeOnline(turn) else core()?.setState(JarvisState.THINKING)
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
            try {
                executor.executeAsync(action) { outcome ->
                    if (gen != generation || state == State.IDLE) return@executeAsync   // session cancelled / finished meanwhile
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
        val message = outcome.message
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
                    if (gen == generation) core()?.setState(JarvisState.SPEAKING)
                }
                override fun onDone(success: Boolean) { main.post(proceed) }
            })
        } catch (t: Throwable) {
            Log.e(TAG, "tts.speak threw", t)
            main.post(proceed)
        }
    }

    private fun finish() {
        if (state == State.IDLE) return                   // already finished: never report twice
        generation++
        invalidateOnline(resetHistory = true)
        main.removeCallbacksAndMessages(null)
        try { commandSpeech.stopListening() } catch (t: Throwable) { Log.w(TAG, "stopListening failed", t) }
        try { tts.stop() } catch (t: Throwable) { Log.w(TAG, "tts.stop failed", t) }
        core()?.setVoiceAmplitude(0f)
        conversationContext.sessionState = SessionState.ENDING
        conversationContext.clear()
        setState(State.IDLE)
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
        state = s
        callback.onStateChanged(s)
    }

    fun release() {
        cancel()
        commandSpeech.destroy()
    }

    private companion object {
        val SPEAK_TOKEN = Any()
        const val TAG = "JarvisConversation"

        const val SILENCE_TIMEOUT_MS = 25_000L           // no speech for this long ends the session (quietly)
        const val WATCHDOG_SLACK_MS = 20_000L            // covers one STT window (7 s wait + 12 s speech)
        const val MAX_SESSION_MS = 10 * 60_000L
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

        const val ONLINE_MAX_MS = 80_000L                // safety net above the OnlineBrain's own turn timeout
    }
}
