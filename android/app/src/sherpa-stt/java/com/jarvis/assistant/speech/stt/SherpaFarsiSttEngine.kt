package com.jarvis.assistant.speech.stt

import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.VersionInfo
import java.io.File

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
        // Native libraries first, in dependency order, so a missing/ABI-wrong library is reported by name.
        // (libsherpa-onnx-jni.so links against libonnxruntime.so.)
        loadNative("onnxruntime")
        loadNative("sherpa-onnx-jni")
        // Diagnostics only: a failure here must never be the reason the recognizer is not created.
        try {
            Log.i(TAG, "sherpa-onnx native ${VersionInfo.version}, onnxruntime ${VersionInfo.onnxruntimeVersion}")
        } catch (t: Throwable) {
            Log.w(TAG, "sherpa-onnx version info unavailable (continuing)", t)
        }
    }

    private val recognizer: OfflineRecognizer = createRecognizer(modelPath, tokensPath, numThreads)

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

        fun loadNative(name: String) {
            try {
                System.loadLibrary(name)
            } catch (e: UnsatisfiedLinkError) {
                // "onnxruntime" may legitimately be resolved only as a dependency of the JNI library.
                if (name == "onnxruntime") Log.w(TAG, "System.loadLibrary($name): ${e.message}")
                else throw e
            }
        }

        /**
         * The native recognizer picks its implementation from the ONNX metadata (`model_type`); when that key is
         * missing it aborts the whole process (exit), which Kotlin cannot catch. So the metadata is read first and,
         * if the file carries the NeMo CTC keys but no `model_type`, the type is given explicitly ("nemo_ctc").
         */
        fun createRecognizer(modelPath: String, tokensPath: String, numThreads: Int): OfflineRecognizer {
            val info = OnnxModelProbe.read(File(modelPath))
            Log.i(TAG, "model metadata: $info")
            val explicitType = if (info.modelType == null && info.looksLikeNemoCtc) "nemo_ctc" else ""
            if (explicitType.isNotEmpty()) Log.w(TAG, "model.onnx has no model_type metadata -> using modelType=$explicitType")

            return try {
                build(modelPath, tokensPath, numThreads, explicitType)
            } catch (e: IllegalArgumentException) {
                // The native side rejected the config (returns a null pointer). Try once with the type given
                // explicitly; if that also fails the original reason is reported.
                if (explicitType.isNotEmpty()) throw e
                Log.w(TAG, "recognizer rejected the default config; retrying with modelType=nemo_ctc", e)
                try { build(modelPath, tokensPath, numThreads, "nemo_ctc") } catch (e2: Throwable) { e.addSuppressed(e2); throw e }
            }
        }

        fun build(modelPath: String, tokensPath: String, numThreads: Int, modelType: String) = OfflineRecognizer(
            assetManager = null,                              // real files in internal storage
            config = OfflineRecognizerConfig(
                modelConfig = OfflineModelConfig(
                    nemo = OfflineNemoEncDecCtcModelConfig(model = modelPath),
                    tokens = tokensPath,
                    numThreads = numThreads,
                    debug = false,
                    provider = "cpu",
                    modelType = modelType
                ),
                decodingMethod = "greedy_search"
            )
        )
    }
}
