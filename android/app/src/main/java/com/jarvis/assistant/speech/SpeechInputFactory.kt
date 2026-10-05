package com.jarvis.assistant.speech

import android.content.Context

/**
 * The single place that decides which [SpeechInput] the conversation session uses.
 *
 * Returns the Offline STT facade [JarvisCommandSpeechController] (implements [SpeechInput]).
 */
object SpeechInputFactory {
    fun create(context: Context): SpeechInput = JarvisCommandSpeechController(context)
}
