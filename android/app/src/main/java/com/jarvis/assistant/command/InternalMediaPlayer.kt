package com.jarvis.assistant.command

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * JARVIS' own player for files that are on the phone. Audio plays here with [MediaPlayer]; video plays in
 * [InternalVideoActivity]. Nothing is handed to another app. Main thread only.
 *
 * Success is reported ONLY after the player really started (audio: onPrepared + start(); video: the VideoView
 * prepared and started). Any error, or no start within [START_TIMEOUT_MS], is reported as a failure.
 */
object InternalMediaPlayer {

    private const val TAG = "InternalMediaPlayer"
    private const val START_TIMEOUT_MS = 8_000L

    private val main = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var pending: ((Boolean) -> Unit)? = null
    private var timeout: Runnable? = null
    private var focusRequest: AudioFocusRequest? = null
    private var audioManager: AudioManager? = null

    /** Plays an audio file. [onResult](true) only when playback has really started. */
    fun playAudio(app: Context, uri: Uri, onResult: (Boolean) -> Unit) {
        stop()
        InternalVideoActivity.closeCurrent()
        pending = onResult
        val mp = MediaPlayer()
        player = mp
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            mp.setWakeMode(app.applicationContext, android.os.PowerManager.PARTIAL_WAKE_LOCK)
            mp.setDataSource(app.applicationContext, uri)
            mp.setOnPreparedListener { p ->
                if (player !== p) return@setOnPreparedListener
                try {
                    requestFocus(app)
                    p.start()
                    report(p.isPlaying)
                } catch (e: RuntimeException) {
                    Log.w(TAG, "start failed", e)
                    report(false)
                }
            }
            mp.setOnErrorListener { p, _, _ ->
                if (player === p) { report(false); stop() }
                true
            }
            mp.setOnCompletionListener { p -> if (player === p) stop() }
            armTimeout()
            mp.prepareAsync()
        } catch (e: Exception) {
            Log.w(TAG, "Cannot play audio", e)
            report(false)
            stop()
        }
    }

    /** Opens [InternalVideoActivity]; it calls [videoStarted] / [videoFailed]. */
    fun playVideo(app: Context, uri: Uri, title: String, onResult: (Boolean) -> Unit) {
        stop()
        pending = onResult
        try {
            app.startActivity(
                Intent(app, InternalVideoActivity::class.java)
                    .setData(uri)
                    .putExtra(InternalVideoActivity.EXTRA_TITLE, title)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            armTimeout()
        } catch (e: RuntimeException) {
            Log.w(TAG, "Cannot open video screen", e)
            report(false)
        }
    }

    internal fun videoStarted() = report(true)
    internal fun videoFailed() = report(false)

    /** Stops the audio player AND closes the video screen (the "قطع کن" command). */
    fun stopAll(app: Context) {
        stop()
        InternalVideoActivity.closeCurrent()
    }

    private fun requestFocus(app: Context) {
        try {
            val am = app.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            audioManager = am
            if (Build.VERSION.SDK_INT >= 26) {
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setOnAudioFocusChangeListener { }
                    .build()
                focusRequest = req
                am.requestAudioFocus(req)
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "audio focus failed", e)
        }
    }

    private fun abandonFocus() {
        try {
            val req = focusRequest
            focusRequest = null
            if (req != null && Build.VERSION.SDK_INT >= 26) audioManager?.abandonAudioFocusRequest(req)
        } catch (e: RuntimeException) { /* ignore */ }
    }

    fun stop() {
        abandonFocus()
        timeout?.let { main.removeCallbacks(it) }
        timeout = null
        val mp = player
        player = null
        if (mp != null) {
            try { mp.setOnPreparedListener(null); mp.setOnErrorListener(null); mp.stop() } catch (e: RuntimeException) { /* not started */ }
            try { mp.release() } catch (e: RuntimeException) { /* ignore */ }
        }
    }

    private fun armTimeout() {
        timeout?.let { main.removeCallbacks(it) }
        val t = Runnable { report(false); stop() }
        timeout = t
        main.postDelayed(t, START_TIMEOUT_MS)
    }

    /** Delivers the result exactly once. */
    private fun report(ok: Boolean) {
        timeout?.let { main.removeCallbacks(it) }
        timeout = null
        val cb = pending
        pending = null
        cb?.invoke(ok)
    }
}
