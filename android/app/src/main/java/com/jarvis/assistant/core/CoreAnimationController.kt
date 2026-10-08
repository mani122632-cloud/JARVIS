package com.jarvis.assistant.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/**
 * Single frame-driven update loop for state blending, rotations, voice smoothing
 * and cinematic entry/exit. No allocations after construction.
 */
class CoreAnimationController(private val s: CoreAnimationState) {

    companion object {
        private const val N = 13
        // 0 outerRps,1-3 innerRps,4 scannerRps,5 scannerAlpha,6 glow,7 wave,8 pattern,
        // 9 scaleBase,10 scaleAmp,11 breathHz,12 voiceWeight   (indexed by JarvisState.ordinal)
        private val TABLE = arrayOf(
            floatArrayOf(0.026f, 0.045f, -0.055f, 0.040f, 0f, 0f, 0.55f, 0f, 0f, 0f, 0.015f, 0.285f, 0f),
            floatArrayOf(0.022f, 0.050f, -0.060f, 0.050f, 0f, 0f, 0.85f, 1f, 0f, 0f, 0.060f, 0.90f, 0f),
            floatArrayOf(0.040f, 0.100f, -0.160f, 0.130f, 0.62f, 1f, 0.70f, 0f, 1f, 0f, 0.008f, 0.50f, 0f),
            floatArrayOf(0.030f, 0.070f, -0.090f, 0.070f, 0f, 0f, 0.80f, 0.25f, 0f, 0f, 0f, 0.50f, 1f)
        )
        // Bloom-in: the core grows from a point, then its light ignites, then the edge glow unfurls.
        private const val SCALE_UP = 0.95f
        private const val LIGHT_UP = 0.85f
        private const val EDGE_UP = 0.85f
        private const val LIGHT_GATE = 0.25f      // light starts once the core is 25% grown
        private const val EDGE_GATE = 0.20f       // edge glow starts once the light is 20% lit
        // Collapse-out: edge glow + light fade first, then the core folds into its own centre point.
        private const val LIGHT_DOWN = 0.55f
        private const val EDGE_DOWN = 0.50f
        private const val SCALE_DOWN = 0.60f
        private const val COLLAPSE_GATE = 0.25f   // collapse starts once the light is down to 25%
        /** Total durations (ms) of the full bloom / collapse. A host that removes the window by timer must wait at least this long. */
        const val SHOW_TOTAL_MS = 1300L
        const val HIDE_TOTAL_MS = 1050L
        private const val TWO_PI = (2.0 * PI).toFloat()

        /**
         * Material FastOutSlowIn = cubic-bezier(0.4, 0, 0.2, 1), solved without allocation.
         * Applied to a linear progress value, so reversing mid-flight stays position-continuous.
         */
        fun ease(t: Float): Float {
            if (t <= 0f) return 0f
            if (t >= 1f) return 1f
            val x1 = 0.4f; val x2 = 0.2f
            var u = t
            for (i in 0 until 6) {
                val v = 1f - u
                val x = 3f * v * v * u * x1 + 3f * v * u * u * x2 + u * u * u
                val dx = 3f * v * v * x1 + 6f * v * u * (x2 - x1) + 3f * u * u * (1f - x2)
                if (kotlin.math.abs(dx) < 1e-4f) break
                u = (u - (x - t) / dx).coerceIn(0f, 1f)
            }
            val v = 1f - u
            return 3f * v * u * u + u * u * u   // y1 = 0, y2 = 1
        }

        private fun transitionSeconds(from: JarvisState, to: JarvisState): Float = when {
            from == JarvisState.READY && to == JarvisState.LISTENING -> 0.45f
            from == JarvisState.LISTENING && to == JarvisState.THINKING -> 0.50f
            from == JarvisState.THINKING && to == JarvisState.SPEAKING -> 0.35f
            from == JarvisState.SPEAKING && to == JarvisState.READY -> 0.65f
            else -> 0.45f
        }
    }

    private val cur = TABLE[0].copyOf()
    private val from = TABLE[0].copyOf()
    private val target = TABLE[0].copyOf()
    private var transT = 1f
    private var transDur = 0.45f

    // Hidden by default: the reactor only exists on screen while JARVIS is active.
    // Three independent timelines (all linear 0..1, eased when applied, so reversing mid-flight never jumps).
    private var scaleT = 0f      // core size / presence (point <-> full size)
    private var lightT = 0f      // core light / glow
    private var edgeT = 0f       // left/right edge glow
    private var visTarget = 0f

    private var wavePhase = 0f
    private var patternPhase = 0f
    private var breathPhase = 0f

    var state = JarvisState.READY
        private set
    @Volatile var rawVoice = 0f
    /** Kept for API compatibility. The core no longer travels from the bottom; it blooms in place. */
    var hiddenOffsetPx = 0f

