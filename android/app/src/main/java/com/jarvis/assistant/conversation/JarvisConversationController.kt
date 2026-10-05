package com.jarvis.assistant.conversation

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.jarvis.assistant.brain.BrainResult
import com.jarvis.assistant.brain.JarvisBrain
import com.jarvis.assistant.command.JarvisAction
import com.jarvis.assistant.command.JarvisActionExecutor
import com.jarvis.assistant.core.JarvisCoreView
import com.jarvis.assistant.core.JarvisState
import com.jarvis.assistant.speech.CommandSpeechError
import com.jarvis.assistant.speech.JarvisCommandSpeechController
import com.jarvis.assistant.speech.JarvisPhrases
import com.jarvis.assistant.speech.JarvisSpeechController

/**
 * Command phase of a conversation, after wake word + "بله ارباب.":
 *
 *   COMMAND_LISTENING -> COMMAND_PROCESSING (Brain) -> RESPONDING (TTS, then the action for a COMMAND) -> finished
 *
 * The Brain (Stage 46.4) decides between COMMAND (say "حتماً.", run the action), CONVERSATION (say the reply)
 * and UNKNOWN (say "متوجه نشدم.", run nothing). Every outcome ends the conversation; the wake word resumes.
 *
 * Owns no microphone besides the command recognizer; the caller guarantees Vosk is suspended.
 * Reports the end of the conversation via [Callback.onConversationFinished]. Main thread only.
 */
