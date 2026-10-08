package com.jarvis.assistant.core

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * Left/right edge energy with real depth, in the JARVIS palette (cyan / ice / reactor copper).
 *
 * The 3D feel comes from stacking, back to front:
 *  - a dark "well" behind the light, so the glow reads as light coming out of a recess;
 *  - four depth layers (far = wide, deep teal, set further into the screen; near = thin, ice-bright, at the edge),
 *    each emerging a little later than the one behind it and breathing out of phase (parallax);
 *  - slow colour blobs drifting inside the volume;
 *  - thin energy streaks that spawn at the edge and grow inward (perspective: nearer = larger and brighter);
 *  - a hotspot at the core's height that pulses with the reactor, and a bright rim hugging the edge.
 * Light layers are blended additively so overlaps build up luminous depth instead of flat alpha.
 * Everything is a unit radial gradient scaled by the canvas matrix: no hard edges, no allocation in draw.
 */
class JarvisEdgeGlow {

    private companion object {
        const val DEEP = 0x0A8FB8
        const val CYAN = 0x00D9FF
        const val ICE = 0xDFFFFF
        const val COPPER = 0xE0703C
        const val WELL = 0x000A12
        const val TWO_PI = (2.0 * PI).toFloat()
        const val STREAKS = 6
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val addMode = PorterDuffXfermode(PorterDuff.Mode.ADD)

    private fun argb(a: Float, rgb: Int) = ((a * 255f).toInt().coerceIn(0, 255) shl 24) or rgb

    private fun unit(rgb: Int, a0: Float, a1: Float): Shader = RadialGradient(
        0f, 0f, 1f,
        intArrayOf(argb(a0, rgb), argb(a1, rgb), argb(0f, rgb)),
        floatArrayOf(0f, 0.50f, 1f), Shader.TileMode.CLAMP
    )

    private val well = unit(WELL, 0.60f, 0.32f)
    private val layers = arrayOf(unit(DEEP, 0.80f, 0.34f), unit(CYAN, 0.80f, 0.30f), unit(COPPER, 0.60f, 0.20f), unit(ICE, 0.80f, 0.24f))
    private val blobs = arrayOf(unit(CYAN, 0.80f, 0.30f), unit(ICE, 0.60f, 0.20f), unit(COPPER, 0.50f, 0.16f))
    private val streak = unit(ICE, 0.95f, 0.35f)
    private val hot = unit(ICE, 0.90f, 0.40f)
    private val rim = unit(ICE, 0.95f, 0.30f)

    private val streakY = FloatArray(2 * STREAKS) { i ->
        val v = sin(i * 12.9898f + 78.233f) * 43758.547f
        (v - kotlin.math.floor(v)) * 2f - 1f          // fixed pseudo-random -1..1 lane per streak
    }

    private var w = 0f
    private var h = 0f
    private var band = 0f
    private var rimW = 0f
    private var phase = 0f
    private var intensity = 0.55f

    fun resize(width: Int, height: Int, density: Float) {
        w = width.toFloat(); h = height.toFloat()
        band = min(w * 0.30f, 64f * density)
        rimW = 7f * density
    }

    fun update(dt: Float, state: JarvisState, voice: Float) {
        val speed: Float
        val target: Float
        when (state) {
            JarvisState.READY -> { speed = 0.30f; target = 0.55f }
            JarvisState.LISTENING -> { speed = 0.55f; target = 0.95f }
            JarvisState.THINKING -> { speed = 1.00f; target = 0.80f }
            JarvisState.SPEAKING -> { speed = 0.75f; target = 0.75f + 0.30f * voice }
        }
        phase = (phase + TWO_PI * speed * dt) % (TWO_PI * 4f)
        intensity += (target - intensity) * (1f - Math.pow(0.90, (dt * 60f).toDouble()).toFloat())
    }

    /** [progress] 0..1 edge timeline, [light] 0..1 core light level, [cy] vertical origin the glow unfurls from. */
    fun draw(c: Canvas, progress: Float, light: Float, cy: Float) {
        if (progress <= 0f || w <= 0f || h <= 0f) return
        val e = CoreAnimationController.ease(progress)
        val halfH = h * (0.12f + 0.88f * e)
        val a = (e * (0.55f + 0.45f * light) * intensity).coerceIn(0f, 1f)
        val breath = 0.5f + 0.5f * sin(phase * 0.6f)       // same slow rhythm family as the reactor's breathing

        for (side in 0..1) {
            val x = if (side == 0) 0f else w
            val dir = if (side == 0) 1f else -1f           // +1 = towards screen centre

            // 1. recess behind the light
            ellipse(c, well, x, cy, band * 2.0f, halfH, a * 0.75f, false)

            // 2. depth layers, far -> near, each emerging after the one behind it
            for (k in 0..3) {
                val lp = ((e - k * 0.14f) / 0.58f).coerceIn(0f, 1f)
                val lE = CoreAnimationController.ease(lp)
                if (lE <= 0f) continue
                val breathK = 1f + 0.14f * sin(phase * 0.5f + k * 1.3f + side * 1.7f)
                val lw = band * (2.1f - 0.48f * k) * breathK * (0.50f + 0.50f * lE)
                val lx = x + dir * (3 - k) * 0.06f * band            // far layers sit deeper inside the screen
                ellipse(c, layers[k], lx, cy, lw, halfH * (1f - 0.05f * k), a * lE * (0.34f + 0.17f * k), true)
            }

            // 3. drifting colour volume
            for (i in 0..2) {
                val ph = phase * (0.55f + 0.23f * i) + side * 2.4f + i * 2.1f
                val y = cy + sin(ph) * h * 0.30f * e
                val bw = band * (0.95f + 0.28f * sin(ph * 0.7f + i))
                ellipse(c, blobs[i], x + dir * band * 0.10f * i, y, bw, h * (0.15f + 0.05f * i) * (0.4f + 0.6f * e), a * (0.55f - 0.08f * i), true)
            }

            // 4. streaks: born at the edge, grow inward and brighten as they come "forward"
            for (j in 0 until STREAKS) {
                val t = (phase * 0.045f + j / STREAKS.toFloat() + side * 0.31f) % 1f
                val fade = sin(PI.toFloat() * t)
                val y = cy + streakY[side * STREAKS + j] * halfH * 0.85f
                val len = band * (0.6f + 2.4f * t * t)
                val th = rimW * (0.25f + 1.1f * t)
                ellipse(c, streak, x, y, len, th, a * fade * fade * 0.55f * (0.4f + 0.6f * light), true)
            }

            // 5. hotspot at the core's height, pulsing with the reactor
            ellipse(c, hot, x, cy, band * (1.0f + 0.25f * breath), h * 0.13f * (0.5f + 0.5f * e), a * (0.30f + 0.45f * light) * (0.8f + 0.4f * breath), true)

            // 6. bright rim on the edge itself
            ellipse(c, rim, x, cy, rimW, halfH * 0.96f, a * 0.80f, true)
        }
        paint.xfermode = null
    }

    private fun ellipse(c: Canvas, s: Shader, x: Float, y: Float, rx: Float, ry: Float, alpha: Float, add: Boolean) {
        paint.shader = s
        paint.xfermode = if (add) addMode else null
        paint.alpha = (alpha * 255f).toInt().coerceIn(0, 255)
        c.save()
        c.translate(x, y)
        c.scale(rx, ry)
        c.drawCircle(0f, 0f, 1f, paint)
        c.restore()
    }
}
