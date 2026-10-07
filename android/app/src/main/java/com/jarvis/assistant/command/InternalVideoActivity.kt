package com.jarvis.assistant.command

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.MediaController
import android.widget.VideoView

/** Full-screen video of a local file, played inside JARVIS (VideoView). Reports the real start / failure. */
class InternalVideoActivity : Activity() {

    private var video: VideoView? = null
    private var reported = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Only one JARVIS video screen at a time: a new command replaces the previous video.
        val old = current?.get()
        if (old != null && old !== this) try { old.finish() } catch (e: RuntimeException) { /* ignore */ }
        current = java.lang.ref.WeakReference(this)
        val uri = intent?.data
        if (uri == null) { fail(); finish(); return }
        val root = FrameLayout(this).apply { setBackgroundColor(0xFF000000.toInt()) }
        val v = VideoView(this)
        root.addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        setContentView(root)
        video = v
        val controller = MediaController(this)
        controller.setAnchorView(v)
        v.setMediaController(controller)
        v.setOnPreparedListener { mp ->
            try {
                v.start()
                if (v.isPlaying || mp.isPlaying) ok() else fail()
            } catch (e: RuntimeException) { fail() }
        }
        v.setOnErrorListener { _, _, _ -> fail(); finish(); true }
        v.setOnCompletionListener { finish() }
        try { v.setVideoURI(uri) } catch (e: RuntimeException) { fail(); finish() }
    }

    private fun ok() { if (!reported) { reported = true; InternalMediaPlayer.videoStarted() } }
    private fun fail() { if (!reported) { reported = true; InternalMediaPlayer.videoFailed() } }

    override fun onDestroy() {
        if (current?.get() === this) current = null
        try { video?.stopPlayback() } catch (e: RuntimeException) { /* ignore */ }
        fail()      // no-op when already reported
        super.onDestroy()
    }

    companion object {
        const val EXTRA_TITLE = "title"
        private var current: java.lang.ref.WeakReference<InternalVideoActivity>? = null

        /** Closes the video screen if one is open (main thread). */
        fun closeCurrent() {
            val a = current?.get() ?: return
            current = null
            try { a.finish() } catch (e: RuntimeException) { /* ignore */ }
        }
    }
}
