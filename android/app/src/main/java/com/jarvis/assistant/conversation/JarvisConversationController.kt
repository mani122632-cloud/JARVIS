package com.jarvis.assistant.conversation

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.jarvis.assistant.brain.BrainResult
import com.jarvis.assistant.brain.JarvisBrain
import com.jarvis.assistant.brain.llm.ReplyStreamSink
import com.jarvis.assistant.brain.llm.StreamingJarvisBrain
import com.jarvis.assistant.command.CallTool
import com.jarvis.assistant.command.JarvisAction
import com.jarvis.assistant.command.JarvisActionExecutor
import com.jarvis.assistant.core.JarvisCoreView
import com.jarvis.assistant.core.JarvisState
import com.jarvis.assistant.speech.CommandSpeechError
import com.jarvis.assistant.speech.JarvisPhrases
import com.jarvis.assistant.speech.JarvisSpeechController
import com.jarvis.assistant.speech.SpeechInput
import com.jarvis.assistant.speech.StreamingSpeechAdapter

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
 * Streamed replies: with a [StreamingJarvisBrain] the reply is spoken WHILE the LLM is still writing it. The Brain
 * hands over natural segments ([ReplyStreamSink]); [StreamingSpeechAdapter] plays them one after another through the
 * same [tts] (Gyro), so the first sentence starts as soon as it exists. The turn's result still arrives once; a
 * Conversation result whose text was already spoken is not spoken again, anything else (a question, a command, an
 * end of conversation, a tool summary) is handled as before, after the queued speech has finished.
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
    val conversationContext: ConversationContext = ConversationContext()
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

    /** Plays the segments of a streamed reply, one at a time, through [tts]. */
    private val speechStream = StreamingSpeechAdapter(tts)

    /** The streamed reply of the current turn (null when the turn is not streamed or already finished). */
    private var replyStream: ReplyStream? = null

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
        abortReplyStream()
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

        // The Brain decides everything, including the end of the conversation (an intent, not a string compare).
        // The LLM Brain answers asynchronously (network); the offline Brain answers immediately. Either way the
        // result is delivered once, on the main thread, and dropped if this turn was cancelled meanwhile.
        val turnGen = generation
        main.removeCallbacks(processingTimeout)
        abortReplyStream()
        val streaming = brain as? StreamingJarvisBrain
        val stream = if (streaming != null) ReplyStream(turnGen) else null
        replyStream = stream
        processingTimeout = Runnable {
            // A streamed reply that is already being spoken is ended there; otherwise the turn is unusable.
            val rs = replyStream
            val result = if (rs != null && rs.delivered) {
                rs.finalSpoken = true
                streaming?.cancelStreaming()
                BrainResult.Conversation("")
            } else {
                BrainResult.Unknown()
            }
            deliverBrainResult(turnGen, result)
        }
        main.postDelayed(processingTimeout, PROCESSING_TIMEOUT_MS)
        try {
            if (streaming != null && stream != null) {
                streaming.thinkStreamAsync(best, conversationContext, stream.sink) { result -> deliverBrainResult(turnGen, result) }
            } else {
                brain.thinkAsync(best, conversationContext) { result -> deliverBrainResult(turnGen, result) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Brain threw", e)
            deliverBrainResult(turnGen, BrainResult.Unknown())
        }
    }

    /** Drops a streamed reply in progress: silence, and the Brain closes its LLM connection. */
    private fun abortReplyStream() {
        replyStream = null
        speechStream.cancel()
        try { (brain as? StreamingJarvisBrain)?.cancelStreaming() } catch (t: Throwable) { Log.w(TAG, "cancelStreaming failed", t) }
    }

    /**
     * The spoken side of one streamed turn. The Brain delivers segments ([sink]); the first one switches the session to
     * RESPONDING and starts the voice. When the Brain's result arrives it waits until the queued speech is finished and
     * only then continues, so a command never cuts the sentence that announces it.
     */
    private inner class ReplyStream(val turnGen: Int) : StreamingSpeechAdapter.Listener {
        var delivered = false
        var finalSpoken = false
        private var pendingResult: BrainResult? = null

        val sink = object : ReplyStreamSink {
            override fun onSegment(text: String) { this@ReplyStream.onSegment(text) }
            override fun onFinalReplySpoken() { if (isCurrent()) finalSpoken = true }
        }

        private fun isCurrent(): Boolean =
            replyStream === this && turnGen == generation &&
                (state == State.COMMAND_PROCESSING || state == State.RESPONDING)

        private fun onSegment(text: String) {
            if (!isCurrent()) return
            if (!delivered) {
                delivered = true
                setState(State.RESPONDING)
                speechStream.open(this)
            }
            speechStream.enqueue(text)
        }

        override fun onFirstAudio() {
            if (isCurrent()) core()?.setState(JarvisState.SPEAKING)
        }

        /** The Brain finished: continue after the last queued segment has been spoken. */
        fun complete(result: BrainResult) {
            pendingResult = result
            speechStream.close()
        }

        override fun onDrained(success: Boolean) {
            if (replyStream !== this || turnGen != generation) return      // cancelled meanwhile
            replyStream = null
            val result = pendingResult ?: return
            guarded {
                if (result is BrainResult.Conversation && finalSpoken) {
                    // Already spoken while it was written: only remember it and listen again.
                    conversationContext.addResponse(result.responseText)
                    listen(LISTEN_DELAY_MS, newTurn = true)
                } else {
                    handleBrainResult(result)
                }
            }
        }
    }

    /** Safety net: a Brain that never answers must not leave the session stuck in COMMAND_PROCESSING. */
    private var processingTimeout = Runnable { }

    private fun deliverBrainResult(turnGen: Int, result: BrainResult) {
        if (turnGen != generation) return                                          // cancelled / finished / duplicate
        val rs = replyStream
        if (rs != null && rs.turnGen == turnGen && rs.delivered && state == State.RESPONDING) {
            main.removeCallbacks(processingTimeout)
            rs.complete(result)                                                    // continues once the voice is done
            return
        }
        if (state != State.COMMAND_PROCESSING) return
        main.removeCallbacks(processingTimeout)
        replyStream = null                                                         // nothing was spoken from the stream
        guarded { handleBrainResult(result) }
    }

    private fun handleBrainResult(result: BrainResult) {
        Log.i(TAG, "Brain decided: ${result.kind}" +
            ((result as? BrainResult.Command)?.let { " ${it.action::class.simpleName} conf=${it.confidence}" } ?: ""))
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
        if (action is JarvisAction.ToolCall && action.tool == CallTool.NAME) {
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

    private fun handleOutcome(action: JarvisAction, outcome: JarvisActionExecutor.Outcome) {
        val message = outcome.message
        when {
            // Back's only real effect is closing the assistant, so the session ends with it.
            action == JarvisAction.GoBack -> finish()
            // The executor's sentence: the reason for a failure, or the confirmation of a real alarm / timer.
            message != null -> {
                conversationContext.addResponse(message)
                speak(message) { listen(LISTEN_DELAY_MS, newTurn = true) }
            }
            // The session stays open: JARVIS listens again so the user can keep talking.
            else -> listen(AFTER_COMMAND_DELAY_MS, newTurn = true)
        }
    }

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
        abortReplyStream()
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
        const val PROCESSING_TIMEOUT_MS = 65_000L        // upper bound for one Brain decision (LLM round trips included); above the LLM Brain's 58 s
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
    }
}
