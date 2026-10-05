package com.jarvis.assistant.wakeword

import android.Manifest
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.FileNotFoundException

/**
 * Offline wake word detector («هی جارویس») on top of Vosk. Matching is spelling/spacing tolerant (see [WakePhrase]).
 *
 * Guarantees:
 *  - At most one capture thread / microphone at a time. [start] is idempotent; a new session first
 *    waits for the previous one to release the microphone.
 *  - [stop] releases the microphone (the thread closes AudioRecord + Recognizer in `finally`).
 *  - [release] additionally frees the Vosk model. The engine must not be used afterwards.
 *  - Callbacks are delivered on the main thread and never after [stop]/[release] for a stale session.
 *
 * The Vosk model is expected in assets/[ASSET_DIR] (see INTEGRATION.md); it is copied once to
 * internal storage because Vosk needs a real directory.
 */
class VoskWakeWordEngine(context: Context, private val callback: Callback) {

    interface Callback {
        /** The wake phrase was detected. The engine has already stopped (microphone released). */
        fun onWakeWord()
        /** LOADING_MODEL / LISTENING, or a terminal problem: NO_PERMISSION / MODEL_MISSING / ERROR. */
        fun onStatus(status: WakeStatus)
    }

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val debug = (app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    private val lock = Any()
    private var lastSession: Session? = null          // guarded by lock
    private val modelLock = Any()
    private var model: Model? = null                  // guarded by modelLock
    private var grammar: WakePhrase.Grammar? = null   // built once per loaded model; guarded by modelLock
    @Volatile private var released = false

    /** True once a wake word was delivered; reset only by the next explicit [start]. */
    private val activationDelivered = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Starts listening. No-op if a session is already running. */
    fun start() {
        synchronized(lock) {
            if (released) return
            val cur = lastSession
            if (cur != null && !cur.stopped) return
            activationDelivered.set(false)
            val s = Session(cur)
            lastSession = s
            s.start()
        }
    }

    /** Stops listening and releases the microphone (asynchronously, within ~150 ms). */
    fun stop() {
        synchronized(lock) { lastSession?.stopped = true }
    }

    fun release() {
        released = true
        val alive: Boolean
        synchronized(lock) {
            lastSession?.stopped = true
            alive = lastSession?.isAlive == true
        }
        if (!alive) closeModel()                       // otherwise the session thread closes it
    }

    // ---------------------------------------------------------------------------------------------

    private inner class Session(private var previous: Session?) : Thread("JarvisWakeWord") {
        @Volatile var stopped = false

        override fun run() {
            try {
                try { previous?.join() } catch (e: InterruptedException) { return }
                previous = null
                if (stopped || released) return
                capture(this)
            } catch (t: Throwable) {
                Log.e(TAG, "Wake word session crashed", t)
                emit(this, WakeStatus.ERROR)
            } finally {
                if (released) closeModel()
            }
        }
    }

    private fun capture(s: Session) {
        if (app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            emit(s, WakeStatus.NO_PERMISSION); return
        }
        if (model == null) emit(s, WakeStatus.LOADING_MODEL)
        val m = obtainModel(s) ?: return
        if (s.stopped || released) return
        val g = synchronized(modelLock) { grammar } ?: WakePhrase.build(null)

        var recognizer: Recognizer? = null
        var audio: AudioRecord? = null
        try {
            recognizer = if (g.json != null) Recognizer(m, SAMPLE_RATE.toFloat(), g.json)
                         else Recognizer(m, SAMPLE_RATE.toFloat())
            recognizer.setWords(true)
            audio = createAudioRecord()
            if (audio == null) { emit(s, WakeStatus.ERROR); return }
            audio.startRecording()
            if (audio.recordingState != AudioRecord.RECORDSTATE_RECORDING) { emit(s, WakeStatus.ERROR); return }
            emit(s, WakeStatus.LISTENING)

            val buf = ShortArray(CHUNK_SAMPLES)
            var sumSq = 0.0                             // energy of the audio since the last final result
            var count = 0L
            var peakRms = 0.0                           // loudest chunk of the current utterance
            var partialHits = 0                         // consecutive chunks whose partial IS the phrase
            var lastPartial = ""
            while (!s.stopped && !released) {
                val n = audio.read(buf, 0, buf.size)
                if (n < 0) { emit(s, WakeStatus.ERROR); return }
                if (n == 0) continue
                var chunkSq = 0.0
                for (i in 0 until n) { val v = buf[i].toDouble(); chunkSq += v * v }
                sumSq += chunkSq
                count += n
                peakRms = maxOf(peakRms, Math.sqrt(chunkSq / n))

                if (recognizer.acceptWaveForm(buf, n)) {
                    // FINAL result: the safety net (also catches the phrase when partials flickered).
                    val rms = if (count > 0) Math.sqrt(sumSq / count) else 0.0
                    val loud = maxOf(rms, peakRms)
                    sumSq = 0.0; count = 0; peakRms = 0.0; partialHits = 0; lastPartial = ""
                    if (isWakeFinal(g, recognizer.result, loud)) { deliver(s); return }
                } else {
                    // Partial results are only READ (never restart the recognizer). The phrase must show up in two
                    // consecutive chunks (~250 ms) so a single flickering hypothesis cannot trigger JARVIS, but the
                    // user does not have to wait for Vosk's end-of-speech silence either.
                    val partial = try { JSONObject(recognizer.partialResult).optString("partial") } catch (e: Exception) { "" }
                    if (partial.isNotEmpty() && WakePhrase.matches(partial)) {
                        partialHits = if (partial == lastPartial || partialHits == 0) partialHits + 1 else 1
                        lastPartial = partial
                        val rms = if (count > 0) Math.sqrt(sumSq / count) else 0.0
                        if (partialHits >= PARTIAL_CONFIRM_CHUNKS && maxOf(rms, peakRms) >= WakePhrase.MIN_UTTERANCE_RMS) {
                            if (debug) { Log.d(TAG, "wake ACCEPT on partial"); WakeWordState.update(heard = partial) }
                            deliver(s); return
                        }
                    } else {
                        partialHits = 0; lastPartial = ""
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Capture failed", t)
            emit(s, WakeStatus.ERROR)
        } finally {
            try { audio?.stop() } catch (e: IllegalStateException) { /* not started */ }
            audio?.release()
            try { recognizer?.close() } catch (e: Throwable) { /* ignore */ }
        }
    }

    /** Exactly one activation per engine run, even if something else re-enters here. The mic is released in finally. */
    private fun deliver(s: Session) {
        s.stopped = true
        if (activationDelivered.compareAndSet(false, true)) {
            main.post { if (!released) callback.onWakeWord() }
        }
    }

    private fun isWakeFinal(g: WakePhrase.Grammar, json: String, rms: Double): Boolean {
        val verdict = g.evaluateFinal(json).let { v ->
            if (v.accepted) WakePhrase.energyVerdict(rms).let { e -> if (e.accepted) v else e } else v
        }
        if (debug) {
            val heard = try { JSONObject(json).optString("text") } catch (e: Exception) { "" }
            if (heard.isNotBlank()) WakeWordState.update(heard = heard)
            Log.d(TAG, "wake candidate \"$heard\" -> ${if (verdict.accepted) "ACCEPT" else "reject"} (${verdict.reason})")
        }
        return verdict.accepted
    }

    private fun emit(s: Session, status: WakeStatus) {
        if (s.stopped || released) return
        main.post { if (!s.stopped && !released) callback.onStatus(status) }
    }

    private fun createAudioRecord(): AudioRecord? {
        val min = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (min <= 0) return null
        val size = maxOf(min * 2, SAMPLE_RATE)         // bytes; >= 0.5 s
        for (source in intArrayOf(MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC)) {
            try {
                val r = AudioRecord(
                    source, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size
                )
                if (r.state == AudioRecord.STATE_INITIALIZED) return r
                r.release()
            } catch (e: SecurityException) {
                Log.w(TAG, "AudioRecord denied", e)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "AudioRecord bad args", e)
            }
        }
        return null
    }

    // ---- model --------------------------------------------------------------------------------

    private fun obtainModel(s: Session): Model? {
        synchronized(modelLock) { model?.let { return it } }
        try {
            val dir = File(app.filesDir, "vosk/$ASSET_DIR")
            val marker = File(dir, ".ok")
            val stamp = installStamp()
            if (!marker.exists() || marker.readText() != stamp) {
                if (!assetModelPresent()) { emit(s, WakeStatus.MODEL_MISSING); return null }
                dir.deleteRecursively()
                copyAsset(ASSET_DIR, dir)
                marker.writeText(stamp)
            }
            if (s.stopped || released) return null
            LibVosk.setLogLevel(LogLevel.WARNINGS)
            val m = Model(dir.absolutePath)
            val g = WakePhrase.build(readVocabulary(dir))
            Log.i(TAG, "Wake grammar: ${g.json ?: "free recognition (model lacks the phrase words)"}")
            synchronized(modelLock) {
                if (released) { m.close(); return null }
                model = m
                grammar = g
            }
            return m
        } catch (t: Throwable) {
            Log.e(TAG, "Could not load Vosk model", t)
            emit(s, WakeStatus.ERROR)
            return null
        }
    }

    private fun closeModel() {
        synchronized(modelLock) {
            try { model?.close() } catch (e: Throwable) { /* ignore */ }
            model = null
            grammar = null
        }
    }

    /**
     * The subset of [WakePhrase.candidateWords] present in the model's word list (graph/words.txt,
     * one "word id" per line). Null if the list cannot be read; the grammar then assumes all exist.
     */
    private fun readVocabulary(modelDir: File): Set<String>? {
        val f = File(modelDir, "graph/words.txt")
        if (!f.isFile) return null
        val wanted = WakePhrase.candidateWords()
        val found = HashSet<String>()
        return try {
            f.bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (line in lines) {
                    val sp = line.indexOf(' ')
                    val w = WakePhrase.normalize(if (sp > 0) line.substring(0, sp) else line)
                    if (w in wanted) found += w
                }
            }
            found
        } catch (e: Exception) {
            Log.w(TAG, "Could not read model vocabulary", e)
            null
        }
    }

    /** A model counts as present when assets/model-fa/am/final.mdl exists. */
    private fun assetModelPresent(): Boolean =
        app.assets.list("$ASSET_DIR/am")?.contains("final.mdl") == true

    /** Changes with every app update, so a replaced model is re-copied. */
    private fun installStamp(): String = try {
        app.packageManager.getPackageInfo(app.packageName, 0).lastUpdateTime.toString()
    } catch (e: Exception) { "0" }

    private fun copyAsset(path: String, dest: File) {
        val children = app.assets.list(path) ?: emptyArray()
        if (children.isNotEmpty()) {
            dest.mkdirs()
            for (name in children) copyAsset("$path/$name", File(dest, name))
            return
        }
        try {
            dest.parentFile?.mkdirs()
            app.assets.open(path).use { input -> dest.outputStream().use { input.copyTo(it) } }
        } catch (e: FileNotFoundException) {
            dest.mkdirs()                              // empty directory
        }
    }

    companion object {
        private const val TAG = "VoskWakeWord"

        /** Folder inside app/src/main/assets that holds the unpacked Vosk Persian model. */
        const val ASSET_DIR = "model-fa"

        private const val SAMPLE_RATE = 16000
        private const val CHUNK_SAMPLES = 2048          // ~128 ms
        private const val PARTIAL_CONFIRM_CHUNKS = 2
    }
}
