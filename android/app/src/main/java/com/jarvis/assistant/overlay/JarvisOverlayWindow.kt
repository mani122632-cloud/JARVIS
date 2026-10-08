package com.jarvis.assistant.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import com.jarvis.assistant.core.CoreAnimationController
import com.jarvis.assistant.core.JarvisCoreView

/**
 * Owns exactly one WindowManager overlay window that hosts the existing [JarvisCoreView].
 * Idempotent: show() never creates a second window, hide()/remove() are always safe.
 * Main thread only. Pass the Service context (needed for TYPE_APPLICATION_OVERLAY).
 *
 * The window is full-screen (so the core's left/right edge glow can reach the screen edges), transparent,
 * not focusable and not touchable, so touches go straight through to the app underneath. All visuals are
 * driven by [JarvisCoreView] itself: it blooms in place on show() and collapses into its centre on hide().
 * The window is removed only after the core reports that the exit animation finished (onHidden), with a
 * safety timer of HIDE_TOTAL_MS + margin in case that callback can never fire.
 */
class JarvisOverlayWindow(private val context: Context) {

    private enum class State { GONE, SHOWN, EXITING }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private var exitFallback: Runnable? = null

    private var state = State.GONE
    private var root: FrameLayout? = null
    private var core: JarvisCoreView? = null
    private var params: WindowManager.LayoutParams? = null
    private var token = 0                      // invalidates a pending exit when show() interrupts it

    /** True from show() until the window has actually been removed (includes the exit fade). */
    val isAttached: Boolean get() = state != State.GONE

    /** The on-screen core, or null when the overlay is not shown. */
    val currentCore: JarvisCoreView? get() = core

    /** Adds the window (or re-enters an exiting one) and returns the core. Null if the window could not be added. */
    fun show(): JarvisCoreView? {
        val existing = root
        if (existing != null) {
            if (state == State.EXITING) {
                token++
                cancelExitFallback()
                state = State.SHOWN
                core?.showCinematic()          // re-bloom from wherever the collapse had reached
            }
            return core
        }

        val lp = buildParams()
        val coreView = JarvisCoreView(context).apply {
            coreAnchorY = CORE_ANCHOR_Y
        }
        val frame = FrameLayout(context).apply {
            clipChildren = false
            clipToPadding = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            addView(coreView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }

        try {
            windowManager.addView(frame, lp)
        } catch (e: RuntimeException) {            // BadToken / permission revoked / invalid display
            Log.e(TAG, "Could not add overlay window", e)
            frame.removeAllViews()
            return null
        }

        root = frame
        core = coreView
        params = lp
        state = State.SHOWN
        coreView.showCinematic()
        return coreView
    }

    /** Fades out, then removes the window and calls [onRemoved]. Safe to call repeatedly / when not shown. */
    fun hide(onRemoved: (() -> Unit)? = null) {
        val frame = root
        if (frame == null) { onRemoved?.invoke(); return }
        if (state == State.EXITING) return
        state = State.EXITING
        val myToken = ++token
        var done = false
        val finish = {
            if (!done && myToken == token && state == State.EXITING) {
                done = true
                cancelExitFallback()
                core?.onHidden = null
                remove()
                onRemoved?.invoke()
            }
        }
        val c = core
        if (c == null) { finish(); return }
        c.onHidden = { finish() }               // fires when glow + light + collapse have fully finished
        c.hideCinematic()
        // Safety net only: lets the exit finish fully, but can never leave the window stuck on screen.
        val fb = Runnable { finish() }
        exitFallback = fb
        handler.postDelayed(fb, CoreAnimationController.HIDE_TOTAL_MS + EXIT_MARGIN_MS)
    }

    private fun cancelExitFallback() {
        exitFallback?.let { handler.removeCallbacks(it) }
        exitFallback = null
    }

    /** Removes the window immediately (service teardown). Safe to call repeatedly. */
    fun remove() {
        token++
        cancelExitFallback()
        core?.onHidden = null
        val frame = root
        if (frame != null) {
            try {
                windowManager.removeViewImmediate(frame)
            } catch (e: IllegalArgumentException) {
                // already gone
            }
            frame.removeAllViews()
        }
        root = null
        core = null
        params = null
        state = State.GONE
    }

    /** Recompute size/position after rotation or display-size change. */
    fun onConfigurationChanged() {
        val frame = root ?: return
        val lp = params ?: return
        applyGeometry(lp)
        try {
            windowManager.updateViewLayout(frame, lp)
        } catch (e: RuntimeException) {
            Log.w(TAG, "updateViewLayout failed", e)
        }
    }

    private fun buildParams(): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or      // touches pass through to the app below
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or   // edge glow reaches the true screen edges
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        val lp = WindowManager.LayoutParams(0, 0, type, flags, PixelFormat.TRANSLUCENT)
        lp.gravity = Gravity.TOP or Gravity.START
        lp.windowAnimations = 0
        lp.title = "JarvisOverlay"
        applyGeometry(lp)
        return lp
    }

    /** Full-screen, transparent. The core's size and position are decided inside [JarvisCoreView]. */
    private fun applyGeometry(lp: WindowManager.LayoutParams) {
        lp.width = WindowManager.LayoutParams.MATCH_PARENT
        lp.height = WindowManager.LayoutParams.MATCH_PARENT
        lp.x = 0
        lp.y = 0
    }

    private companion object {
        const val TAG = "JarvisOverlayWindow"
        /** Core centre as a fraction of screen height: lower third, like an assistant orb. It blooms here, never travels. */
        const val CORE_ANCHOR_Y = 0.74f
        const val EXIT_MARGIN_MS = 350L
    }
}
