package com.jarvis.assistant.speech.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.jarvis.assistant.speech.JarvisPhrases
import com.jarvis.assistant.speech.JarvisSpeechController
import com.jarvis.assistant.wakeword.TtsStatus
import com.jarvis.assistant.wakeword.WakeWordState
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Persian speech generated locally (Piper voice through sherpa-onnx). Independent of Android's
 * system TTS and of the network.
 *
 *  - ONE engine for the whole service lifetime: the model is loaded once on a worker thread
 *    ([initialize]) and released in [shutdown]/[release].
 *  - speak() before the model is loaded waits (latest request wins); callers keep their own timeouts.
 *  - If the voice is missing or fails, every speak() fails fast with onDone(false); nothing throws.
 *  - Playback uses AudioTrack, so it never touches the microphone.
 *  - Short fixed sentences are cached after their first synthesis (see [JarvisPhrases.PREWARM]).
 *
 * Lifecycle: [initialize] (once, idempotent, never blocks the caller) -> [speak] any number of times ->
 * [stop] (cuts the current utterance) -> [release] (frees the native model; same as [shutdown]).
 * [speak] initializes by itself if [initialize] was not called.
 *
 * Problems (voice model missing, sherpa-onnx not in the build, load failure) never throw: [status] and
 * [lastError] describe them, [statusListener] is told, and every speak() fails fast with onDone(false).
 *
 * Create and call on the main thread. Callbacks are delivered on the main thread.
 * Logcat tag: JarvisTts.
 */
class OfflinePersianTts(context: Context) : JarvisSpeechController {

    /** Reports engine status changes to the layer above. Main thread. */
    fun interface StatusListener { fun onTtsStatus(status: TtsStatus, message: String?) }

