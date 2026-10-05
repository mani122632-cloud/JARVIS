package com.jarvis.assistant.speech

import android.content.Context

/**
 * The single place that decides which [SpeechInput] the conversation session uses.
 *
 * OFFLINE STT HOOK: when JARVIS-OFFLINE-STT.zip is merged, return its SpeechInput implementation here
 * (e.g. `OfflineSttSpeechInput(context)`) instead of [JarvisCommandSpeechController]. Nothing else changes.
 */
object SpeechInputFactory {
    fun create(context: Context): SpeechInput = JarvisCommandSpeechController(context)
}
