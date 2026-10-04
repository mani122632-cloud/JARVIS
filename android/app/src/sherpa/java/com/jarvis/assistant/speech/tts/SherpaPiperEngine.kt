package com.jarvis.assistant.speech.tts

import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig

/**
 * Piper voice (VITS + espeak-ng phonemizer) running on sherpa-onnx.
 *
 * This file is compiled ONLY when the sherpa-onnx Kotlin API and native libraries are present
 * (src/sherpa/java/com/k2fsa/sherpa/onnx/Tts.kt and src/main/jniLibs/<abi>/libsherpa-onnx-jni.so);
 * see app/build.gradle and tools/install-offline-assets.sh. [OfflineTtsEngineFactory] finds it by name.
 */
class SherpaPiperEngine(
    modelPath: String,
    tokensPath: String,
    dataDir: String,
    numThreads: Int
) : OfflineTtsEngine {

    private val tts: OfflineTts = OfflineTts(
        assetManager = null,                              // all files are real files in internal storage
        config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model = modelPath,
                    tokens = tokensPath,
                    dataDir = dataDir
                ),
                numThreads = numThreads,
                debug = false,
                provider = "cpu"
            )
        )
    )

    override fun synthesize(text: String, speed: Float): PcmAudio? {
        if (text.isBlank()) return null
        return try {
            val audio = tts.generate(text = text, sid = 0, speed = speed)
            val f = audio.samples
            if (f.isEmpty()) return null
            val pcm = ShortArray(f.size)
            for (i in f.indices) {
                val v = (f[i] * 32767f).toInt()
                pcm[i] = (if (v > 32767) 32767 else if (v < -32768) -32768 else v).toShort()
            }
            PcmAudio(pcm, audio.sampleRate)
        } catch (t: Throwable) {
            Log.e("SherpaPiperEngine", "generate failed", t)
            null
        }
    }

    override fun release() {
        try { tts.release() } catch (t: Throwable) { Log.w("SherpaPiperEngine", "release failed", t) }
    }
}
