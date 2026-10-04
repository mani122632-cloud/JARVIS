package com.jarvis.assistant.speech.tts

/** Mono 16-bit PCM produced by an engine. */
class PcmAudio(val samples: ShortArray, val sampleRate: Int)

/**
 * A loaded offline TTS model. Implementations are heavy: create ONE per service lifetime.
 * [synthesize] blocks (call it off the main thread) and must be called from one thread at a time.
 */
interface OfflineTtsEngine {
    /** Returns null when synthesis failed. Never throws. */
    fun synthesize(text: String, speed: Float): PcmAudio?

    /** Frees the model. The engine must not be used afterwards. */
    fun release()
}
