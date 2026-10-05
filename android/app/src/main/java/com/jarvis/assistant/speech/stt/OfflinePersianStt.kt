package com.jarvis.assistant.speech.stt

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.jarvis.assistant.nlu.PersianNormalizer
import com.jarvis.assistant.speech.CommandSpeechError
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Fully offline Persian speech-to-text for the command phase. No network, no Android SpeechRecognizer.
 *
 *   startListening -> AudioRecord (16 kHz mono) -> energy endpointing -> mic released -> offline decode -> text
 *
 *  - ONE model, loaded once on a background thread when this object is created ([OfflineSttEngineFactory]).
 *  - All capture and decoding happens on one worker thread; callbacks arrive on the main thread.
 *  - The microphone is released BEFORE decoding starts, on every path (result, silence, error, cancel), so
 *    the Vosk wake word can take it back as soon as the command phase ends. [stopListening] waits (<= 250 ms)
 *    until the worker has actually closed the AudioRecord.
 *  - Silence: [NO_SPEECH_MS] after the start without speech -> [CommandSpeechError.NO_SPEECH].
 *  - Microphone busy: [OPEN_ATTEMPTS] tries, then [CommandSpeechError.BUSY]; never an endless loop.
 *  - Model/engine missing: [CommandSpeechError.MODEL_MISSING] (clear log line, no crash, no online fallback).
 *
 * The raw text is normalized with [PersianNormalizer] (the same normalizer the command parser uses).
 * Main thread API only. The caller guarantees no other microphone consumer (Vosk) is running.
 */
class OfflinePersianStt(context: Context) {

