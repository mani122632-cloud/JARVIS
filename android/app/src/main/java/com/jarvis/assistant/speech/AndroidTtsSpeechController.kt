package com.jarvis.assistant.speech

import android.content.Context
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

/**
 * android.speech.tts wrapper. ONE TextToSpeech instance lives for the whole service lifetime.
 *
 * Initialization is asynchronous: speak() before the engine is ready is queued (latest wins) and spoken
 * as soon as it is. If the default engine has no Persian voice, the other installed engines are tried once
 * at startup. If no engine can speak Persian, every speak() fails fast with onDone(false) so the
 * conversation never waits on a dead TTS. Create and use on the main thread.
 *
 * Logcat tag: JarvisTts.
 */
class AndroidTtsSpeechController(context: Context) : JarvisSpeechController {

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    private var tts: TextToSpeech? = null
    private var engineGen = 0
    private var ready = false
    private var failed = false
    private var candidates: List<String>? = null     // other engines to try, filled on first failure
    private var pendingText: String? = null
    private var callback: JarvisSpeechController.Callback? = null
    private var currentId: String? = null
    private var counter = 0

    init { createEngine(null) }

    private fun createEngine(enginePackage: String?) {
        val gen = ++engineGen
        val l = TextToSpeech.OnInitListener { status -> main.post { onEngineInit(gen, status, enginePackage) } }
        Log.i(TAG, "Creating TextToSpeech engine=${enginePackage ?: "default"}")
        tts = try {
            if (enginePackage == null) TextToSpeech(app, l) else TextToSpeech(app, l, enginePackage)
        } catch (e: Throwable) {
            Log.e(TAG, "TextToSpeech constructor failed", e)
            main.post { onEngineInit(gen, TextToSpeech.ERROR, enginePackage) }
            null
        }
    }

    private fun onEngineInit(gen: Int, status: Int, enginePackage: String?) {
        if (gen != engineGen) return                     // replaced or shut down
        val engine = tts
        Log.i(TAG, "TTS initialized: status=$status engine=${engine?.defaultEngine ?: enginePackage}")
        if (engine == null || status != TextToSpeech.SUCCESS) { tryNextEngine(engine); return }

        val locale = persianLocale(engine)
        if (locale == null) {
            Log.w(TAG, "No Persian voice in engine ${engine.defaultEngine}")
            tryNextEngine(engine)
            return
        }
        val r = engine.setLanguage(locale)
        Log.i(TAG, "TTS language result: $r for $locale")
        if (r < TextToSpeech.LANG_AVAILABLE) { tryNextEngine(engine); return }

        engine.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) {
                Log.i(TAG, "TTS speak started: $utteranceId")
                main.post { if (utteranceId == currentId) callback?.onStart() }
            }
            override fun onDone(utteranceId: String) {
                Log.i(TAG, "TTS speak completed: $utteranceId")
                main.post { finish(utteranceId, true) }
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String) {
                Log.e(TAG, "TTS error: $utteranceId")
                main.post { finish(utteranceId, false) }
            }
            override fun onError(utteranceId: String, errorCode: Int) {
                Log.e(TAG, "TTS error: $utteranceId code=$errorCode")
                main.post { finish(utteranceId, false) }
            }
        })
        ready = true
        val text = pendingText
        val id = currentId
        pendingText = null
        if (text != null && id != null) doSpeak(text, id)
    }

    /** fa-IR, then fa. Null if the engine has no usable Persian voice. */
    private fun persianLocale(engine: TextToSpeech): Locale? {
        for (loc in listOf(Locale("fa", "IR"), Locale("fa"))) {
            val a = try { engine.isLanguageAvailable(loc) } catch (e: Throwable) { TextToSpeech.LANG_NOT_SUPPORTED }
            Log.i(TAG, "isLanguageAvailable($loc) = $a")
            if (a >= TextToSpeech.LANG_AVAILABLE) return loc
        }
        return null
    }

    /** Engine unusable for Persian: try the next installed engine, or give up (speak() then fails fast). */
    private fun tryNextEngine(old: TextToSpeech?) {
        if (candidates == null) {
            val current = try { old?.defaultEngine } catch (e: Throwable) { null }
            candidates = try {
                old?.engines?.map { it.name }?.filter { it != current } ?: emptyList()
            } catch (e: Throwable) { emptyList() }
            Log.i(TAG, "Other TTS engines to try: $candidates")
        }
        val next = candidates!!.firstOrNull()
        try { old?.shutdown() } catch (e: Throwable) { /* ignore */ }
        tts = null
        if (next != null) {
            candidates = candidates!!.drop(1)
            createEngine(next)
            return
        }
        Log.e(TAG, "Persian TTS is NOT available on this device; JARVIS will stay silent")
        failed = true
        engineGen++                                      // ignore any late init callbacks
        pendingText = null
        currentId?.let { id -> main.post { finish(id, false) } }
    }

    override fun speak(text: String, callback: JarvisSpeechController.Callback?) {
        if (tts == null && !failed && !ready) {          // engine swap in progress: queue like "not ready"
            this.callback = callback
            val id = "jarvis-${++counter}"
            currentId = id
            pendingText = text
            return
        }
        val engine = tts
        if (engine == null || failed) { callback?.let { main.post { it.onDone(false) } }; return }
        if (ready) engine.stop()
        this.callback = callback
        val id = "jarvis-${++counter}"
        currentId = id
        Log.i(TAG, "TTS speak requested: \"$text\" ready=$ready")
        if (!ready) pendingText = text else doSpeak(text, id)
    }

    private fun doSpeak(text: String, id: String) {
        val r = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) ?: TextToSpeech.ERROR
        if (r != TextToSpeech.SUCCESS) {
            Log.e(TAG, "TTS speak() returned $r")
            main.post { finish(id, false) }
        }
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
        if (ready) tts?.stop()
    }

    override fun shutdown() {
        stop()
        engineGen++
        try { tts?.shutdown() } catch (e: Throwable) { /* ignore */ }
        tts = null
        ready = false
    }

    private companion object { const val TAG = "JarvisTts" }
}
