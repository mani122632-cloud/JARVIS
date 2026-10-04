package com.jarvis.assistant.wakeword

import android.os.Handler
import android.os.Looper

enum class WakeStatus { OFF, NO_PERMISSION, LOADING_MODEL, MODEL_MISSING, PHRASE_UNSUPPORTED, LISTENING, SUSPENDED, ERROR }

/** Offline Persian voice (TTS) status, shown next to the wake status. */
enum class TtsStatus { OFF, LOADING, READY, MODEL_MISSING, ENGINE_MISSING, ERROR }

/**
 * High-level assistant flow. Exactly one microphone user exists per state:
 *  - WAKE_WORD_LISTENING: Vosk only.
 *  - COMMAND_LISTENING: the command SpeechRecognizer only.
 *  - every other state: nobody holds the microphone (offline TTS plays through AudioTrack).
 */
enum class AssistantFlow {
    IDLE,
    WAKE_WORD_LISTENING,
    ACTIVATING,
    SPEAKING,
    COMMAND_LISTENING,
    COMMAND_PROCESSING,
    RESPONDING
}

/**
 * Process-wide, read-only view of the voice state for the UI (the service writes it).
 * [listener] is invoked on the main thread after every change.
 */
object WakeWordState {
    @Volatile var status: WakeStatus = WakeStatus.OFF
        private set
    @Volatile var flow: AssistantFlow = AssistantFlow.IDLE
        private set
    @Volatile var tts: TtsStatus = TtsStatus.OFF
        private set
    /** Last recognized text; only filled in debuggable builds (developer aid). */
    @Volatile var lastHeard: String = ""
        private set
    @Volatile var listener: Runnable? = null

    private val main = Handler(Looper.getMainLooper())

    fun update(status: WakeStatus? = null, flow: AssistantFlow? = null, heard: String? = null, tts: TtsStatus? = null) {
        if (status != null) this.status = status
        if (flow != null) this.flow = flow
        if (heard != null) this.lastHeard = heard
        if (tts != null) this.tts = tts
        main.post { listener?.run() }
    }

    fun isActive(s: WakeStatus = status): Boolean =
        s == WakeStatus.LOADING_MODEL || s == WakeStatus.LISTENING || s == WakeStatus.SUSPENDED
}
