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
        // One single timeline T (0..1) drives reactor, light and edge glow, so they always move in perfect sync.
        // Entry: off -> almost invisible (small, dim) -> quick but smooth ignite -> full.
        // Exit (T runs backwards): light dims -> energy off -> reactor folds into its own point -> gone.
        private const val SHOW_S = 0.75f
        private const val HIDE_S = 0.95f
        private const val GROW_END = 0.40f        // reactor size: T 0..0.40
        private const val LIGHT_START = 0.30f     // light / edge glow: T 0.30..0.85
        private const val LIGHT_END = 0.85f
        /** Total durations (ms) of the full show / hide. A host that removes the window by timer must wait at least this long. */
        const val SHOW_TOTAL_MS = 800L
        const val HIDE_TOTAL_MS = 1000L
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
    // Single linear timeline (eased when applied), so reversing mid-flight never jumps.
    private var timeline = 0f
    private var light = 0f       // 0..1 light level shared by reactor and edge glow
    private var visTarget = 0f

    private var energyPhasePrivate = 0f
    private var breathPhase = 0f

    var state = JarvisState.READY
        private set
    @Volatile var rawVoice = 0f
    /** Kept for API compatibility. The core no longer travels from the bottom; it blooms in place. */
    var hiddenOffsetPx = 0f

    /** Same value as [lightLevel]: the edge glow follows the reactor light exactly. */
    val edgeProgress: Float get() = light
    /** 0..1 light level shared by reactor and edge glow. */
    val lightLevel: Float get() = light
    /** 0..1 position inside one soft energy-pulse cycle. */
    val energyPhase: Float get() = energyPhasePrivate
    /** How deep the pulse is for the current state. */
    val energyDepth: Float get() = cur[6]

    val isHidden: Boolean get() = timeline <= 0f && visTarget <= 0f
    /** True when only slow idle motion is running, so ~30 fps is enough. */
    val isIdleLowRate: Boolean
        get() = state == JarvisState.READY && transT >= 1f && timeline >= 1f && visTarget >= 1f

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
        timeline = x
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
        // No rotation of any part: the reactor geometry is static; only light and energy move.
        s.waveWeight = cur[7]
        s.patternWeight = cur[8]
        s.voiceWeight = cur[12]

        breathPhase = frac(breathPhase + cur[11] * dt)
        energyPhasePrivate = frac(energyPhasePrivate + min(0.65f, cur[11] * 1.2f) * dt)   // soft pulse, capped at ~1.5 s per cycle
        val breath = 0.5f - 0.5f * cos(TWO_PI * breathPhase)

        val raw = rawVoice
        s.voiceAmplitude = raw
        val k = 1f - Math.pow(0.82, (dt * 60f).toDouble()).toFloat()   // frame-rate independent
        val sm = s.smoothedVoiceAmplitude + (raw - s.smoothedVoiceAmplitude) * k
        s.smoothedVoiceAmplitude = sm
        val vw = cur[12]

        advanceTimeline(dt)
        val lE = light
        val flash = 4f * light * (1f - light)            // soft ignition flash, only while the light is changing
        s.coreScale = 1f + cur[9] + cur[10] * breath + sm * 0.08f * vw
        s.glow = min(1.2f, (cur[6] + 0.25f * sm * vw + 0.05f * breath) * (0.35f + 0.65f * lE) + 0.22f * flash)
        s.coreBrightness = min(1f, 0.70f + 0.30f * cur[6] + 0.25f * sm * vw)

        applyVisibility()
    }

    private fun sstep(a: Float, b: Float, x: Float): Float {
        val u = ((x - a) / (b - a)).coerceIn(0f, 1f)
        return u * u * (3f - 2f * u)
    }

    private fun advanceTimeline(dt: Float) {
        timeline = if (visTarget > 0f) min(1f, timeline + dt / SHOW_S) else max(0f, timeline - dt / HIDE_S)
    }

    private fun applyVisibility() {
        val sE = ease((timeline / GROW_END).coerceIn(0f, 1f))
        light = sstep(LIGHT_START, LIGHT_END, timeline)
        val presence = sstep(0f, 0.20f, timeline)             // almost invisible at first
        s.visibilityProgress = presence
        s.verticalEntryOffset = 0f                            // never enters from / returns to the bottom
        s.entryScale = 0.05f + 0.95f * sE                     // grows from, and folds back into, its own centre
        s.masterBrightness = presence * (0.14f + 0.86f * light) // dim body first, then the light ignites
    }
}
