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
class JarvisCommandSpeechController(context: Context) : SpeechInput {

    /** Kept so existing callers compile unchanged; it is exactly [SpeechInput.Listener] (the offline engine has no partials). */
    interface Listener : SpeechInput.Listener

    private val stt = OfflinePersianStt(context)      // starts loading the model in the background, once
    private var listener: SpeechInput.Listener? = null

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

    override fun setListener(l: SpeechInput.Listener?) { listener = l }

    override val isListening: Boolean get() = stt.isListening

    override val isPreparing: Boolean get() = stt.isPreparing

    override fun startListening() { stt.startListening() }

    /** Aborts the session silently (no callbacks) and releases the microphone. */
    override fun stopListening() { stt.stopListening() }

    /** Stops and frees the STT model. The controller must not be used afterwards. */
    override fun destroy() {
        listener = null
        stt.release()
    }
}
