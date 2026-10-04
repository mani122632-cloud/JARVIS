package com.jarvis.assistant.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import com.jarvis.assistant.core.JarvisCoreView

/**
 * Owns exactly one WindowManager overlay window that hosts the existing [JarvisCoreView].
 * Idempotent: show() never creates a second window, hide()/remove() are always safe.
 * Main thread only. Pass the Service context (needed for TYPE_APPLICATION_OVERLAY).
 *
 * The window is small (180-240dp), bottom-centre, not focusable and not touchable, so touches
 * go straight through to the app underneath.
 */
class JarvisOverlayWindow(private val context: Context) {

    private enum class State { GONE, SHOWN, EXITING }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val enterInterpolator = PathInterpolator(0.2f, 0f, 0f, 1f)   // smooth decel, no overshoot
    private val exitInterpolator = PathInterpolator(0.4f, 0f, 1f, 1f)    // smooth accel, no overshoot

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
                state = State.SHOWN
                animateIn(existing)
            }
            return core
        }

        val lp = buildParams()
        val coreView = JarvisCoreView(context)
        val frame = FrameLayout(context).apply {
            clipChildren = false
            clipToPadding = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            alpha = 0f
            scaleX = START_SCALE
            scaleY = START_SCALE
            pivotX = lp.width / 2f
            pivotY = lp.height / 2f
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
        animateIn(frame)
        return coreView
    }

    /** Fades out, then removes the window and calls [onRemoved]. Safe to call repeatedly / when not shown. */
    fun hide(onRemoved: (() -> Unit)? = null) {
        val frame = root
        if (frame == null) { onRemoved?.invoke(); return }
        if (state == State.EXITING) return
        state = State.EXITING
        val myToken = ++token
        frame.animate().cancel()
        frame.animate()
            .alpha(0f)
            .scaleX(EXIT_SCALE)
            .scaleY(EXIT_SCALE)
            .setDuration(EXIT_MS)
            .setInterpolator(exitInterpolator)
            .withEndAction {
                if (myToken == token && state == State.EXITING) {
                    remove()
                    onRemoved?.invoke()
                }
            }
            .start()
    }

    /** Removes the window immediately (service teardown). Safe to call repeatedly. */
    fun remove() {
        token++
        val frame = root
        if (frame != null) {
            frame.animate().cancel()
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
        frame.pivotX = lp.width / 2f
        frame.pivotY = lp.height / 2f
        try {
            windowManager.updateViewLayout(frame, lp)
        } catch (e: RuntimeException) {
            Log.w(TAG, "updateViewLayout failed", e)
        }
    }

    private fun animateIn(frame: View) {
        frame.animate().cancel()
        frame.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(ENTER_MS)
            .setInterpolator(enterInterpolator)
            .start()
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
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        val lp = WindowManager.LayoutParams(0, 0, type, flags, PixelFormat.TRANSLUCENT)
        lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        lp.windowAnimations = 0
        lp.title = "JarvisOverlay"
        applyGeometry(lp)
        return lp
    }

    /** Size = 56% of the short screen side, clamped to 180-240dp. Sits ~12% above the bottom edge. */
    private fun applyGeometry(lp: WindowManager.LayoutParams) {
        val dm = context.resources.displayMetrics
        val shortSide = minOf(dm.widthPixels, dm.heightPixels).toFloat()
        val size = (shortSide * SIZE_FRACTION).coerceIn(MIN_DP * dm.density, MAX_DP * dm.density).toInt()
        lp.width = size
        lp.height = size
        lp.x = 0
        lp.y = (dm.heightPixels * BOTTOM_FRACTION).toInt()
    }

    private companion object {
        const val TAG = "JarvisOverlayWindow"
        const val MIN_DP = 180f
        const val MAX_DP = 240f
        const val SIZE_FRACTION = 0.56f
        const val BOTTOM_FRACTION = 0.12f
        const val START_SCALE = 0.88f
        const val EXIT_SCALE = 0.94f
        const val ENTER_MS = 420L
        const val EXIT_MS = 300L
    }
}
