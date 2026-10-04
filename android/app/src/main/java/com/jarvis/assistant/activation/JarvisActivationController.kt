package com.jarvis.assistant.activation

import android.os.Handler
import android.os.Looper
import com.jarvis.assistant.core.JarvisCoreView
import com.jarvis.assistant.core.JarvisState
import com.jarvis.assistant.speech.JarvisSpeechController
import java.lang.ref.WeakReference

/**
 * Single entry point for "JARVIS was summoned", whatever the trigger (wake word, assistant
 * role, overlay, headset, hardware button). Triggers only call [activate].
 *
 * Sequence: core.showCinematic() -> LISTENING -> speak "بله ارباب." -> ready for command.
 * Uses only the existing JarvisCoreView API; owns no animation. Main thread only.
 */
class JarvisActivationController(private val speech: JarvisSpeechController) {

    enum class Source { MANUAL, WAKE_WORD, ASSISTANT, OVERLAY, HEADSET, HARDWARE_BUTTON }
    enum class Phase { IDLE, ACTIVATING, READY_FOR_COMMAND }

    /** The future command-listening stage hooks in here. */
    interface Listener { fun onReadyForCommand() }

    var listener: Listener? = null

    var phase = Phase.IDLE
        private set

    private val main = Handler(Looper.getMainLooper())
    private var coreRef: WeakReference<JarvisCoreView>? = null
    private var generation = 0

    private val timeoutRunnable = Runnable {
        // TTS never answered: don't stay stuck; continue as if the phrase finished.
        speech.stop()
        completeActivation(generation)
    }

    /** Call from onCreate/onStart. Holds the view weakly. */
    fun bind(core: JarvisCoreView) { coreRef = WeakReference(core) }

    /** Call from onDestroy (or onStop). Cancels work and drops the view reference. */
    fun unbind() {
        cancelPending()
        phase = Phase.IDLE
        coreRef = null
    }

    /**
     * @return true if an activation started; false if ignored (already activating/active, or not bound).
     */
    fun activate(@Suppress("UNUSED_PARAMETER") source: Source = Source.MANUAL): Boolean {
        val core = coreRef?.get() ?: return false
        if (phase != Phase.IDLE) return false
        phase = Phase.ACTIVATING
        val gen = ++generation

        core.showCinematic()
        core.setState(JarvisState.LISTENING)

        main.postDelayed(timeoutRunnable, SPEECH_TIMEOUT_MS)
        speech.speak(PHRASE, object : JarvisSpeechController.Callback {
            override fun onStart() {
                if (gen == generation && phase == Phase.ACTIVATING) {
                    coreRef?.get()?.setState(JarvisState.SPEAKING)
                }
            }
            override fun onDone(success: Boolean) = completeActivation(gen)
        })
        return true
    }

    /** End the interaction: core sinks and the layer returns to IDLE. Safe to call repeatedly. */
    fun deactivate() {
        cancelPending()
        phase = Phase.IDLE
        coreRef?.get()?.let {
            it.setVoiceAmplitude(0f)
            it.setState(JarvisState.READY)
            it.hideCinematic()
        }
    }

    private fun completeActivation(gen: Int) {
        if (gen != generation || phase != Phase.ACTIVATING) return
        main.removeCallbacks(timeoutRunnable)
        phase = Phase.READY_FOR_COMMAND
        coreRef?.get()?.let {
            it.setVoiceAmplitude(0f)
            it.setState(JarvisState.LISTENING)
        }
        listener?.onReadyForCommand()
    }

    private fun cancelPending() {
        generation++                       // invalidates in-flight speech callbacks
        main.removeCallbacks(timeoutRunnable)
        speech.stop()
    }

    private companion object {
        const val PHRASE = "بله ارباب."
        const val SPEECH_TIMEOUT_MS = 6000L
    }
}