    /** 0..1 progress of the edge glow (already time-shaped; the renderer applies its own easing). */
    val edgeProgress: Float get() = edgeT
    /** 0..1 eased light level of the core. */
    val lightLevel: Float get() = ease(lightT)

    val isHidden: Boolean get() = scaleT <= 0f && lightT <= 0f && edgeT <= 0f && visTarget <= 0f
    /** True when only slow idle motion is running, so ~30 fps is enough. */
    val isIdleLowRate: Boolean
        get() = state == JarvisState.READY && transT >= 1f && scaleT >= 1f && lightT >= 1f && edgeT >= 1f && visTarget >= 1f

    fun setState(ns: JarvisState) {
        if (ns == state) return
        cur.copyInto(from)
        TABLE[ns.ordinal].copyInto(target)
        transDur = transitionSeconds(state, ns)
        transT = 0f
        state = ns
        if (isHidden) { target.copyInto(cur); transT = 1f }   // off-screen: no point blending
    }

    init { applyVisibility() }

    fun show() { visTarget = 1f }
    fun hide() { visTarget = 0f }
    fun setVisibleImmediately(v: Boolean) {
        val x = if (v) 1f else 0f
        scaleT = x; lightT = x; edgeT = x
        visTarget = x
        applyVisibility()
    }

    private fun wrap360(v: Float): Float { var r = v % 360f; if (r < 0f) r += 360f; return r }
    private fun frac(v: Float): Float = v - kotlin.math.floor(v)

    fun update(dt: Float) {
        if (transT < 1f) {
            transT = min(1f, transT + dt / transDur)
            val e = transT * transT * (3f - 2f * transT)
            for (i in 0 until N) cur[i] = from[i] + (target[i] - from[i]) * e
            s.transitionProgress = transT
        }
        s.outerRotation = wrap360(s.outerRotation + cur[0] * 360f * dt)
        for (i in 0..2) s.innerRotations[i] = wrap360(s.innerRotations[i] + cur[1 + i] * 360f * dt)
        s.scannerAngle = wrap360(s.scannerAngle + cur[4] * 360f * dt)
        s.scannerAlpha = cur[5]
        s.waveWeight = cur[7]
        s.patternWeight = cur[8]
        s.voiceWeight = cur[12]

        wavePhase = frac(wavePhase + 1.1f * dt)        // ~900 ms cycle
        patternPhase = frac(patternPhase + 0.30f * dt)
        breathPhase = frac(breathPhase + cur[11] * dt)
        s.segmentPhase = wavePhase
        s.patternPhase = patternPhase
        val breath = 0.5f - 0.5f * cos(TWO_PI * breathPhase)

        val raw = rawVoice
        s.voiceAmplitude = raw
        val k = 1f - Math.pow(0.82, (dt * 60f).toDouble()).toFloat()   // frame-rate independent
        val sm = s.smoothedVoiceAmplitude + (raw - s.smoothedVoiceAmplitude) * k
        s.smoothedVoiceAmplitude = sm
        val vw = cur[12]

        advanceTimeline(dt)
        val lE = ease(lightT)
        val flash = 4f * lightT * (1f - lightT)          // soft bloom flash, only while the light is changing
        s.coreScale = 1f + cur[9] + cur[10] * breath + sm * 0.08f * vw
        s.glow = min(1.2f, (cur[6] + 0.25f * sm * vw + 0.05f * breath) * (0.35f + 0.65f * lE) + 0.30f * flash)
        s.coreBrightness = min(1f, 0.70f + 0.30f * cur[6] + 0.25f * sm * vw)

        applyVisibility()
    }

    private fun advanceTimeline(dt: Float) {
        if (visTarget > 0f) {
            scaleT = min(1f, scaleT + dt / SCALE_UP)
            if (scaleT >= LIGHT_GATE) lightT = min(1f, lightT + dt / LIGHT_UP)
            if (lightT >= EDGE_GATE) edgeT = min(1f, edgeT + dt / EDGE_UP)
        } else {
            edgeT = max(0f, edgeT - dt / EDGE_DOWN)
            lightT = max(0f, lightT - dt / LIGHT_DOWN)
            if (lightT <= COLLAPSE_GATE) scaleT = max(0f, scaleT - dt / SCALE_DOWN)
        }
    }

    private fun applyVisibility() {
        val sE = ease(scaleT)
        val lE = ease(lightT)
        val p = (scaleT / 0.45f).coerceIn(0f, 1f)
        val presence = p * p * (3f - 2f * p)                 // fades in while the core is still small
        s.visibilityProgress = presence
        s.verticalEntryOffset = 0f                           // never enters from / returns to the bottom
        s.entryScale = 0.05f + 0.95f * sE                    // grows from, and folds back into, its own centre
        s.masterBrightness = presence * (0.30f + 0.70f * lE) // dim body first, then the light ignites
    }
}