    interface Listener {
        /** The microphone is open and capturing. */
        fun onListeningStarted() {}
        /** Voice level 0..1, for the reactor. */
        fun onVoiceLevel(level: Float) {}
        /** Final Persian text (normalized, never blank). Followed by [onListeningStopped]. */
        fun onResult(text: String) {}
        fun onError(error: CommandSpeechError) {}
        /** Exactly once per finished session (result or error), not after [stopListening]. */
        fun onListeningStopped() {}
    }

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "JarvisStt").apply { isDaemon = true }
    }

    @Volatile private var listener: Listener? = null
    @Volatile private var session = 0                   // bumped by start/stop: invalidates a running session
    private var active = false                          // main thread
    private var captureDone: CountDownLatch? = null     // counted down when the AudioRecord is closed

    // Written once by the loader task on the worker thread, read by session tasks on the same thread.
    private var engine: OfflineSttEngine? = null
    private var loadError: CommandSpeechError? = null
    private var released = false

    private val watchdog = Runnable {
        Log.w(TAG, "STT session watchdog fired")
        finishWithError(session, CommandSpeechError.NO_SPEECH)
    }

    init {
        // Queued first on the single worker thread, so every session runs after the model finished loading.
        try { executor.execute { loadEngine() } } catch (e: RejectedExecutionException) { /* not possible yet */ }
    }

    fun setListener(l: Listener?) { listener = l }

    val isListening: Boolean get() = active

    // ---- public API (main thread) -----------------------------------------------------------------

    fun startListening() {
        if (active || released) return
        if (app.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            main.post { listener?.onError(CommandSpeechError.NO_PERMISSION); listener?.onListeningStopped() }
            return
        }
        val id = ++session
        active = true
        val latch = CountDownLatch(1)
        captureDone = latch
        main.removeCallbacks(watchdog)
        main.postDelayed(watchdog, WATCHDOG_MS)
        try {
            executor.execute { runSession(id, latch) }
        } catch (e: RejectedExecutionException) {
            latch.countDown()
            finishWithError(id, CommandSpeechError.OTHER)
        }
    }

    /** Aborts silently (no callbacks) and makes sure the microphone is closed when this returns. */
    fun stopListening() {
        if (!active) return
        session++                                        // the worker notices within one 20 ms read
        active = false
        main.removeCallbacks(watchdog)
        try { captureDone?.await(RELEASE_WAIT_MS, TimeUnit.MILLISECONDS) } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /** Stops and frees the model (after pending work). The object must not be used afterwards. */
    fun release() {
        stopListening()
        listener = null
        released = true
        try {
            executor.execute {
                try { engine?.release() } catch (t: Throwable) { Log.w(TAG, "engine release failed", t) }
                engine = null
            }
        } catch (e: RejectedExecutionException) { /* already shut down */ }
        executor.shutdown()
    }

    // ---- model --------------------------------------------------------------------------------------

    private fun loadEngine() {
        val t0 = SystemClock.elapsedRealtime()
        when (val r = OfflineSttEngineFactory.create(app)) {
            is OfflineSttEngineFactory.Result.Ready -> {
                engine = r.engine
                Log.i(TAG, "Persian STT model loaded in ${SystemClock.elapsedRealtime() - t0} ms")
            }
            OfflineSttEngineFactory.Result.EngineMissing -> {
                loadError = CommandSpeechError.MODEL_MISSING
                Log.e(TAG, "Persian STT unavailable: sherpa-onnx ASR API is not part of this build. " +
                    "Run tools/install-persian-stt.sh and rebuild.")
            }
            OfflineSttEngineFactory.Result.ModelMissing -> {
                loadError = CommandSpeechError.MODEL_MISSING
                Log.e(TAG, "Persian STT unavailable: model files not found in assets/${SttModelInstaller.ASSET_DIR}/ " +
                    "(model.onnx, tokens.txt). Run tools/install-persian-stt.sh and rebuild.")
            }
            is OfflineSttEngineFactory.Result.Failed -> {
                loadError = CommandSpeechError.OTHER
                Log.e(TAG, "Persian STT model failed to load", r.error)
            }
        }
    }

    // ---- one session (worker thread) ----------------------------------------------------------------

    private fun runSession(id: Int, latch: CountDownLatch) {
        var rec: AudioRecord? = null
        try {
            if (session != id) return
            val eng = engine
            if (eng == null) {
                latch.countDown()
                finishWithError(id, loadError ?: CommandSpeechError.MODEL_MISSING)
                return
            }

            val opened = openAudioRecord(id)
            if (session != id) { opened.record?.let { closeQuietly(it) }; return }
            rec = opened.record
            if (rec == null) {
                latch.countDown()
                finishWithError(id, opened.error ?: CommandSpeechError.AUDIO)
                return
            }

            val captured = capture(id, rec)
            closeQuietly(rec)                            // microphone is free from here on, before decoding
            rec = null
            latch.countDown()
            if (session != id) return

            when (captured) {
                is Capture.Failed -> finishWithError(id, captured.error)
                is Capture.Speech -> decode(id, eng, captured)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "STT session failed", t)
            rec?.let { closeQuietly(it) }
            latch.countDown()
            finishWithError(id, CommandSpeechError.OTHER)
        } finally {
            latch.countDown()
        }
    }

    private class Opened(val record: AudioRecord?, val error: CommandSpeechError?)

    /** Opens and starts the microphone; a limited number of attempts when it is busy. */
    private fun openAudioRecord(id: Int): Opened {
        val min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return Opened(null, CommandSpeechError.AUDIO)
        val size = max(min * 2, SAMPLE_RATE)             // bytes, >= 0.5 s
        var denied = false
        for (attempt in 1..OPEN_ATTEMPTS) {
            if (session != id) return Opened(null, null)
            for (source in intArrayOf(MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC)) {
                var r: AudioRecord? = null
                try {
                    r = AudioRecord(source, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size)
                    if (r.state == AudioRecord.STATE_INITIALIZED) {
                        r.startRecording()
                        if (r.recordingState == AudioRecord.RECORDSTATE_RECORDING) return Opened(r, null)
                    }
                } catch (e: SecurityException) {
                    denied = true
                } catch (e: IllegalArgumentException) {
                    Log.w(TAG, "AudioRecord bad args", e)
                } catch (e: IllegalStateException) {
                    Log.w(TAG, "AudioRecord start failed", e)
                }
                r?.let { closeQuietly(it) }
            }
            if (denied) return Opened(null, CommandSpeechError.NO_PERMISSION)
            Log.w(TAG, "Microphone busy (attempt $attempt/$OPEN_ATTEMPTS)")
            if (attempt < OPEN_ATTEMPTS) {
                try { Thread.sleep(OPEN_RETRY_MS) } catch (e: InterruptedException) { Thread.currentThread().interrupt(); break }
            }
        }
        return Opened(null, CommandSpeechError.BUSY)
    }

    private sealed class Capture {
        class Speech(val pcm: ShortArray, val from: Int, val to: Int) : Capture()
        class Failed(val error: CommandSpeechError) : Capture()
    }

    /**
     * Reads 20 ms chunks until the speaker has finished (trailing silence), the utterance is too long,
     * nothing was said for [NO_SPEECH_MS], the session was cancelled or the microphone failed.
     * Energy based: threshold = max(floor, noise floor x [NOISE_RATIO]); the noise floor adapts while silent.
     */
    private fun capture(id: Int, rec: AudioRecord): Capture {
        main.post { if (id == session && active) listener?.onListeningStarted() }
        val chunk = ShortArray(CHUNK)
        val buf = ShortArray(MAX_SAMPLES)
        var n = 0
        var noise = INITIAL_NOISE
        var started = false
        var loudRun = 0
        var speechStart = 0
        var lastLoud = 0
        var levelTick = 0
        var emptyReads = 0
        val t0 = SystemClock.elapsedRealtime()

        while (true) {
            if (session != id) return Capture.Failed(CommandSpeechError.OTHER)     // cancelled: caller ignores it
            val r = rec.read(chunk, 0, CHUNK)
            if (r < 0) {
                Log.w(TAG, "AudioRecord.read failed: $r")
                return Capture.Failed(CommandSpeechError.AUDIO)
            }
            if (r == 0) {
                if (++emptyReads > MAX_EMPTY_READS) return Capture.Failed(CommandSpeechError.AUDIO)
                continue
            }
            emptyReads = 0
            if (n + r > MAX_SAMPLES) {
                return if (started) speech(buf, n, speechStart, lastLoud) else Capture.Failed(CommandSpeechError.NO_SPEECH)
            }
            System.arraycopy(chunk, 0, buf, n, r)
            val chunkStart = n
            n += r

            var sum = 0.0
            for (i in 0 until r) { val v = chunk[i] / 32768.0; sum += v * v }
            val rms = sqrt(sum / r).toFloat()
            if (++levelTick % 2 == 0) {
                val db = 20f * log10(rms + 1e-6f)
                val level = ((db + 55f) / 40f).coerceIn(0f, 1f)
                main.post { if (id == session && active) listener?.onVoiceLevel(level) }
            }

            val thr = max(MIN_THRESHOLD, noise * NOISE_RATIO)
            if (!started) {
                if (rms > thr) {
                    loudRun++
                    if (loudRun >= START_CHUNKS) {
                        started = true
                        speechStart = max(0, chunkStart - (loudRun - 1) * CHUNK)
                        lastLoud = n
                    }
                } else {
                    loudRun = 0
                    noise = (noise * 0.97f + rms * 0.03f).coerceAtMost(MAX_NOISE)
                }
                if (!started && SystemClock.elapsedRealtime() - t0 >= NO_SPEECH_MS) {
                    return Capture.Failed(CommandSpeechError.NO_SPEECH)
                }
            } else {
                if (rms > thr * HYSTERESIS) lastLoud = n
                if (n - lastLoud >= END_SILENCE_SAMPLES || n - speechStart >= MAX_UTTERANCE_SAMPLES) {
                    return speech(buf, n, speechStart, lastLoud)
                }
            }
        }
    }

    private fun speech(buf: ShortArray, n: Int, speechStart: Int, lastLoud: Int): Capture {
        val from = max(0, speechStart - PRE_ROLL_SAMPLES)
        val to = minOf(n, lastLoud + TAIL_SAMPLES)
        return if (to - from < MIN_SPEECH_SAMPLES) Capture.Failed(CommandSpeechError.NO_SPEECH)
        else Capture.Speech(buf, from, to)
    }

    private fun decode(id: Int, eng: OfflineSttEngine, c: Capture.Speech) {
        val len = c.to - c.from
        val samples = FloatArray(len)
        var peak = 0f
        for (i in 0 until len) {
            val v = c.pcm[c.from + i] / 32768f
            samples[i] = v
            val a = abs(v)
            if (a > peak) peak = a
        }
        // Quiet microphones: raise very low recordings (never amplify noise-only audio beyond x8).
        if (peak in 0.005f..QUIET_PEAK) {
            val gain = minOf(TARGET_PEAK / peak, MAX_GAIN)
            for (i in samples.indices) samples[i] *= gain
        }
        val t0 = SystemClock.elapsedRealtime()
        val raw = eng.transcribe(samples, SAMPLE_RATE)
        Log.i(TAG, "Decoded ${len * 1000L / SAMPLE_RATE} ms of audio in ${SystemClock.elapsedRealtime() - t0} ms")   // text is never logged
        if (session != id) return
        val text = normalize(raw)
        if (text.isBlank()) finishWithError(id, CommandSpeechError.NO_MATCH) else finishWithText(id, text)
    }

    // ---- results (any thread -> main) ----------------------------------------------------------------

    private fun finishWithText(id: Int, text: String) {
        main.post {
            if (id != session || !active) return@post
            active = false
            main.removeCallbacks(watchdog)
            val l = listener
            l?.onResult(text)
            l?.onListeningStopped()
        }
    }

    private fun finishWithError(id: Int, error: CommandSpeechError) {
        main.post {
            if (id != session || !active) return@post
            active = false
            session++                                    // a still-running worker must stop
            main.removeCallbacks(watchdog)
            val l = listener
            l?.onError(error)
            l?.onListeningStopped()
        }
    }

    private fun closeQuietly(r: AudioRecord) {
        try { if (r.recordingState == AudioRecord.RECORDSTATE_RECORDING) r.stop() } catch (t: Throwable) { /* ignore */ }
        try { r.release() } catch (t: Throwable) { /* ignore */ }
    }

    companion object {
        private const val TAG = "JarvisStt"

        /** Same normalizer the command parser uses (ي/ك, half-space, punctuation, digits); meaning is unchanged. */
        fun normalize(raw: String?): String = if (raw.isNullOrBlank()) "" else PersianNormalizer.normalize(raw)

        private const val SAMPLE_RATE = 16_000
        private const val CHUNK = 320                               // 20 ms

        // Timing
        private const val NO_SPEECH_MS = 7_000L                     // nothing said after "بله ارباب."
        private const val WATCHDOG_MS = 30_000L
        private const val RELEASE_WAIT_MS = 250L
        private const val OPEN_ATTEMPTS = 3
        private const val OPEN_RETRY_MS = 250L
        private const val MAX_EMPTY_READS = 50

        // Endpointing
        private const val START_CHUNKS = 3                          // 60 ms above threshold = speech started
        private const val END_SILENCE_SAMPLES = SAMPLE_RATE * 8 / 10    // 800 ms of silence ends the utterance
        private const val MAX_UTTERANCE_SAMPLES = SAMPLE_RATE * 12
        private const val PRE_ROLL_SAMPLES = SAMPLE_RATE * 3 / 10
        private const val TAIL_SAMPLES = SAMPLE_RATE * 2 / 10
        private const val MIN_SPEECH_SAMPLES = SAMPLE_RATE / 4      // < 250 ms is a click, not a command
        private const val MAX_SAMPLES = SAMPLE_RATE * 20            // 7 s wait + 12 s speech + slack (640 KB)

        // Energy thresholds (RMS of samples in -1..1)
        private const val MIN_THRESHOLD = 0.012f
        private const val INITIAL_NOISE = 0.004f
        private const val MAX_NOISE = 0.05f
        private const val NOISE_RATIO = 2.5f
        private const val HYSTERESIS = 0.6f

        // Gain for very quiet recordings
        private const val QUIET_PEAK = 0.2f
        private const val TARGET_PEAK = 0.5f
        private const val MAX_GAIN = 8f
    }
}
