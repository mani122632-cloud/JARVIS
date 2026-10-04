package com.jarvis.assistant.core

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import kotlin.math.min

/**
 * Public API:
 *  setState(JarvisState), showCinematic(), hideCinematic(),
 *  setVoiceAmplitude(0..1), setCoreVisible(visible, animated)
 * Default: HIDDEN (off-screen, no animation work). Call showCinematic() to enter.
 * Draws on a transparent background; the reactor enters/exits through the view's bottom edge.
 */
class JarvisCoreView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val anim = CoreAnimationState()
    private val controller = CoreAnimationController(anim)
    private val renderer = JarvisCoreRenderer()

    private var running = false
    private var lastFrameNs = 0L
    private var accum = 0f
    private val frameCallback = Choreographer.FrameCallback { onFrame(it) }

    val state: JarvisState get() = controller.state

    fun setState(newState: JarvisState) {
        controller.setState(newState)
        if (newState != JarvisState.SPEAKING) controller.rawVoice = 0f
        startLoop()
    }

    fun showCinematic() { controller.show(); startLoop() }
    fun hideCinematic() { controller.hide(); startLoop() }

    fun setCoreVisible(visible: Boolean, animated: Boolean = true) {
        if (animated) { if (visible) showCinematic() else hideCinematic() }
        else { controller.setVisibleImmediately(visible); invalidate(); if (visible) startLoop() }
    }

    /** 0 = silence, 1 = strong voice. Allocation-free; call as often as TTS delivers levels. */
    fun setVoiceAmplitude(amplitude: Float) {
        controller.rawVoice = amplitude.coerceIn(0f, 1f)
    }

    // ---- lifecycle / loop ----
    override fun onAttachedToWindow() { super.onAttachedToWindow(); startLoop() }
    override fun onDetachedFromWindow() { stopLoop(); super.onDetachedFromWindow() }
    override fun onWindowVisibilityChanged(v: Int) {
        super.onWindowVisibilityChanged(v)
        if (v == VISIBLE) startLoop() else stopLoop()
    }
    override fun onVisibilityChanged(changed: View, v: Int) {
        super.onVisibilityChanged(changed, v)
        if (v == VISIBLE) startLoop() else stopLoop()
    }

    private fun canRun() = isAttachedToWindow && windowVisibility == VISIBLE && visibility == VISIBLE

    private fun startLoop() {
        if (running || !canRun()) return
        running = true
        lastFrameNs = 0L
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private fun stopLoop() {
        running = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
    }

    private fun onFrame(nowNs: Long) {
        if (!running) return
        val dt = if (lastFrameNs == 0L) 0.016f else ((nowNs - lastFrameNs) / 1e9f).coerceIn(0f, 0.05f)
        lastFrameNs = nowNs
        controller.update(dt)
        if (controller.isIdleLowRate) {
            accum += dt
            if (accum >= 0.033f) { accum = 0f; invalidate() }
        } else {
            accum = 0f
            invalidate()
        }
        if (controller.isHidden) {            // fully off-screen: render once more (clear) and sleep
            invalidate()
            running = false
            return
        }
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        renderer.resize(w, h)
        // center sits at h/2; glow halo reaches ~1.2R (x up to ~1.1 scale). Whole reactor ends below the bottom edge.
        controller.hiddenOffsetPx = h / 2f + renderer.radius * 1.4f
        controller.update(0f)
    }

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        // Default to a square if the host gives no height constraint.
        val w = MeasureSpec.getSize(wSpec)
        val h = if (MeasureSpec.getMode(hSpec) == MeasureSpec.UNSPECIFIED) w else MeasureSpec.getSize(hSpec)
        setMeasuredDimension(
            resolveSize(if (w > 0) w else min(h, 600), wSpec),
            resolveSize(h, hSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        if (controller.isHidden) return
        renderer.draw(canvas, width, height, anim)
    }
}
