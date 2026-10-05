package com.jarvis.assistant.speech.stt

/**
 * A loaded offline speech-to-text model. Heavy: ONE instance per service lifetime (see [OfflinePersianStt]).
 * [transcribe] blocks (worker thread only) and is never called from two threads at once.
 */
interface OfflineSttEngine {
    /** [samples]: mono PCM floats in -1..1. Returns the raw transcript, or null when decoding failed. Never throws. */
    fun transcribe(samples: FloatArray, sampleRate: Int): String?

    /** Frees the model. The engine must not be used afterwards. */
    fun release()
}
