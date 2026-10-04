package com.jarvis.assistant.speech

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

/**
 * Smallest useful android.speech.tts wrapper. Holds only the application context.
 * Create and use on the main thread. Requires a Persian (fa) voice installed on the device
 * for correct pronunciation.
 */
class AndroidTtsSpeechController(context: Context) : JarvisSpeechController {

    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ready = false
    private var failed = false
    private var pendingText: String? = null
    private var callback: JarvisSpeechController.Callback? = null
    private var currentId: String? = null
    private var counter = 0

    init {
        tts = TextToSpeech(context.applicationContext) { status -> main.post { onEngineInit(status) } }
    }

    private fun onEngineInit(status: Int) {
        val engine = tts ?: return                       // shut down before init finished
        if (status != TextToSpeech.SUCCESS) {
            failed = true
            pendingText = null
            currentId?.let { finish(it, false) }
            return
        }
        val r = engine.setLanguage(Locale("fa"))
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            Log.w(TAG, "Persian TTS voice not available on this device (result=$r)")
        }
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) {
                main.post { if (utteranceId == currentId) callback?.onStart() }
            }
            override fun onDone(utteranceId: String) { main.post { finish(utteranceId, true) } }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String) { main.post { finish(utteranceId, false) } }
            override fun onError(utteranceId: String, errorCode: Int) { main.post { finish(utteranceId, false) } }
        })
        ready = true
        val text = pendingText
        val id = currentId
        pendingText = null
        if (text != null && id != null) doSpeak(text, id)
    }

    override fun speak(text: String, callback: JarvisSpeechController.Callback?) {
        val engine = tts
        if (engine == null) { callback?.onDone(false); return }
        engine.stop()
        this.callback = callback
        val id = "jarvis-${++counter}"
        currentId = id
        when {
            failed -> main.post { finish(id, false) }
            !ready -> pendingText = text
            else -> doSpeak(text, id)
        }
    }

    private fun doSpeak(text: String, id: String) {
        val r = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) ?: TextToSpeech.ERROR
        if (r != TextToSpeech.SUCCESS) finish(id, false)
    }

    private fun finish(id: String, success: Boolean) {
        if (id != currentId) return                      // stale (replaced or stopped)
        val cb = callback
        callback = null
        currentId = null
        cb?.onDone(success)
    }

    override fun stop() {
        callback = null
        currentId = null
        pendingText = null
        tts?.stop()
    }

    override fun shutdown() {
        stop()
        tts?.shutdown()
        tts = null
        ready = false
    }

    private companion object { const val TAG = "JarvisTts" }
}
