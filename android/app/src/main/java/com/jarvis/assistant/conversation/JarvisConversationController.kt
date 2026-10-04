package com.jarvis.assistant.conversation

import android.os.Handler
import android.os.Looper
import com.jarvis.assistant.command.JarvisAction
import com.jarvis.assistant.command.JarvisActionExecutor
import com.jarvis.assistant.command.JarvisCommandProcessor
import com.jarvis.assistant.core.JarvisCoreView
import com.jarvis.assistant.core.JarvisState
import com.jarvis.assistant.speech.CommandSpeechError
import com.jarvis.assistant.speech.JarvisCommandSpeechController
import com.jarvis.assistant.speech.JarvisSpeechController

/**
 * Command phase of a conversation, after wake word + "بله ارباب.":
 *
 *   COMMAND_LISTENING -> COMMAND_PROCESSING -> RESPONDING (TTS, action) -> LISTENING again or finished
 *
 * Owns no microphone besides the command recognizer; the caller guarantees Vosk is suspended.
 * Reports the end of the conversation via [Callback.onConversationFinished]. Main thread only.
 */
class JarvisConversationController(
    private val tts: JarvisSpeechController,
    private val commandSpeech: JarvisCommandSpeechController,
    private val processor: JarvisCommandProcessor,
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

    private val overallTimeout = Runnable { finish() }

    init {
        commandSpeech.setListener(object : JarvisCommandSpeechController.Listener {
            override fun onListeningStarted() { core()?.setState(JarvisState.LISTENING) }
            override fun onFinalResult(text: String) = onCommand(text)
            override fun onError(error: CommandSpeechError) = onSpeechError(error)
            override fun onVoiceLevel(level: Float) { if (state == State.COMMAND_LISTENING) core()?.setVoiceAmplitude(level) }
        })
    }

    /** Call when "بله ارباب." has finished. */
    fun begin() {
        if (state != State.IDLE) return
        failedAttempts = 0
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
        main.postDelayed({ if (gen == generation && state == State.COMMAND_LISTENING) commandSpeech.startListening() }, LISTEN_DELAY_MS)
    }

    private fun onCommand(text: String) {
        if (state != State.COMMAND_LISTENING) return
        generation++
        setState(State.COMMAND_PROCESSING)
        core()?.setVoiceAmplitude(0f)
        core()?.setState(JarvisState.THINKING)
        val result = processor.process(text)
        if (!result.handled) {
            failedAttempts++
            speak(result.responseText) { if (failedAttempts >= MAX_FAILED_ATTEMPTS) finish() else listen() }
            return
        }
        val action = result.action
        speak(result.responseText) {
            if (action == null || action == JarvisAction.DismissAssistant) { finish(); return@speak }
            val outcome = executor.execute(action)
            if (outcome.success || outcome.message == null) finish()
            else speak(outcome.message) { finish() }
        }
    }

    private fun onSpeechError(error: CommandSpeechError) {
        if (state != State.COMMAND_LISTENING) return
        generation++
        when (error) {
            CommandSpeechError.NO_SPEECH, CommandSpeechError.NO_MATCH -> retryOrFinish(null)
            CommandSpeechError.NO_PERMISSION -> speak("اجازه میکروفون لازم است.") { finish() }
            CommandSpeechError.NOT_AVAILABLE -> speak("تشخیص گفتار روی این گوشی در دسترس نیست.") { finish() }
            CommandSpeechError.NETWORK -> speak("برای تشخیص گفتار به اینترنت نیاز دارم.") { finish() }
            CommandSpeechError.BUSY, CommandSpeechError.AUDIO, CommandSpeechError.OTHER -> retryOrFinish(null)
        }
    }

    private fun retryOrFinish(message: String?) {
        failedAttempts++
        if (failedAttempts >= MAX_FAILED_ATTEMPTS) { finish(); return }
        val next = Runnable { listen() }
        if (message != null) speak(message) { listen() } else main.postDelayed(next, RETRY_DELAY_MS)
    }

    /** Speaks [text]; [then] runs once when done, failed, or after a safety timeout. */
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
        main.postAtTime(proceed, SPEAK_TOKEN, android.os.SystemClock.uptimeMillis() + SPEAK_TIMEOUT_MS)
        tts.speak(text, object : JarvisSpeechController.Callback {
            override fun onStart() { if (gen == generation) core()?.setState(JarvisState.SPEAKING) }
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
        const val LISTEN_DELAY_MS = 350L
        const val RETRY_DELAY_MS = 300L
        const val SPEAK_TIMEOUT_MS = 6000L
        const val OVERALL_TIMEOUT_MS = 60_000L
    }
}
