package com.jarvis.assistant.core

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Procedural arc-reactor renderer. All geometry is derived from radius R and drawn in a
 * center-origin coordinate system, so shaders/paths are built once in resize().
 * onDraw path performs no allocations.
 */
class JarvisCoreRenderer {

    companion object {
        private const val C_BLACK = 0xFF010608.toInt()
        private const val C_DEEP = 0xFF061A24.toInt()
        private const val C_DARK_CYAN = 0xFF073642.toInt()
        private const val C_CYAN = 0xFF00D9FF.toInt()
        private const val C_BRIGHT = 0xFF38E8FF.toInt()
        private const val C_WHITE = 0xFFDFFFFF.toInt()
        private const val C_COPPER = 0xFF7A3025.toInt()
        private const val C_COPPER_HI = 0xFFA64A32.toInt()
        private const val SEGMENTS = 24
        private const val TWO_PI = (2.0 * PI).toFloat()

        private val ARC_R = floatArrayOf(0.545f, 0.470f, 0.395f)
        private val ARC_START = arrayOf(
            floatArrayOf(8f, 128f, 248f), floatArrayOf(-25f, 100f, 215f), floatArrayOf(40f, 160f, 280f))
        private val ARC_SWEEP = arrayOf(
            floatArrayOf(96f, 84f, 100f), floatArrayOf(105f, 95f, 110f), floatArrayOf(100f, 90f, 70f))
        private val MODULE_ANGLES = floatArrayOf(-90f, 30f, 150f)
    }

    var radius = 0f
        private set
    private var R = 0f
    private var master = 1f

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val shaderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private val sectorOuter = Path()
    private val sectorLit = Path()
    private val coil = Path()
    private val ringTicks = Path()
    private val coreTicks = Path()
    private val traces = Path()
    private val moduleTicks = Path()
    private val tmp = RectF()

    private val arcRects = Array(6) { RectF() }   // [family*2 + track]
    private val segBack = RectF()
    private val segRect = RectF()
    private val segInner = RectF()
    private val squareRect = RectF()
    private val segBase = FloatArray(SEGMENTS)

    private var panelShader: Shader? = null
    private var bgShader: Shader? = null
    private var glowShader: Shader? = null
    private var coreShader: Shader? = null
    private var hlShader: Shader? = null

    private fun al(v: Float): Int = (v * master * 255f).toInt().coerceIn(0, 255)

    /** Vertical position of the core centre as a fraction of the view height (0.5 = middle). */
    var anchorY = 0.5f

