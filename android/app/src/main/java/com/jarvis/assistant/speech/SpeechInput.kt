package com.jarvis.assistant.speech

/** One error vocabulary for speech input: the existing [CommandSpeechError] (incl. MODEL_MISSING). */
typealias SpeechInputError = CommandSpeechError

/**
 * Speech input contract of the conversation session (Multi-Turn phase).
 *
 * ONE UTTERANCE PER [startListening]: the session controller calls it once per turn. Every call ends with
 * exactly one of [Listener.onFinalResult] (preceded by [Listener.onFinalAlternatives]) or [Listener.onError],
 * followed by [Listener.onListeningStopped]. [stopListening] aborts silently (no callbacks) and must release
 * the microphone immediately. All callbacks arrive on the main thread; calls come from the main thread.
 *
 * The caller guarantees no other microphone user (Vosk wake word) is running while this input listens.
 *
 * Implementation: [JarvisCommandSpeechController], the facade over the Offline STT ([stt.OfflinePersianStt]).
 */
interface SpeechInput {

    interface Listener {
        fun onListeningStarted() {}
        fun onPartialResult(text: String) {}
        /** All alternatives of the final result (best first). Called right before [onFinalResult]. */
        fun onFinalAlternatives(texts: List<String>) {}
        fun onFinalResult(text: String) {}
        fun onError(error: SpeechInputError) {}
        /** Always called once per utterance, after the final result or error. */
        fun onListeningStopped() {}
        /** Voice level 0..1, for the reactor. Optional. */
        fun onVoiceLevel(level: Float) {}
    }

    val isListening: Boolean

    /** True while the recognizer is still preparing (model loading): a started utterance only answers when ready. */
    val isPreparing: Boolean get() = false

    fun setListener(l: Listener?)

    /** Starts capturing ONE utterance. No-op if already listening. */
    fun startListening()

    /** Silent abort; releases the microphone. */
    fun stopListening()

    /** Releases everything. The input must not be used afterwards. */
    fun destroy()
}
