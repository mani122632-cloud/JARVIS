package com.jarvis.assistant.core

/** Plain mutable values read by the renderer. Written only by CoreAnimationController. */
class CoreAnimationState {
    @JvmField var outerRotation = 0f            // degrees
    @JvmField val innerRotations = FloatArray(3) // degrees, 3 arc/ring families
    @JvmField var scannerAngle = 0f             // degrees
    @JvmField var scannerAlpha = 0f
    @JvmField var coreScale = 1f
    @JvmField var coreBrightness = 0.75f
    @JvmField var glow = 0.55f
    @JvmField var segmentPhase = 0f             // 0..1 traveling wave
    @JvmField var patternPhase = 0f             // 0..1 thinking pattern
    @JvmField var waveWeight = 0f
    @JvmField var patternWeight = 0f
    @JvmField var voiceWeight = 0f
    @JvmField var voiceAmplitude = 0f
    @JvmField var smoothedVoiceAmplitude = 0f
    @JvmField var transitionProgress = 1f
    @JvmField var visibilityProgress = 1f       // eased 0..1
    @JvmField var verticalEntryOffset = 0f      // px, >0 = below final position
    @JvmField var entryScale = 1f
    @JvmField var masterBrightness = 1f
}