class JarvisConversationController(
    private val tts: JarvisSpeechController,
    private val commandSpeech: JarvisCommandSpeechController,
    private val brain: JarvisBrain,
    private val executor: JarvisActionExecutor,
    private val core: () -> JarvisCoreView?,
    private val callback: Callback
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
    private var failedAttempts = 0

    /** Recognizer alternatives of the current final result; the Brain picks the one it can handle. */
    private var alternatives: List<String> = emptyList()

    /** The single short retry that is allowed before "متوجه نشدم." is spoken. */
    private var retried = false

    /** True once an action of this conversation was handed to the executor: a command runs at most once. */
    private var dispatched = false

    private val overallTimeout = Runnable { finish() }

    init {
        commandSpeech.setListener(object : JarvisCommandSpeechController.Listener {
            override fun onListeningStarted() { core()?.setState(JarvisState.LISTENING) }
            override fun onPartialResult(text: String) = onPartial(text)
            override fun onFinalAlternatives(texts: List<String>) { alternatives = texts }
            override fun onFinalResult(text: String) = onCommand(text)
            override fun onError(error: CommandSpeechError) = onSpeechError(error)
            override fun onVoiceLevel(level: Float) { if (state == State.COMMAND_LISTENING) core()?.setVoiceAmplitude(level) }
        })
    }

    /** Call when "بله ارباب." has finished. */
    fun begin() {
        if (state != State.IDLE) return
        failedAttempts = 0
        retried = false
        alternatives = emptyList()
        dispatched = false
        main.removeCallbacks(overallTimeout)
        main.postDelayed(overallTimeout, OVERALL_TIMEOUT_MS)
        listen()
    }

    /** Aborts everything silently (no finished callback). */
    fun cancel() {
        generation++
        main.removeCallbacksAndMessages(null)
        commandSpeech.stopListening()
        tts.stop()
        core()?.setVoiceAmplitude(0f)
        setState(State.IDLE)
    }

    private fun listen() {
        val gen = ++generation
        setState(State.COMMAND_LISTENING)
        core()?.setState(JarvisState.LISTENING)
        // Short pause so the recognizer doesn't hear the tail of our own voice.
        val delay = if (retried || failedAttempts > 0) RETRY_DELAY_MS else LISTEN_DELAY_MS
        main.postDelayed({ if (gen == generation && state == State.COMMAND_LISTENING) commandSpeech.startListening() }, delay)
    }

    /**
     * Fast path: a partial that already is a complete, high-confidence command and stays unchanged for
     * [PARTIAL_STABLE_MS] is executed without waiting for the recognizer's (sometimes very slow) end-of-speech.
     * Any newer partial restarts the wait, so "اینستاگرام ... رو ببند" is not cut off at "اینستاگرام", and
     * low-confidence partials ("صدا رو روی ۵") always wait for the final result.
     */
    private fun onPartial(text: String) {
        if (state != State.COMMAND_LISTENING || dispatched) return
        main.removeCallbacks(partialRunnable)
        if (!brain.isConfidentCommand(text)) return
        partialText = text
        main.postDelayed(partialRunnable, PARTIAL_STABLE_MS)
    }

    private var partialText = ""
    private val partialRunnable = Runnable {
        if (state != State.COMMAND_LISTENING) return@Runnable
        Log.i(TAG, "Executing stable partial (${partialText.length} chars)")
        commandSpeech.stopListening()                    // silent abort: releases the microphone now
        onCommand(partialText)
    }

    private fun onCommand(text: String) {
        // Single entry for partial AND final results: the first one moves us out of COMMAND_LISTENING,
        // so the other (and any late duplicate) is ignored here and cannot run the action again.
        if (state != State.COMMAND_LISTENING || dispatched) return
        main.removeCallbacks(partialRunnable)
        Log.i(TAG, "Utterance received (${text.length} chars)")   // text itself is never logged (may hold memory facts)
        generation++
        setState(State.COMMAND_PROCESSING)
        core()?.setVoiceAmplitude(0f)
        core()?.setState(JarvisState.THINKING)
        val candidates = (listOf(text) + alternatives).distinct()
        alternatives = emptyList()
        val best = try { brain.pickBest(candidates).ifBlank { text } } catch (e: Exception) { text }
        val result = try {
            brain.think(best)
        } catch (e: Exception) {                          // the Brain promises not to throw; stay safe
            Log.e(TAG, "Brain threw", e)
            BrainResult.Unknown()
        }
        Log.i(TAG, "Brain decided: ${result.kind}" + ((result as? BrainResult.Command)?.let { " ${it.action::class.simpleName} conf=${it.confidence}" } ?: ""))
        when (result) {
            // Conversation or unknown: speak, execute nothing, end (wake word resumes).
            is BrainResult.Conversation -> speak(result.responseText) { finish() }
            is BrainResult.Unknown -> if (retryOnce()) Unit else speak(result.responseText) { finish() }
            is BrainResult.Command -> runCommand(result)
        }
    }

    private fun runCommand(result: BrainResult.Command) {
        val action = result.action
        if (action is JarvisAction.Unknown) {              // never executed
            if (!retryOnce()) speak(JarvisPhrases.NOT_UNDERSTOOD) { finish() }
            return
        }
        speak(result.responseText) {                       // "حتماً."
            if (dispatched) return@speak
            dispatched = true
            if (action == JarvisAction.DismissAssistant) { finish(); return@speak }
            val outcome = try {
                executor.execute(action)
            } catch (e: RuntimeException) {                // executor promises not to throw; stay safe
                Log.e(TAG, "Executor threw", e)
                JarvisActionExecutor.Outcome(false, null)
            }
            val message = outcome.message
            if (outcome.success || message == null) finish() else speak(message) { finish() }
        }
    }

    private fun onSpeechError(error: CommandSpeechError) {
        if (state != State.COMMAND_LISTENING) return
        generation++
        when (error) {
            // Empty / weak first result: ONE short silent retry, only then "متوجه نشدم.".
            CommandSpeechError.NO_SPEECH, CommandSpeechError.NO_MATCH ->
                if (!retryOnce()) speak(JarvisPhrases.NOT_UNDERSTOOD) { finish() }
            CommandSpeechError.NO_PERMISSION -> speak("اجازه میکروفون لازم است.") { finish() }
            CommandSpeechError.NOT_AVAILABLE -> speak("تشخیص گفتار روی این گوشی در دسترس نیست.") { finish() }
            CommandSpeechError.NETWORK -> speak("برای تشخیص گفتار به اینترنت نیاز دارم.") { finish() }
            CommandSpeechError.BUSY, CommandSpeechError.AUDIO, CommandSpeechError.OTHER -> {
                // Transient recognizer problem: one immediate retry, then give up.
                failedAttempts++
                if (failedAttempts >= MAX_FAILED_ATTEMPTS) finish() else listen()
            }
        }
    }

    /** First empty/unrecognized result: listen once more without speaking. False when the retry is already used. */
    private fun retryOnce(): Boolean {
        if (retried || dispatched) return false
        retried = true
        Log.i(TAG, "Empty or unrecognized result; one retry")
        main.removeCallbacks(partialRunnable)
        commandSpeech.stopListening()
        listen()
        return true
    }

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
        val now = android.os.SystemClock.uptimeMillis()
        main.postAtTime(startTimeout, SPEAK_TOKEN, now + SPEAK_START_TIMEOUT_MS)
        main.postAtTime(proceed, SPEAK_TOKEN, now + SPEAK_TIMEOUT_MS)
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
        core()?.setVoiceAmplitude(0f)
        setState(State.IDLE)
        callback.onConversationFinished()
    }

    private fun setState(s: State) {
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
        const val MAX_FAILED_ATTEMPTS = 2
        const val TAG = "JarvisConversation"
        const val LISTEN_DELAY_MS = 250L          // lets the tail of our own voice die out
        const val RETRY_DELAY_MS = 400L
        const val PARTIAL_STABLE_MS = 500L
        const val SPEAK_START_TIMEOUT_MS = 3000L
        const val SPEAK_TIMEOUT_MS = 8000L
        const val OVERALL_TIMEOUT_MS = 60_000L
    }
}
