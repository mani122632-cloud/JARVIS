package com.jarvis.assistant.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

enum class CommandSpeechError { NO_PERMISSION, NOT_AVAILABLE, NO_SPEECH, NO_MATCH, NETWORK, BUSY, AUDIO, OTHER }

/**
 * Command-phase speech recognition (Persian) on top of android.speech.SpeechRecognizer.
 * One short session per [startListening]: a fresh recognizer is created, and destroyed after the final
 * result or an error, so the microphone is never held between commands. Main thread only.
 * The caller must make sure no other microphone consumer (Vosk) is running.
 */
class JarvisCommandSpeechController(context: Context) {

    interface Listener {
        fun onListeningStarted() {}
        fun onPartialResult(text: String) {}
        fun onFinalResult(text: String) {}
        fun onError(error: CommandSpeechError) {}
        /** Always called once per session, after the final result or error. */
        fun onListeningStopped() {}
        /** Voice level 0..1, for the reactor. */
        fun onVoiceLevel(level: Float) {}
    }

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var listener: Listener? = null
    private var session = 0
    private var active = false
    private var lastPartial = ""

    private val watchdog = Runnable {
        val id = session
        Log.w(TAG, "Recognizer watchdog fired")
        finish(id, error = CommandSpeechError.NO_SPEECH)
    }

    fun setListener(l: Listener?) { listener = l }

    val isListening: Boolean get() = active

    fun startListening() {
        if (active) return
        if (app.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED) {
            main.post { listener?.onError(CommandSpeechError.NO_PERMISSION); listener?.onListeningStopped() }
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(app)) {
            main.post { listener?.onError(CommandSpeechError.NOT_AVAILABLE); listener?.onListeningStopped() }
            return
        }
        val id = ++session
        active = true
        lastPartial = ""
        try {
            val r = SpeechRecognizer.createSpeechRecognizer(app)
            recognizer = r
            r.setRecognitionListener(RecListener(id))
            r.startListening(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fa-IR")
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "fa-IR")
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                    putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, app.packageName)
                }
            )
            main.postDelayed(watchdog, MAX_SESSION_MS)
        } catch (e: Throwable) {
            Log.e(TAG, "startListening failed", e)
            finish(id, error = CommandSpeechError.OTHER)
        }
    }

    /** Aborts the session silently (no callbacks) and releases the microphone. */
    fun stopListening() {
        if (!active) return
        session++                       // invalidates callbacks of the old recognizer
        active = false
        main.removeCallbacks(watchdog)
        releaseRecognizer()
    }

    fun destroy() {
        stopListening()
        listener = null
    }

    private fun releaseRecognizer() {
        val r = recognizer ?: return
        recognizer = null
        try { r.setRecognitionListener(null); r.cancel() } catch (e: Throwable) { /* ignore */ }
        try { r.destroy() } catch (e: Throwable) { /* ignore */ }
    }

    private fun finish(id: Int, finalText: String? = null, error: CommandSpeechError? = null) {
        if (id != session || !active) return
        active = false
        main.removeCallbacks(watchdog)
        releaseRecognizer()
        val l = listener
        when {
            !finalText.isNullOrBlank() -> l?.onFinalResult(finalText)
            error == CommandSpeechError.NO_MATCH && lastPartial.isNotBlank() -> l?.onFinalResult(lastPartial)
            else -> l?.onError(error ?: CommandSpeechError.NO_MATCH)
        }
        l?.onListeningStopped()
    }

    private inner class RecListener(private val id: Int) : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            if (id == session && active) listener?.onListeningStarted()
        }
        override fun onPartialResults(partialResults: Bundle?) {
            if (id != session || !active) return
            val t = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (!t.isNullOrBlank()) { lastPartial = t; listener?.onPartialResult(t) }
        }
        override fun onResults(results: Bundle?) {
            val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            finish(id, finalText = t, error = CommandSpeechError.NO_MATCH)
        }
        override fun onError(error: Int) {
            finish(id, error = when (error) {
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> CommandSpeechError.NO_SPEECH
                SpeechRecognizer.ERROR_NO_MATCH -> CommandSpeechError.NO_MATCH
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> CommandSpeechError.NO_PERMISSION
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> CommandSpeechError.NETWORK
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> CommandSpeechError.BUSY
                SpeechRecognizer.ERROR_AUDIO -> CommandSpeechError.AUDIO
                else -> CommandSpeechError.OTHER
            })
        }
        override fun onRmsChanged(rmsdB: Float) {
            if (id == session && active) listener?.onVoiceLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
        }
        override fun onBeginningOfSpeech() {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private companion object {
        const val TAG = "JarvisCommandSpeech"
        const val MAX_SESSION_MS = 15_000L
    }
}
