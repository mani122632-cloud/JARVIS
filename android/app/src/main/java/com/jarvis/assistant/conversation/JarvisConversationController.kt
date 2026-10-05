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
import com.jarvis.assistant.nlu.PersianNormalizer
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
 * The session ends (-> [Callback.onConversationFinished], the service then hides the overlay and resumes Vosk) when
 *  - the user says an exit phrase (خداحافظ / فعلاً / تمام / دیگه کاری ندارم / برو استراحت کن / کافیه ...),
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

    /** Uptime when the current turn started waiting for the user (silent re-listens do not reset it). */
    private var turnStartedAt = 0L

    /** Consecutive utterances without usable text; "متوجه نشدم." is said once, then the session gives up quietly. */
    private var emptyStreak = 0

    /** Consecutive transient STT failures (BUSY / AUDIO / OTHER). */
    private var failedAttempts = 0

    /** Recognizer alternatives of the current final result; the Brain picks the one it can handle. */
    private var alternatives: List<String> = emptyList()

    /** Backup for a recognizer that never answers; the normal silence check happens on NO_SPEECH. */
    private val silenceWatchdog = Runnable {
        if (state == State.COMMAND_LISTENING) {
            Log.i(TAG, "Silence watchdog: ending session")
            finish()
        }
    }

    private val maxSession = Runnable {
        Log.i(TAG, "Maximum session length reached")
        finish()
    }

    init {
        commandSpeech.setListener(object : SpeechInput.Listener {
            override fun onListeningStarted() { core()?.setState(JarvisState.LISTENING) }
            override fun onFinalAlternatives(texts: List<String>) { alternatives = texts }
            override fun onFinalResult(text: String) = onUtterance(text)
            override fun onError(error: CommandSpeechError) = onSpeechError(error)
            override fun onVoiceLevel(level: Float) { if (state == State.COMMAND_LISTENING) core()?.setVoiceAmplitude(level) }
        })
    }

    /** Call when "بله ارباب." has finished. */
    fun begin() {
        if (state != State.IDLE) return
        conversationContext.clear()
        emptyStreak = 0
        failedAttempts = 0
        alternatives = emptyList()
        main.removeCallbacks(maxSession)
        main.postDelayed(maxSession, MAX_SESSION_MS)
        listen(LISTEN_DELAY_MS, newTurn = true)
    }

    /** Aborts everything silently (no finished callback). */
    fun cancel() {
        generation++
        main.removeCallbacksAndMessages(null)
        commandSpeech.stopListening()
        tts.stop()
        core()?.setVoiceAmplitude(0f)
        conversationContext.clear()
        setState(State.IDLE)
    }

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
            if (gen == generation && state == State.COMMAND_LISTENING) commandSpeech.startListening()
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
            CommandSpeechError.NOT_AVAILABLE -> speak("تشخیص گفتار روی این گوشی در دسترس نیست.") { finish() }
            CommandSpeechError.NETWORK -> speak("برای تشخیص گفتار به اینترنت نیاز دارم.") { finish() }
            CommandSpeechError.BUSY, CommandSpeechError.AUDIO, CommandSpeechError.OTHER -> {
                failedAttempts++
                if (failedAttempts >= MAX_FAILED_ATTEMPTS) finish() else listen(RETRY_DELAY_MS, newTurn = false)
            }
        }
    }

    /** Speech without usable text: retry silently; say "متوجه نشدم." only once in a row; give up after a few. */
    private fun handleUnusable() {
        emptyStreak++
        when {
            emptyStreak >= MAX_EMPTY_STREAK -> finish()
            emptyStreak == NOT_UNDERSTOOD_AT -> speak(JarvisPhrases.NOT_UNDERSTOOD) { listen(LISTEN_DELAY_MS, newTurn = false) }
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

        if (isExitPhrase(best)) {
            Log.i(TAG, "Exit phrase")
            conversationContext.sessionState = SessionState.ENDING
            speak(JarvisPhrases.GOODBYE) { finish() }
            return
        }

        // Command system first (inside the Brain), conversation second.
        val result = try {
            brain.think(best, conversationContext)
        } catch (e: Exception) {
            Log.e(TAG, "Brain threw", e)
            BrainResult.Unknown()
        }
        Log.i(TAG, "Brain decided: ${result.kind}" +
            ((result as? BrainResult.Command)?.let { " ${it.action::class.simpleName} conf=${it.confidence}" } ?: ""))
        when (result) {
            is BrainResult.Conversation -> {
                conversationContext.addResponse(result.responseText)
                speak(result.responseText) { listen(LISTEN_DELAY_MS, newTurn = true) }
            }
            is BrainResult.Unknown -> handleUnusable()
            is BrainResult.Command -> runCommand(result)
        }
    }

    private fun runCommand(result: BrainResult.Command) {
        val action = result.action
        if (action is JarvisAction.Unknown) { handleUnusable(); return }
        speak(result.responseText) {                       // "حتماً."
            if (action == JarvisAction.DismissAssistant) { finish(); return@speak }
            val outcome = try {
                executor.execute(action)
            } catch (e: RuntimeException) {                // executor promises not to throw; stay safe
                Log.e(TAG, "Executor threw", e)
                JarvisActionExecutor.Outcome(false, null)
            }
            val message = outcome.message
            when {
                // Back's only real effect is closing the assistant, so the session ends with it.
                action == JarvisAction.GoBack -> finish()
                // The session stays open: JARVIS listens again so the user can keep talking.
                outcome.success || message == null -> listen(AFTER_COMMAND_DELAY_MS, newTurn = true)
                else -> speak(message) { listen(LISTEN_DELAY_MS, newTurn = true) }
            }
        }
    }

    // ---- exit phrases ---------------------------------------------------------------------------

    private fun isExitPhrase(text: String): Boolean {
        val tokens = PersianNormalizer.tokens(text).filter { it !in EXIT_FILLER }
        if (tokens.isEmpty()) return false
        val phrase = tokens.joinToString(" ")
        if (phrase in EXIT_PHRASES) return true
        return tokens.size <= 2 && tokens.any { it in EXIT_WORDS }
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
            then()
        }
        val startTimeout = Runnable {
            Log.w(TAG, "TTS did not start; continuing without speech")
            tts.stop()
            proceed.run()
        }
        val maxSpeak = (SPEAK_BASE_MS + text.length * SPEAK_PER_CHAR_MS).coerceAtMost(SPEAK_MAX_MS)
        val now = SystemClock.uptimeMillis()
        main.postAtTime(startTimeout, SPEAK_TOKEN, now + SPEAK_START_TIMEOUT_MS)
        main.postAtTime(proceed, SPEAK_TOKEN, now + maxSpeak)
        tts.speak(text, object : JarvisSpeechController.Callback {
            override fun onStart() {
                main.removeCallbacks(startTimeout)
                if (gen == generation) core()?.setState(JarvisState.SPEAKING)
            }
            override fun onDone(success: Boolean) { main.post(proceed) }
        })
    }

    private fun finish() {
        generation++
        main.removeCallbacksAndMessages(null)
        commandSpeech.stopListening()
        tts.stop()
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
        const val MAX_FAILED_ATTEMPTS = 2
        const val MAX_EMPTY_STREAK = 4
        const val NOT_UNDERSTOOD_AT = 2

        const val LISTEN_DELAY_MS = 250L                 // lets the tail of our own voice die out
        const val RETRY_DELAY_MS = 400L
        const val AFTER_COMMAND_DELAY_MS = 700L          // an app may just have come to the front

        const val SPEAK_START_TIMEOUT_MS = 3000L
        const val SPEAK_BASE_MS = 4000L
        const val SPEAK_PER_CHAR_MS = 90L
        const val SPEAK_MAX_MS = 20_000L

        val EXIT_FILLER = setOf("جارویس", "جارویز", "هی", "لطفا", "ارباب", "بله", "ممنون", "مرسی", "ممنونم", "خب", "باشه")
        val EXIT_PHRASES = setOf(
            "خداحافظ", "خدافظ", "خدانگهدار", "فعلا", "تمام", "تمام شد", "کافیه", "کافی است", "بسه", "بای",
            "دیگه کاری ندارم", "دیگه کاری باهات ندارم", "دیگه باهات کاری ندارم", "کاری ندارم", "دیگه کاری نیست",
            "برو استراحت کن", "استراحت کن", "تا بعد"
        )
        val EXIT_WORDS = setOf("خداحافظ", "خدافظ", "خدانگهدار", "فعلا", "کافیه", "بای")
    }
}
