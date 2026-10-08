package com.jarvis.assistant.core

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min

/**
 * Left/right edge energy: only a very soft blue/turquoise halo at the screen edges.
 * No lines, no rings, no streaks, no rim: each layer is a wide gaussian-like falloff that is centred on the
 * screen edge, so nothing with a visible boundary is ever drawn. It shares the reactor's light level (same
 * timeline, same moment on / off) and breathes with the reactor's energy pulse, a little delayed.
 * No allocation in draw.
 */
class JarvisEdgeGlow {

    private companion object {
        const val DEEP = 0x0A9FC0
        const val CYAN = 0x00D9FF
        const val TWO_PI = (2.0 * PI).toFloat()
        val STOPS = floatArrayOf(0f, 0.18f, 0.38f, 0.60f, 0.80f, 1f)
        val FALLOFF = floatArrayOf(1f, 0.74f, 0.45f, 0.20f, 0.06f, 0f)
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
    }

    private fun soft(rgb: Int): Shader = RadialGradient(
        0f, 0f, 1f,
        IntArray(FALLOFF.size) { (((FALLOFF[it] * 255f).toInt().coerceIn(0, 255)) shl 24) or rgb },
        STOPS, Shader.TileMode.CLAMP
    )

    private val wide = soft(DEEP)
    private val mid = soft(CYAN)

    private var w = 0f
    private var h = 0f
    private var band = 0f
    private var intensity = 0.55f

    fun resize(width: Int, height: Int, density: Float) {
        w = width.toFloat(); h = height.toFloat()
        band = min(w * 0.28f, 70f * density)
    }

    fun update(dt: Float, state: JarvisState, voice: Float) {
        val target = when (state) {
            JarvisState.READY -> 0.55f
            JarvisState.LISTENING -> 0.90f
            JarvisState.THINKING -> 0.75f
            JarvisState.SPEAKING -> 0.72f + 0.25f * voice
        }
        intensity += (target - intensity) * (1f - Math.pow(0.90, (dt * 60f).toDouble()).toFloat())
    }

    /** [light] 0..1 shared reactor light level, [energyPhase] 0..1 reactor pulse cycle, [cy] height of the reactor centre. */
    fun draw(c: Canvas, light: Float, energyPhase: Float, cy: Float) {
        if (light <= 0f || w <= 0f || h <= 0f) return
        val pulse = 0.5f + 0.5f * cos(TWO_PI * (energyPhase - 0.40f))   // reaches the edges after the reactor
        val a = light * intensity * (0.86f + 0.14f * pulse)
        val grow = 0.88f + 0.12f * light

        for (side in 0..1) {
            val x = if (side == 0) 0f else w
            soft(c, wide, x, h * 0.50f, band * 2.3f * grow, h * 0.66f, a * 0.34f)
            soft(c, mid, x, h * 0.50f, band * 1.35f * grow, h * 0.52f, a * 0.22f)
            // slightly stronger, still diffuse, where the reactor sits: carries its energy to the screen
            soft(c, mid, x, cy, band * 1.15f * grow, h * 0.26f, a * 0.20f * (0.80f + 0.40f * pulse))
        }
    }

    private fun soft(c: Canvas, s: Shader, x: Float, y: Float, rx: Float, ry: Float, alpha: Float) {
        paint.shader = s
        paint.alpha = (alpha * 255f).toInt().coerceIn(0, 255)
        c.save()
        c.translate(x, y)
        c.scale(rx, ry)
        c.drawCircle(0f, 0f, 1f, paint)
        c.restore()
    }
}