    private class Pending(val id: Int, val text: String)

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "JarvisTts").apply { isDaemon = true } }

    private val generation = AtomicInteger()          // identifies the current utterance; bumped by stop()
    @Volatile private var released = false
    @Volatile private var engine: OfflineTtsEngine? = null
    @Volatile private var statusValue = TtsStatus.OFF
    private val initStarted = AtomicBoolean(false)

    /** Current engine state: OFF (not initialized), LOADING, READY, MODEL_MISSING, ENGINE_MISSING, ERROR. */
    val status: TtsStatus get() = statusValue

    /** Human-readable reason when [status] is not READY/LOADING/OFF (English, for logs and the layer above). */
    @Volatile var lastError: String? = null
        private set

    val isReady: Boolean get() = statusValue == TtsStatus.READY

    var statusListener: StatusListener? = null

    // main-thread state
    private var callback: JarvisSpeechController.Callback? = null
    private var pending: Pending? = null

    @Volatile private var track: AudioTrack? = null
    private val cache = object : LinkedHashMap<String, PcmAudio>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PcmAudio>?) = size > CACHE_SIZE
    }

    /**
     * Loads the voice once on the worker thread (copying it out of the assets the first time, which can
     * take a few seconds). Safe to call repeatedly and from the main thread; returns immediately.
     */
    fun initialize() {
        if (released || !initStarted.compareAndSet(false, true)) return
        statusValue = TtsStatus.LOADING
        WakeWordState.update(tts = TtsStatus.LOADING)
        try {
            worker.execute { loadEngine() }
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            statusValue = TtsStatus.ERROR
            lastError = ERR_LOAD
        }
    }

    /** Same as [shutdown]; named to match the initialize/speak/stop/release API. */
    fun release() = shutdown()

    // ---- loading ----------------------------------------------------------------------------------

    private fun loadEngine() {
        if (released) return
        val t0 = SystemClock.elapsedRealtime()
        val result = try {
            OfflineTtsEngineFactory.create(app)
        } catch (t: Throwable) {
            OfflineTtsEngineFactory.Result.Failed(t)
        }
        val st = when (result) {
            is OfflineTtsEngineFactory.Result.Ready -> TtsStatus.READY
            OfflineTtsEngineFactory.Result.EngineMissing -> TtsStatus.ENGINE_MISSING
            OfflineTtsEngineFactory.Result.ModelMissing -> TtsStatus.MODEL_MISSING
            is OfflineTtsEngineFactory.Result.Failed -> TtsStatus.ERROR
        }
        lastError = when (result) {
            is OfflineTtsEngineFactory.Result.Ready -> null
            OfflineTtsEngineFactory.Result.EngineMissing -> ERR_ENGINE
            OfflineTtsEngineFactory.Result.ModelMissing -> ERR_MODEL
            is OfflineTtsEngineFactory.Result.Failed -> ERR_LOAD
        }
        when (result) {
            is OfflineTtsEngineFactory.Result.Ready -> {
                if (released) { result.engine.release(); return }
                engine = result.engine
                Log.i(TAG, "Offline Persian TTS ready in ${SystemClock.elapsedRealtime() - t0} ms")
            }
            OfflineTtsEngineFactory.Result.EngineMissing ->
                Log.e(TAG, "sherpa-onnx is not part of this build: JARVIS will stay silent (see INTEGRATION.md)")
            OfflineTtsEngineFactory.Result.ModelMissing ->
                Log.e(TAG, "Persian voice model is not in assets/${TtsModelInstaller.ASSET_DIR}: JARVIS will stay silent (see INTEGRATION.md)")
            is OfflineTtsEngineFactory.Result.Failed ->
                Log.e(TAG, "Offline TTS failed to load", result.error)
        }
        statusValue = st
        main.post { onLoaded(st) }
        if (st == TtsStatus.READY) prewarm()
    }

    private fun onLoaded(st: TtsStatus) {
        if (released) return
        WakeWordState.update(tts = st)
        statusListener?.onTtsStatus(st, lastError)
        val p = pending ?: return
        pending = null
        if (st == TtsStatus.READY) start(p.id, p.text) else failNow(p.id)
    }

    /** Synthesizes the fixed sentences once so the first real answer does not wait for the model. */
    private fun prewarm() {
        for (s in JarvisPhrases.PREWARM) {
            if (released) return
            try { cached(s) } catch (t: Throwable) { Log.w(TAG, "prewarm failed", t); return }
        }
    }

    // ---- JarvisSpeechController ---------------------------------------------------------------------

    override fun speak(text: String, callback: JarvisSpeechController.Callback?) {
        stop()                                          // replaces anything in progress; drops its callback
        if (released) { callback?.let { main.post { it.onDone(false) } }; return }
        this.callback = callback
        val id = generation.get()
        if (!initStarted.get()) initialize()
        when (statusValue) {
            TtsStatus.LOADING -> pending = Pending(id, text)
            TtsStatus.READY -> start(id, text)
            else -> {
                Log.w(TAG, "speak() ignored: ${lastError ?: statusValue}")
                failNow(id)
            }
        }
    }

    override fun stop() {
        generation.incrementAndGet()
        callback = null
        pending = null
        val t = track
        if (t != null) try { t.pause(); t.flush() } catch (e: IllegalStateException) { /* already released */ }
    }

    override fun shutdown() {
        if (released) return
        stop()
        released = true
        statusValue = TtsStatus.OFF
        worker.execute {                                 // queued behind any running synthesis
            try { engine?.release() } catch (t: Throwable) { Log.w(TAG, "release failed", t) }
            engine = null
            synchronized(cache) { cache.clear() }
        }
        worker.shutdown()
        WakeWordState.update(tts = TtsStatus.OFF)
    }

    // ---- speaking ---------------------------------------------------------------------------------------

    private fun failNow(id: Int) {
        main.post { finish(id, false) }
    }

    private fun start(id: Int, text: String) {
        try {
            worker.execute { run(id, text) }
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            failNow(id)
        }
    }

    private fun current(id: Int) = id == generation.get() && !released

    private fun finish(id: Int, success: Boolean) {
        if (id != generation.get()) return               // stale: replaced or stopped
        val cb = callback
        callback = null
        cb?.onDone(success)
    }

    /** Worker thread. */
    private fun run(id: Int, text: String) {
        if (!current(id)) return
        var started = false
        var ok = true
        try {
            val sentences = PersianTtsText.sentences(text).ifEmpty { listOf(text) }
            for ((i, sentence) in sentences.withIndex()) {
                if (!current(id)) return
                val pcm = cached(sentence)
                if (pcm == null) { ok = false; break }
                if (!current(id)) return
                if (!started) {
                    started = true
                    main.post { if (current(id)) callback?.onStart() }
                }
                if (!play(id, pcm, lead = i == 0)) { ok = current(id); break }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "speak failed", t)
            ok = false
        }
        main.post { finish(id, ok && started) }
    }

    /** Synthesizes (or fetches from the cache) one sentence. Worker thread. */
    private fun cached(sentence: String): PcmAudio? {
        synchronized(cache) { cache[sentence] }?.let { return it }
        val e = engine ?: return null
        val t0 = SystemClock.elapsedRealtime()
        val pcm = e.synthesize(PersianTtsText.prepare(sentence), 1.0f) ?: return null
        Log.i(TAG, "synthesized ${sentence.length} chars in ${SystemClock.elapsedRealtime() - t0} ms")
        if (sentence.length <= CACHE_MAX_CHARS) synchronized(cache) { cache[sentence] = pcm }
        return pcm
    }

    /** Plays [pcm] and returns when it has been heard, was stopped, or timed out. False if it did not complete. */
    private fun play(id: Int, pcm: PcmAudio, lead: Boolean): Boolean {
        val rate = pcm.sampleRate
        val minBuf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return false
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuf, rate / 2 * 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track = t
        try {
            if (t.state != AudioTrack.STATE_INITIALIZED) return false
            if (!current(id)) return false
            t.play()
            val data = if (lead) ShortArray(rate * LEAD_SILENCE_MS / 1000) + pcm.samples else pcm.samples
            var off = 0
            while (off < data.size) {
                if (!current(id)) return false
                val w = t.write(data, off, minOf(CHUNK, data.size - off))
                if (w < 0) return false
                off += w
            }
            val deadline = SystemClock.elapsedRealtime() + data.size * 1000L / rate + 1500L
            while (current(id) && t.playbackHeadPosition < data.size && SystemClock.elapsedRealtime() < deadline) {
                Thread.sleep(15)
            }
            return current(id)
        } finally {
            track = null
            try { t.stop() } catch (e: IllegalStateException) { /* ignore */ }
            t.release()
        }
    }

    private companion object {
        const val TAG = "JarvisTts"
        const val CACHE_SIZE = 16
        const val CACHE_MAX_CHARS = 60
        const val CHUNK = 2048
        const val ERR_MODEL = "Offline Persian TTS model is missing"
        const val ERR_ENGINE = "Offline Persian TTS engine (sherpa-onnx) is not part of this build"
        const val ERR_LOAD = "Offline Persian TTS failed to load"
        const val LEAD_SILENCE_MS = 60
    }
}
