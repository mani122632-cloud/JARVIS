package com.jarvis.assistant.activation

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.jarvis.assistant.core.JarvisCoreView
import com.jarvis.assistant.core.JarvisState
import com.jarvis.assistant.speech.JarvisSpeechController
import java.lang.ref.WeakReference

/**
 * Single entry point for "JARVIS was summoned", whatever the trigger (wake word, assistant
 * role, headset, hardware button). Triggers only call [activate].
 *
 * Two modes:
 *  - Overlay (Stage 43): pass an [OverlayPresenter]. activate() asks it to show the overlay, then runs
 *    LISTENING -> speak "بله ارباب." (SPEAKING) -> LISTENING. deactivate() sets READY and hides the overlay.
 *  - Legacy (Stage 42): no presenter; bind() a JarvisCoreView that lives in an Activity.
 *
 * Uses only the existing JarvisCoreView API; owns no animation. Main thread only.
 */
class JarvisActivationController(
    private val speech: JarvisSpeechController,
    private val presenter: OverlayPresenter? = null,
    /** Call the core's own showCinematic()/hideCinematic() too. Set false if it doubles the overlay's fade. */
    private val useCinematic: Boolean = true
) {

    enum class Source { MANUAL, WAKE_WORD, ASSISTANT, OVERLAY, HEADSET, HARDWARE_BUTTON }
    enum class Phase { IDLE, ACTIVATING, READY_FOR_COMMAND }

    /** The future command-listening stage hooks in here. */
    interface Listener { fun onReadyForCommand() }

    /** Implemented by JarvisOverlayService. Both calls must be idempotent. */
    interface OverlayPresenter {
        /** Shows the overlay (no-op if already shown) and returns its core, or null if it could not be shown. */
        fun showOverlay(): JarvisCoreView?
        /** Fades the overlay out and removes it. Safe when nothing is shown. */
        fun hideOverlay()
    }

    var listener: Listener? = null

    var phase = Phase.IDLE
        private set

    private val main = Handler(Looper.getMainLooper())
    private var coreRef: WeakReference<JarvisCoreView>? = null
    private var generation = 0

    /** True only for a session started by a valid WAKE_WORD, and only until that session ends. */
    private var wakeSession = false

    /** Command input is allowed only while a wake-word session is in READY_FOR_COMMAND. */
    val commandAuthorized: Boolean
        get() = wakeSession && phase == Phase.READY_FOR_COMMAND

    /** TTS never even started (engine dead / no Persian voice): don't make the user wait. */
    private val startTimeoutRunnable = Runnable {
        if (phase != Phase.ACTIVATING) return@Runnable
        Log.w(TAG, "TTS did not start within ${SPEECH_START_TIMEOUT_MS} ms; continuing to command listening")
        speech.stop()
        completeActivation(generation)
    }

    private val timeoutRunnable = Runnable {
        // TTS never answered: don't stay stuck; continue as if the phrase finished.
        speech.stop()
        completeActivation(generation)
    }

    /** Legacy mode only (no presenter). Holds the view weakly. */
    fun bind(core: JarvisCoreView) { coreRef = WeakReference(core) }

    /** Cancels work and drops the view reference. Does not hide the overlay (its service owns that). */
    fun unbind() {
        cancelPending()
        phase = Phase.IDLE
        coreRef = null
    }

    /**
     * @return true if an activation started; false if ignored (already activating/active, or no core available).
     */
    fun activate(source: Source = Source.MANUAL): Boolean {
        if (source != Source.WAKE_WORD) {
            Log.w(TAG, "Activation rejected: source $source is not WAKE_WORD")
            return false
        }
        if (phase != Phase.IDLE) return false
        val core = (if (presenter != null) presenter.showOverlay() else coreRef?.get()) ?: return false
        coreRef = WeakReference(core)
        phase = Phase.ACTIVATING
        wakeSession = true
        val gen = ++generation

        if (useCinematic) core.showCinematic()
        core.setState(JarvisState.LISTENING)

        main.postDelayed(timeoutRunnable, SPEECH_TIMEOUT_MS)
        main.postDelayed(startTimeoutRunnable, SPEECH_START_TIMEOUT_MS)
        speech.speak(PHRASE, object : JarvisSpeechController.Callback {
            override fun onStart() {
                main.removeCallbacks(startTimeoutRunnable)
                if (gen == generation && phase == Phase.ACTIVATING) {
                    coreRef?.get()?.setState(JarvisState.SPEAKING)
                }
            }
            override fun onDone(success: Boolean) = completeActivation(gen)
        })
        return true
    }

    /** End the interaction: READY, then the overlay hides. Safe to call repeatedly. */
    fun deactivate() {
        cancelPending()
        phase = Phase.IDLE
        coreRef?.get()?.let {
            it.setVoiceAmplitude(0f)
            it.setState(JarvisState.READY)
            if (useCinematic) it.hideCinematic()
        }
        presenter?.hideOverlay()
    }

    private fun completeActivation(gen: Int) {
        if (gen != generation || phase != Phase.ACTIVATING) return
        main.removeCallbacks(timeoutRunnable)
        main.removeCallbacks(startTimeoutRunnable)
        if (!wakeSession) return
        phase = Phase.READY_FOR_COMMAND
        coreRef?.get()?.let {
            it.setVoiceAmplitude(0f)
            it.setState(JarvisState.LISTENING)
        }
        try {
            if (commandAuthorized) listener?.onReadyForCommand()
        } catch (t: Throwable) {            // the command stage must never crash the activation
            Log.e(TAG, "onReadyForCommand threw", t)
        }
    }

    private fun cancelPending() {
        wakeSession = false                // command permission is revoked the moment a session ends
        generation++                       // invalidates in-flight speech callbacks
        main.removeCallbacks(timeoutRunnable)
        main.removeCallbacks(startTimeoutRunnable)
        speech.stop()
    }

    private companion object {
        const val TAG = "JarvisActivation"
        val PHRASE = com.jarvis.assistant.speech.JarvisPhrases.ACK      // the configured confirmation phrase
        // Synthesis of an uncached phrase (first wake right after start-up) runs before audio starts.
        const val SPEECH_START_TIMEOUT_MS = 10_000L
        const val SPEECH_TIMEOUT_MS = 20_000L
    }
}
