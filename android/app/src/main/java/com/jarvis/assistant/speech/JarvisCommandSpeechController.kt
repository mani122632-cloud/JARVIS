package com.jarvis.assistant.speech

import android.content.Context
import com.jarvis.assistant.speech.stt.OfflinePersianStt

enum class CommandSpeechError { NO_PERMISSION, NOT_AVAILABLE, NO_SPEECH, NO_MATCH, NETWORK, BUSY, AUDIO, OTHER, MODEL_MISSING }

/**
 * Command-phase speech recognition (Persian). Since the offline-STT stage this is a thin facade over
 * [OfflinePersianStt] (sherpa-onnx, fully local): no android.speech.SpeechRecognizer, no network.
 * The public API is unchanged, so JarvisConversationController and JarvisOverlayService need no changes.
 *
 * One short session per [startListening]; the microphone is closed before the text is delivered and is
 * never held between commands. Main thread only. The caller must make sure no other microphone consumer
 * (Vosk) is running.
 */
class JarvisCommandSpeechController(context: Context) {

    interface Listener {
        fun onListeningStarted() {}
        fun onPartialResult(text: String) {}          // kept for API compatibility; the offline engine has no partials
        fun onFinalResult(text: String) {}
        /** All alternatives of the final result (best first). Called right before [onFinalResult] with the same best text. */
        fun onFinalAlternatives(texts: List<String>) {}
        fun onError(error: CommandSpeechError) {}
        /** Always called once per session, after the final result or error. */
        fun onListeningStopped() {}
        /** Voice level 0..1, for the reactor. */
        fun onVoiceLevel(level: Float) {}
    }

    private val stt = OfflinePersianStt(context)      // starts loading the model in the background, once
    private var listener: Listener? = null

    init {
        stt.setListener(object : OfflinePersianStt.Listener {
            override fun onListeningStarted() { listener?.onListeningStarted() }
            override fun onVoiceLevel(level: Float) { listener?.onVoiceLevel(level) }
            override fun onResult(text: String) {
                val l = listener
                l?.onFinalAlternatives(listOf(text))
                l?.onFinalResult(text)
            }
            override fun onError(error: CommandSpeechError) { listener?.onError(error) }
            override fun onListeningStopped() { listener?.onListeningStopped() }
        })
    }

    fun setListener(l: Listener?) { listener = l }

    val isListening: Boolean get() = stt.isListening

    fun startListening() = stt.startListening()

    /** Aborts the session silently (no callbacks) and releases the microphone. */
    fun stopListening() = stt.stopListening()

    /** Stops and frees the STT model. The controller must not be used afterwards. */
    fun destroy() {
        listener = null
        stt.release()
    }
}
