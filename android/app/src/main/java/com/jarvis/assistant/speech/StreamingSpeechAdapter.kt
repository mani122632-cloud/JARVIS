package com.jarvis.assistant.speech

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/**
 * Small adapter that lets a reply be spoken while it is still being written. It sits on top of the EXISTING
 * [JarvisSpeechController] (Gyro / offline Persian TTS) and changes nothing in it: segments are queued and spoken
 * strictly one after another, the next one only starts when the previous one reported done. So there is never more
 * than one utterance at a time (no overlapping voices) and a segment is never spoken twice.
 *
 *   open(listener) -> enqueue(segment)* -> close()  => listener.onDrained() once everything was spoken
 *   cancel()                                         => silence at once, no callback
 *
 * The first segment starts playing as soon as it is enqueued ([Listener.onFirstAudio] when the audio really starts).
 * Timeouts per segment are the same as for a normal spoken reply. If speech does not work at all (the first
 * segment fails or times out) the rest is dropped and the stream still ends with [Listener.onDrained], so the
 * conversation never waits on a dead TTS. Main thread only.
 */
class StreamingSpeechAdapter(
    private val tts: JarvisSpeechController,
    private val main: Handler = Handler(Looper.getMainLooper())
) {
    interface Listener {
        /** The first segment's audio started. */
        fun onFirstAudio()

        /** Everything queued was spoken (or speech was given up). Called once; never after [cancel]. */
        fun onDrained(success: Boolean)
    }

    private val queue = ArrayDeque<String>()
    private val token = Any()
    private var listener: Listener? = null
    private var epoch = 0
    private var active = false
    private var closed = false
    private var speaking = false
    private var audioStarted = false
    private var failed = false
    private var allOk = true
    private var lastEnqueued: String? = null

    /** Starts a new stream; a stream that is still active is cancelled silently first. */
    fun open(l: Listener) {
        cancel()
        epoch++
        listener = l
        active = true
        closed = false
        speaking = false
        audioStarted = false
        failed = false
        allOk = true
        lastEnqueued = null
        queue.clear()
    }

    val isOpen: Boolean get() = active

    /** Queues [segment]; an empty one, or one identical to the previous segment, is ignored. */
    fun enqueue(segment: String) {
        if (!active || closed || failed) return
        val t = segment.trim()
        if (t.isEmpty() || t == lastEnqueued) return
        lastEnqueued = t
        queue.addLast(t)
        pump()
    }

    /** No more segments will come: [Listener.onDrained] follows once the queue has been spoken. */
    fun close() {
        if (!active || closed) return
        closed = true
        pump()
    }

    /** Drops everything and silences the voice. No callback. */
    fun cancel() {
        val wasSpeaking = active && speaking
        epoch++
        active = false
        closed = false
        speaking = false
        listener = null
        queue.clear()
        main.removeCallbacksAndMessages(token)
        if (wasSpeaking) {
            try { tts.stop() } catch (t: Throwable) { Log.w(TAG, "tts.stop failed", t) }
        }
    }

    private fun pump() {
        if (!active || speaking) return
        val next = queue.removeFirstOrNull()
        if (next == null) {
            if (closed) complete()
            return
        }
        speak(next)
    }

    private fun speak(segment: String) {
        speaking = true
        val my = epoch
        val startTimeout = Runnable { if (my == epoch && speaking) giveUp("TTS did not start") }
        val maxTimeout = Runnable { if (my == epoch && speaking) giveUp("TTS took too long") }
        val maxSpeak = (SPEAK_BASE_MS + segment.length * SPEAK_PER_CHAR_MS).coerceAtMost(SPEAK_MAX_MS)
        val now = SystemClock.uptimeMillis()
        main.postAtTime(startTimeout, token, now + SPEAK_START_TIMEOUT_MS)
        main.postAtTime(maxTimeout, token, now + maxSpeak)
        try {
            tts.speak(segment, object : JarvisSpeechController.Callback {
                override fun onStart() {
                    if (my != epoch || !speaking) return
                    main.removeCallbacks(startTimeout)
                    if (!audioStarted) {
                        audioStarted = true
                        listener?.onFirstAudio()
                    }
                }

                override fun onDone(success: Boolean) {
                    if (my != epoch || !speaking) return
                    segmentDone(success)
                }
            })
        } catch (t: Throwable) {
            Log.e(TAG, "tts.speak threw", t)
            segmentDone(false)
        }
    }

    private fun segmentDone(success: Boolean) {
        main.removeCallbacksAndMessages(token)
        speaking = false
        if (!success) {
            allOk = false
            if (!audioStarted) dropRest()                 // speech does not work at all
        }
        pump()
    }

    /** A segment hung: cut it and stop speaking this reply. */
    private fun giveUp(reason: String) {
        Log.w(TAG, "$reason; giving up the rest of this reply")
        main.removeCallbacksAndMessages(token)
        try { tts.stop() } catch (t: Throwable) { Log.w(TAG, "tts.stop failed", t) }
        speaking = false
        allOk = false
        dropRest()
        pump()
    }

    private fun dropRest() {
        failed = true
        queue.clear()
    }

    private fun complete() {
        val l = listener
        active = false
        listener = null
        l?.onDrained(allOk)
    }

    private companion object {
        const val TAG = "JarvisStreamTts"

        // Same limits as a normal spoken reply in JarvisConversationController.
        const val SPEAK_START_TIMEOUT_MS = 12_000L
        const val SPEAK_BASE_MS = 14_000L
        const val SPEAK_PER_CHAR_MS = 90L
        const val SPEAK_MAX_MS = 40_000L
    }
}
