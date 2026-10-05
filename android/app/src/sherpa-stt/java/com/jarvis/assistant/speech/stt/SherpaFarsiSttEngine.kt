package com.jarvis.assistant.speech.stt

import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.VersionInfo

/**
 * Persian speech recognition on sherpa-onnx: NeMo FastConformer CTC (Shenava Rizeh v1.0), fully local.
 *
 * Compiled ONLY when the sherpa-onnx ASR Kotlin API (OfflineRecognizer.kt, OfflineStream.kt, ...) and the
 * native library are present; see app/build.gradle and tools/install-persian-stt.sh.
 * [OfflineSttEngineFactory] finds this class by name.
 */
class SherpaFarsiSttEngine(
    modelPath: String,
    tokensPath: String,
    numThreads: Int
) : OfflineSttEngine {

    init {
        // Loads libsherpa-onnx-jni.so (+ libonnxruntime.so) and calls into it: a missing library or a Kotlin API that
        // does not match the .so throws UnsatisfiedLinkError here (caught by the factory) instead of dying later.
        Log.i(TAG, "sherpa-onnx native ${VersionInfo.version}, onnxruntime ${VersionInfo.onnxruntimeVersion}")
    }

    private val recognizer: OfflineRecognizer = OfflineRecognizer(
        assetManager = null,                              // real files in internal storage
        config = OfflineRecognizerConfig(
            modelConfig = OfflineModelConfig(
                nemo = OfflineNemoEncDecCtcModelConfig(model = modelPath),
                tokens = tokensPath,
                numThreads = numThreads,
                debug = false,
                provider = "cpu"
            ),
            decodingMethod = "greedy_search"
        )
    )

    override fun transcribe(samples: FloatArray, sampleRate: Int): String? {
        if (samples.isEmpty()) return null
        val stream = try { recognizer.createStream() } catch (t: Throwable) {
            Log.e(TAG, "createStream failed", t)
            return null
        }
        return try {
            stream.acceptWaveform(samples, sampleRate)
            recognizer.decode(stream)
            recognizer.getResult(stream).text
        } catch (t: Throwable) {
            Log.e(TAG, "decode failed", t)
            null
        } finally {
            try { stream.release() } catch (t: Throwable) { /* ignore */ }
        }
    }

    override fun release() {
        try { recognizer.release() } catch (t: Throwable) { Log.w(TAG, "release failed", t) }
    }

    private companion object {
        const val TAG = "JarvisSttEngine"
    }
}