    /** [maxRadius] caps the core size (px) so it stays at roughly Siri-orb presence in a large overlay. */
    fun resize(w: Int, h: Int, maxRadius: Float = Float.MAX_VALUE) {
        R = min(min(w, h) / 2f * 0.93f, maxRadius)
        radius = R
        if (R <= 0f) return

        buildSector(sectorOuter, 0.82f * R, 1.0f * R, 13f)
        buildSector(sectorLit, 0.85f * R, 0.975f * R, 10.5f)

        coil.reset()
        for (k in 0..8) {
            val r = (0.858f + k * 0.0125f) * R
            tmp.set(-r, -r, r, r)
            coil.arcTo(tmp, -8.5f, 17f, true)
        }

        ringTicks.reset()
        for (i in 0 until 72) {
            val a = i * TWO_PI / 72f
            val r0 = (if (i % 6 == 0) 0.700f else 0.712f) * R
            val r1 = 0.735f * R
            ringTicks.moveTo(cos(a) * r0, sin(a) * r0); ringTicks.lineTo(cos(a) * r1, sin(a) * r1)
        }
        coreTicks.reset()
        for (i in 0 until 48) {
            val a = i * TWO_PI / 48f
            val r0 = (if (i % 4 == 0) 0.250f else 0.265f) * R
            val r1 = 0.295f * R
            coreTicks.moveTo(cos(a) * r0, sin(a) * r0); coreTicks.lineTo(cos(a) * r1, sin(a) * r1)
        }
        traces.reset()
        for (off in floatArrayOf(-0.014f, 0.014f)) {
            traces.moveTo(0.345f * R, off * R); traces.lineTo(0.50f * R, off * R)
        }
        moduleTicks.reset()
        moduleTicks.moveTo(-0.040f * R, 0f); moduleTicks.lineTo(-0.028f * R, 0f)
        moduleTicks.moveTo(0.028f * R, 0f); moduleTicks.lineTo(0.040f * R, 0f)
        moduleTicks.moveTo(0f, -0.040f * R); moduleTicks.lineTo(0f, -0.028f * R)
        moduleTicks.moveTo(0f, 0.028f * R); moduleTicks.lineTo(0f, 0.040f * R)

        for (f in 0..2) for (t in 0..1) {
            val r = (ARC_R[f] - t * 0.030f) * R
            arcRects[f * 2 + t].set(-r, -r, r, r)
        }

        val cr = 0.63f * R; val hl = 0.052f * R; val hw = 0.026f * R; val pad = 0.008f * R
        segBack.set(cr - hl - pad, -hw - pad, cr + hl + pad, hw + pad)
        segRect.set(cr - hl, -hw, cr + hl, hw)
        val ins = 0.012f * R
        segInner.set(cr - hl + ins, -hw + ins, cr + hl - ins, hw - ins)
        squareRect.set(-0.04f * R, -0.04f * R, 0.04f * R, 0.04f * R)
        for (i in 0 until SEGMENTS) segBase[i] = 0.68f + 0.12f * sin(i * 1.7f)

        panelShader = LinearGradient(0.82f * R, 0f, 1.0f * R, 0f,
            intArrayOf(0xFF0A5568.toInt(), 0xFF1FB7D4.toInt(), 0xFF7CF0FF.toInt()),
            floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
        bgShader = RadialGradient(0f, 0f, 1.2f * R,
            intArrayOf(0x00000000, 0x6000D9FF, 0x2200D9FF, 0x00000000),
            floatArrayOf(0f, 0.82f, 0.93f, 1f), Shader.TileMode.CLAMP)
        glowShader = RadialGradient(0f, 0f, 0.62f * R,
            intArrayOf(0xCC38E8FF.toInt(), 0x6000D9FF, 0x00000000),
            floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        coreShader = RadialGradient(0f, 0f, 0.155f * R,
            intArrayOf(C_WHITE, C_BRIGHT, C_CYAN, C_DARK_CYAN),
            floatArrayOf(0f, 0.35f, 0.78f, 1f), Shader.TileMode.CLAMP)
        hlShader = RadialGradient(0f, 0f, 0.085f * R,
            intArrayOf(0xFFFFFFFF.toInt(), 0xCCDFFFFF.toInt(), 0x00DFFFFF),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
    }

    private fun buildSector(p: Path, r0: Float, r1: Float, half: Float) {
        p.reset()
        tmp.set(-r1, -r1, r1, r1); p.arcTo(tmp, -half, 2f * half)
        tmp.set(-r0, -r0, r0, r0); p.arcTo(tmp, half, -2f * half)
        p.close()
    }

    fun draw(c: Canvas, w: Int, h: Int, s: CoreAnimationState) {
        if (R <= 0f) return
        master = s.masterBrightness
        c.save()
        c.translate(w / 2f, h * anchorY + s.verticalEntryOffset)
        c.scale(s.entryScale, s.entryScale)

        // BACK PLANE
        shaderPaint.shader = bgShader; shaderPaint.alpha = al(0.55f * s.glow)
        c.drawCircle(0f, 0f, 1.2f * R, shaderPaint)
        drawHousing(c, s)
        drawInnerRing(c, s)
        // MID PLANE
        drawSegments(c, s)
        drawArcs(c, s)
        drawTraces(c)
        drawModules(c, s)
        // FRONT PLANE
        drawCentralRing(c, s)
        drawCore(c, s)
        c.restore()
    }

    private fun layeredCircle(c: Canvas, r: Float, wBack: Float, wMid: Float, wThin: Float, aMid: Float, aThin: Float) {
        stroke.color = C_CYAN; stroke.alpha = al(0.10f); stroke.strokeWidth = wBack * 1.6f
        c.drawCircle(0f, 0f, r, stroke)
        stroke.color = C_BLACK; stroke.alpha = al(0.85f); stroke.strokeWidth = wBack
        c.drawCircle(0f, 0f, r, stroke)
        stroke.color = C_CYAN; stroke.alpha = al(aMid); stroke.strokeWidth = wMid
        c.drawCircle(0f, 0f, r, stroke)
        stroke.color = C_BRIGHT; stroke.alpha = al(aThin); stroke.strokeWidth = wThin
        c.drawCircle(0f, 0f, r, stroke)
    }

    private fun drawHousing(c: Canvas, s: CoreAnimationState) {
        fill.color = C_DEEP; fill.alpha = al(0.95f)
        c.drawCircle(0f, 0f, R, fill)
        for (i in 0 until 12) {
            c.save()
            c.rotate(-90f + i * 30f + s.outerRotation)
            fill.color = 0xFF03090D.toInt(); fill.alpha = al(1f)
            c.drawPath(sectorOuter, fill)
            if (i % 2 == 0) {   // copper coil
                fill.color = C_COPPER; fill.alpha = al(0.85f)
                c.drawPath(sectorLit, fill)
                stroke.color = C_COPPER_HI; stroke.alpha = al(0.55f); stroke.strokeWidth = 0.006f * R
                c.drawPath(coil, stroke)
                stroke.color = C_BLACK; stroke.alpha = al(0.8f); stroke.strokeWidth = 0.008f * R
                c.drawPath(sectorLit, stroke)
            } else {            // lit cyan panel
                stroke.color = C_DARK_CYAN; stroke.alpha = al(0.9f); stroke.strokeWidth = 0.016f * R
                c.drawPath(sectorOuter, stroke)
                shaderPaint.shader = panelShader; shaderPaint.alpha = al(0.68f)
                c.drawPath(sectorLit, shaderPaint)
                stroke.color = C_BRIGHT; stroke.alpha = al(0.55f); stroke.strokeWidth = 0.006f * R
                c.drawPath(sectorLit, stroke)
            }
            // bolt on sector boundary
            c.rotate(15f)
            fill.color = C_BLACK; fill.alpha = al(0.95f)
            c.drawCircle(0.91f * R, 0f, 0.020f * R, fill)
            stroke.color = C_CYAN; stroke.alpha = al(0.40f); stroke.strokeWidth = 0.004f * R
            c.drawCircle(0.91f * R, 0f, 0.014f * R, stroke)
            c.restore()
        }
        layeredCircle(c, 0.995f * R, 0.020f * R, 0.008f * R, 0.003f * R, 0.45f, 0.55f)
    }

    private fun drawInnerRing(c: Canvas, s: CoreAnimationState) {
        stroke.color = 0xFF02080C.toInt(); stroke.alpha = al(1f); stroke.strokeWidth = 0.14f * R
        c.drawCircle(0f, 0f, 0.75f * R, stroke)
        c.save(); c.rotate(-s.outerRotation * 2f)
        stroke.color = C_CYAN; stroke.alpha = al(0.30f); stroke.strokeWidth = 0.004f * R
        c.drawPath(ringTicks, stroke)
        c.restore()
        layeredCircle(c, 0.815f * R, 0.022f * R, 0.010f * R, 0.004f * R, 0.55f, 0.60f)
        fill.color = 0xFF02090E.toInt(); fill.alpha = al(1f)
        c.drawCircle(0f, 0f, 0.69f * R, fill)
        // main cyan rim
        layeredCircle(c, 0.70f * R, 0.050f * R, 0.026f * R, 0.008f * R, 0.70f, 0.80f)
    }

    private fun angDist(a: Float, b: Float): Float = abs(((a - b + 540f) % 360f) - 180f)

    private fun drawSegments(c: Canvas, s: CoreAnimationState) {
        val cornerBack = 0.034f * R
        val corner = 0.026f * R
        for (i in 0 until SEGMENTS) {
            val pos = i / SEGMENTS.toFloat()
            val ang = 7.5f + i * 15f
            var b = segBase[i]
            val wv = 0.5f + 0.5f * sin(TWO_PI * (pos * 2f - s.segmentPhase))
            b += s.waveWeight * (0.30f * wv * wv - 0.05f)
            val pt = 0.5f + 0.5f * sin(TWO_PI * (pos * 3f - s.patternPhase))
            b += s.patternWeight * (0.30f * pt * pt - 0.05f)
            if (s.scannerAlpha > 0f) {
                val d = angDist(ang, s.scannerAngle)
                if (d < 30f) { val t = 1f - d / 30f; b += s.scannerAlpha * 0.35f * t * t }
            }
            b += s.smoothedVoiceAmplitude * s.voiceWeight * 0.22f
            if (b < 0.2f) b = 0.2f else if (b > 1f) b = 1f

            c.save(); c.rotate(ang)
            fill.color = C_BLACK; fill.alpha = al(0.9f)
            c.drawRoundRect(segBack, cornerBack, cornerBack, fill)
            stroke.color = C_CYAN; stroke.alpha = al(b * 0.22f); stroke.strokeWidth = 0.022f * R
            c.drawRoundRect(segRect, corner, corner, stroke)
            stroke.color = C_CYAN; stroke.alpha = al(b); stroke.strokeWidth = 0.008f * R
            c.drawRoundRect(segRect, corner, corner, stroke)
            fill.color = C_BRIGHT; fill.alpha = al(b * 0.78f)
            c.drawRoundRect(segInner, corner * 0.7f, corner * 0.7f, fill)
            c.restore()
        }
    }

    private fun drawArcs(c: Canvas, s: CoreAnimationState) {
        // thin boundary ring
        stroke.color = C_CYAN; stroke.alpha = al(0.22f); stroke.strokeWidth = 0.004f * R
        c.drawCircle(0f, 0f, 0.572f * R, stroke)
        val bright = (0.72f + 0.18f * (s.glow - 0.5f) + 0.08f * s.patternWeight).coerceIn(0.5f, 1f) +
            0.06f * s.smoothedVoiceAmplitude * s.voiceWeight
        for (f in 0..2) {
            c.save(); c.rotate(s.innerRotations[f])
            for (t in 0..1) {
                val rect = arcRects[f * 2 + t]
                val wMain = if (t == 0) 0.026f else 0.013f
                for (k in 0..2) {
                    val st = ARC_START[f][k] + t * 6f
                    val sw = ARC_SWEEP[f][k] - t * 12f
                    stroke.color = C_CYAN; stroke.alpha = al(0.12f); stroke.strokeWidth = wMain * 2.2f * R
                    c.drawArc(rect, st, sw, false, stroke)
                    stroke.color = C_BLACK; stroke.alpha = al(0.85f); stroke.strokeWidth = wMain * 1.7f * R
                    c.drawArc(rect, st, sw, false, stroke)
                    stroke.color = C_CYAN; stroke.alpha = al(bright * 0.85f); stroke.strokeWidth = wMain * R
                    c.drawArc(rect, st, sw, false, stroke)
                    stroke.color = C_BRIGHT; stroke.alpha = al(bright); stroke.strokeWidth = wMain * 0.3f * R
                    c.drawArc(rect, st, sw, false, stroke)
                }
            }
            c.restore()
        }
    }

    private fun drawTraces(c: Canvas) {
        for (a in MODULE_ANGLES) {
            c.save(); c.rotate(a)
            stroke.color = C_BLACK; stroke.alpha = al(0.8f); stroke.strokeWidth = 0.014f * R
            c.drawPath(traces, stroke)
            stroke.color = C_BRIGHT; stroke.alpha = al(0.45f); stroke.strokeWidth = 0.005f * R
            c.drawPath(traces, stroke)
            c.restore()
        }
    }

    private fun drawModules(c: Canvas, s: CoreAnimationState) {
        for (a in MODULE_ANGLES) {
            var boost = 0f
            if (s.scannerAlpha > 0f) {
                val d = angDist(a, s.scannerAngle)
                if (d < 25f) { val t = 1f - d / 25f; boost = s.scannerAlpha * t }
            }
            c.save(); c.rotate(a); c.translate(0.545f * R, 0f)
            fill.color = C_BLACK; fill.alpha = al(1f)
            c.drawCircle(0f, 0f, 0.062f * R, fill)
            stroke.color = C_DARK_CYAN; stroke.alpha = al(1f); stroke.strokeWidth = 0.010f * R
            c.drawCircle(0f, 0f, 0.060f * R, stroke)
            stroke.color = C_CYAN; stroke.alpha = al(0.55f + 0.4f * boost); stroke.strokeWidth = 0.007f * R
            c.drawCircle(0f, 0f, 0.046f * R, stroke)
            fill.color = C_DARK_CYAN; fill.alpha = al(0.9f)
            c.drawCircle(0f, 0f, 0.030f * R, fill)
            stroke.color = C_CYAN; stroke.alpha = al(0.5f); stroke.strokeWidth = 0.004f * R
            c.drawPath(moduleTicks, stroke)
            fill.color = C_BRIGHT; fill.alpha = al(0.6f + 0.4f * boost)
            c.drawCircle(0f, 0f, 0.012f * R, fill)
            c.restore()
        }
    }

    private fun drawCentralRing(c: Canvas, s: CoreAnimationState) {
        fill.color = 0xFF02090E.toInt(); fill.alpha = al(1f)
        c.drawCircle(0f, 0f, 0.345f * R, fill)
        layeredCircle(c, 0.335f * R, 0.028f * R, 0.013f * R, 0.005f * R, 0.65f, 0.75f)
        c.save(); c.rotate(s.innerRotations[2])
        stroke.color = C_CYAN; stroke.alpha = al(0.45f); stroke.strokeWidth = 0.005f * R
        c.drawPath(coreTicks, stroke)
        c.restore()
        stroke.color = C_DARK_CYAN; stroke.alpha = al(0.9f); stroke.strokeWidth = 0.008f * R
        c.drawCircle(0f, 0f, 0.235f * R, stroke)
    }

    private fun drawCore(c: Canvas, s: CoreAnimationState) {
        c.save()
        c.scale(s.coreScale, s.coreScale)
        shaderPaint.shader = glowShader; shaderPaint.alpha = al(0.55f * s.glow)
        c.drawCircle(0f, 0f, 0.62f * R, shaderPaint)

        fill.color = 0xFF02080C.toInt(); fill.alpha = al(1f)
        c.drawCircle(0f, 0f, 0.205f * R, fill)
        stroke.color = C_CYAN; stroke.alpha = al(0.18f); stroke.strokeWidth = 0.040f * R
        c.drawCircle(0f, 0f, 0.192f * R, stroke)
        stroke.color = C_CYAN; stroke.alpha = al(0.90f); stroke.strokeWidth = 0.014f * R
        c.drawCircle(0f, 0f, 0.192f * R, stroke)
        stroke.color = C_BRIGHT; stroke.alpha = al(0.9f); stroke.strokeWidth = 0.004f * R
        c.drawCircle(0f, 0f, 0.192f * R, stroke)

        fill.color = 0xFF04202A.toInt(); fill.alpha = al(1f)
        c.drawCircle(0f, 0f, 0.172f * R, fill)

        shaderPaint.shader = coreShader; shaderPaint.alpha = al(s.coreBrightness)
        c.drawCircle(0f, 0f, 0.155f * R, shaderPaint)

        stroke.color = C_DARK_CYAN; stroke.alpha = al(0.45f); stroke.strokeWidth = 0.005f * R
        c.drawRoundRect(squareRect, 0.01f * R, 0.01f * R, stroke)

        shaderPaint.shader = hlShader; shaderPaint.alpha = al(1f)
        c.drawCircle(0f, 0f, 0.085f * R, shaderPaint)
        c.restore()
    }
}
