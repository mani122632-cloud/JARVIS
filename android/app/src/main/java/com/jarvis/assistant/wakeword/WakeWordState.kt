package com.jarvis.assistant.wakeword

import android.os.Handler
import android.os.Looper

enum class WakeStatus { OFF, NO_PERMISSION, LOADING_MODEL, MODEL_MISSING, LISTENING, SUSPENDED, ERROR }

/** High-level assistant flow: IDLE -> WAKE_WORD_LISTENING -> OVERLAY_ACTIVATING -> LISTENING. */
enum class AssistantFlow { IDLE, WAKE_WORD_LISTENING, OVERLAY_ACTIVATING, LISTENING }

/**
 * Process-wide, read-only view of the wake word state for the UI (the service writes it).
 * [listener] is invoked on the main thread after every change.
 */
object WakeWordState {
    @Volatile var status: WakeStatus = WakeStatus.OFF
        private set
    @Volatile var flow: AssistantFlow = AssistantFlow.IDLE
        private set
    /** Last recognized text; only filled in debuggable builds (developer test aid). */
    @Volatile var lastHeard: String = ""
        private set
    @Volatile var listener: Runnable? = null

    private val main = Handler(Looper.getMainLooper())

    fun update(status: WakeStatus? = null, flow: AssistantFlow? = null, heard: String? = null) {
        if (status != null) this.status = status
        if (flow != null) this.flow = flow
        if (heard != null) this.lastHeard = heard
        main.post { listener?.run() }
    }

    fun isActive(s: WakeStatus = status): Boolean =
        s == WakeStatus.LOADING_MODEL || s == WakeStatus.LISTENING || s == WakeStatus.SUSPENDED
}
