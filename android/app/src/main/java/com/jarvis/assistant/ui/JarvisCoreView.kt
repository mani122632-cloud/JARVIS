package com.jarvis.assistant.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import com.jarvis.assistant.R
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/**
 * The JARVIS core: a breathing disc surrounded by concentric rings.
 *
 * Performance notes:
 *  - No allocations in onDraw (paints, rects, shader are created once).
 *  - Single draw loop driven by postInvalidateOnAnimation (vsync aligned).
 *  - Loop stops when detached / hidden, and when system animations are disabled.
 *  - Only circles and arcs; the single gradient is a static shader.
 */
class JarvisCoreView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** Motion parameters for one state. Placeholders for non-READY states. */
    private class Profile(
        val spinDegPerSec: Float,
        val orbitDegPerSec: Float,
        val breathAmp: Float,
        val breathHz: Float,
        val intensity: Float
    )

    private companion object {
        const val MAX_SIZE_DP = 380f
        const val TWO_PI = (2.0 * Math.PI).toFloat()
        const val MAX_FRAME_DT = 0.05f
        const val EASE_RATE = 3f

        // Radii as fractions of the available radius.
        const val R_CORE = 0.24f
        const val R_HALO = 0.58f
        const val R_A = 0.35f
        const val R_B = 0.47f
        const val R_C = 0.60f
        const val R_D = 0.72f
        const val R_E = 0.88f

        // Arc segments as (startDeg, sweepDeg) pairs.
        val ARCS_B = floatArrayOf(0f, 62f, 84f, 26f, 140f, 74f, 236f, 14f, 262f, 70f)
        val ARCS_D = floatArrayOf(20f, 110f, 190f, 80f)

        val READY = Profile(6f, 14f, 0.02f, 0.20f, 0.85f)
        val LISTENING = Profile(10f, 20f, 0.035f, 0.42f, 1.0f)
        val THINKING = Profile(60f, 90f, 0.02f, 0.62f, 1.0f)
        val SPEAKING = Profile(14f, 24f, 0.05f, 1.10f, 1.0f)

        fun profileFor(s: JarvisState) = when (s) {
            JarvisState.READY -> READY
            JarvisState.LISTENING -> LISTENING
            JarvisState.THINKING -> THINKING
            JarvisState.SPEAKING -> SPEAKING
        }
    }

    var state: JarvisState = JarvisState.READY
        set(value) {
            if (field == value) return
            field = value
            target = profileFor(value)
            if (!running) {
                snapToTarget()
                invalidate()
            }
        }

    private val density = resources.displayMetrics.density
    private val accent = context.getColor(R.color.jarvis_accent)
    private val coreFillColor = context.getColor(R.color.jarvis_core_fill)

    private var target = READY
    private var curSpin = READY.spinDegPerSec
    private var curOrbit = READY.orbitDegPerSec
    private var curAmp = READY.breathAmp
    private var curHz = READY.breathHz
    private var curIntensity = READY.intensity

    private var spinAngle = 0f
    private var orbitAngle = 0f
    private var breathPhase = 0f

    private var running = false
    private var lastFrameMs = 0L

    private var baseR = 0f
    private val rectB = RectF()
    private val rectD = RectF()
    private val rectE = RectF()

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = accent
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = accent
    }
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = coreFillColor
    }
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    // ---- Layout ---------------------------------------------------------

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val max = (MAX_SIZE_DP * density).toInt()
        var size = if (MeasureSpec.getMode(widthSpec) == MeasureSpec.UNSPECIFIED) max
        else min(MeasureSpec.getSize(widthSpec), max)
        if (MeasureSpec.getMode(heightSpec) != MeasureSpec.UNSPECIFIED) {
            size = min(size, MeasureSpec.getSize(heightSpec))
        }
        setMeasuredDimension(size, size)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        baseR = min(w, h) / 2f - 4f * density
        if (baseR <= 0f) {
            // RadialGradient throws IllegalArgumentException for radius <= 0.
            baseR = 0f
            haloPaint.shader = null
            return
        }
        setRect(rectB, baseR * R_B)
        setRect(rectD, baseR * R_D)
        setRect(rectE, baseR * R_E)

        val haloR = baseR * R_HALO
        val inner = (baseR * R_CORE / haloR).coerceIn(0f, 0.99f)
        haloPaint.shader = RadialGradient(
            0f, 0f, haloR,
            intArrayOf(accent, accent and 0x00FFFFFF),
            floatArrayOf(inner, 1f),
            Shader.TileMode.CLAMP
        )
    }

    private fun setRect(r: RectF, radius: Float) = r.set(-radius, -radius, radius, radius)

    // ---- Animation loop -------------------------------------------------

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateRunning()
    }

    override fun onDetachedFromWindow() {
        running = false
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        updateRunning()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        updateRunning()
    }

    private fun updateRunning() {
        val should = isAttachedToWindow &&
            windowVisibility == VISIBLE &&
            visibility == VISIBLE &&
            ValueAnimator.areAnimatorsEnabled()
        if (should && !running) {
            running = true
            lastFrameMs = 0L
            postInvalidateOnAnimation()
        } else if (!should) {
            running = false
            invalidate()
        }
    }

    private fun advance(dt: Float) {
        val t = target
        val k = 1f - exp(-dt * EASE_RATE)
        curSpin += (t.spinDegPerSec - curSpin) * k
        curOrbit += (t.orbitDegPerSec - curOrbit) * k
        curAmp += (t.breathAmp - curAmp) * k
        curHz += (t.breathHz - curHz) * k
        curIntensity += (t.intensity - curIntensity) * k

        spinAngle = (spinAngle + curSpin * dt) % 360f
        orbitAngle = (orbitAngle + curOrbit * dt) % 360f
        breathPhase = (breathPhase + TWO_PI * curHz * dt) % TWO_PI
    }

    private fun snapToTarget() {
        val t = target
        curSpin = t.spinDegPerSec
        curOrbit = t.orbitDegPerSec
        curAmp = t.breathAmp
        curHz = t.breathHz
        curIntensity = t.intensity
    }

    // ---- Drawing --------------------------------------------------------

    private fun a(fraction: Float): Int = (255f * fraction * curIntensity).toInt().coerceIn(0, 255)

    private fun drawArcs(canvas: Canvas, rect: RectF, arcs: FloatArray) {
        var i = 0
        while (i < arcs.size) {
            canvas.drawArc(rect, arcs[i], arcs[i + 1], false, strokePaint)
            i += 2
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (baseR <= 0f) return
        if (running) {
            val now = SystemClock.uptimeMillis()
            val dt = if (lastFrameMs == 0L) 0f else min((now - lastFrameMs) / 1000f, MAX_FRAME_DT)
            lastFrameMs = now
            advance(dt)
        }

        val b = sin(breathPhase)
        val n = 0.5f + 0.5f * b
        val coreR = baseR * R_CORE * (1f + curAmp * b)

        canvas.save()
        canvas.translate(width / 2f, height / 2f)

        // Soft, restrained halo behind the core.
        if (haloPaint.shader != null) {
            haloPaint.alpha = a(0.10f + 0.08f * n)
            canvas.drawCircle(0f, 0f, baseR * R_HALO, haloPaint)
        }

        // Core disc.
        canvas.drawCircle(0f, 0f, coreR, corePaint)
        fillPaint.alpha = a(0.05f + 0.05f * n)
        canvas.drawCircle(0f, 0f, coreR * 0.80f, fillPaint)

        strokePaint.strokeCap = Paint.Cap.BUTT
        strokePaint.strokeWidth = 1.5f * density
        strokePaint.alpha = a(0.90f)
        canvas.drawCircle(0f, 0f, coreR, strokePaint)

        strokePaint.strokeWidth = 1f * density
        strokePaint.alpha = a(0.30f)
        canvas.drawCircle(0f, 0f, coreR * 0.62f, strokePaint)

        fillPaint.alpha = a(0.85f)
        canvas.drawCircle(0f, 0f, coreR * 0.09f * (1f + 0.25f * b), fillPaint)

        // Ring A: inner hairline.
        strokePaint.alpha = a(0.22f)
        canvas.drawCircle(0f, 0f, baseR * R_A, strokePaint)

        // Ring B: segmented, clockwise.
        strokePaint.strokeCap = Paint.Cap.ROUND
        strokePaint.strokeWidth = 2f * density
        strokePaint.alpha = a(0.45f + 0.15f * n)
        canvas.save()
        canvas.rotate(spinAngle)
        drawArcs(canvas, rectB, ARCS_B)
        canvas.restore()

        // Ring C: hairline.
        strokePaint.strokeCap = Paint.Cap.BUTT
        strokePaint.strokeWidth = 1f * density
        strokePaint.alpha = a(0.12f)
        canvas.drawCircle(0f, 0f, baseR * R_C, strokePaint)

        // Ring D: thin segmented, counter-clockwise.
        strokePaint.strokeCap = Paint.Cap.ROUND
        strokePaint.alpha = a(0.30f)
        canvas.save()
        canvas.rotate(-spinAngle * 0.6f)
        drawArcs(canvas, rectD, ARCS_D)
        canvas.restore()

        // Ring E: outer hairline with a small orbiting node.
        strokePaint.strokeCap = Paint.Cap.BUTT
        strokePaint.alpha = a(0.10f)
        canvas.drawCircle(0f, 0f, baseR * R_E, strokePaint)

        canvas.save()
        canvas.rotate(orbitAngle)
        strokePaint.strokeCap = Paint.Cap.ROUND
        strokePaint.strokeWidth = 1.5f * density
        strokePaint.alpha = a(0.35f)
        canvas.drawArc(rectE, -26f, 26f, false, strokePaint)
        fillPaint.alpha = a(0.90f)
        canvas.drawCircle(baseR * R_E, 0f, 2.5f * density, fillPaint)
        canvas.restore()

        canvas.restore()

        if (running) postInvalidateOnAnimation()
    }
}
