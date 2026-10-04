package com.jarvis.assistant.speech

/**
 * Minimal speech output contract. Callbacks are always delivered on the main thread.
 * Swap the implementation (cloud TTS, custom voice, ...) without touching activation code.
 */
interface JarvisSpeechController {

    interface Callback {
        /** Audio actually started. */
        fun onStart()
        /** Finished, failed, or could not start. success=false means nothing (or only part) was heard. */
        fun onDone(success: Boolean)
    }

    /** Speaks [text], replacing any speech in progress (the replaced callback is dropped). */
    fun speak(text: String, callback: Callback? = null)

    /** Stops current/pending speech. The pending callback is dropped, not invoked. */
    fun stop()

    /** Releases the engine. The controller must not be used afterwards. */
    fun shutdown()
}
