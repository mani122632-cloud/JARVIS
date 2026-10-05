package com.jarvis.assistant.speech.stt

import android.content.Context
import android.util.Log

/**
 * Creates the offline STT engine without a compile-time dependency on sherpa-onnx, so the project builds
 * (and degrades gracefully) when the files from tools/install-persian-stt.sh are absent.
 * [SherpaFarsiSttEngine] only exists in the build when the sherpa-onnx ASR Kotlin API is present
 * (see app/build.gradle).
 */
object OfflineSttEngineFactory {
    private const val TAG = "JarvisSttFactory"
    private const val ENGINE_CLASS = "com.jarvis.assistant.speech.stt.SherpaFarsiSttEngine"
    private const val NUM_THREADS = 2

    sealed class Result {
        class Ready(val engine: OfflineSttEngine) : Result()
        object EngineMissing : Result()      // sherpa-onnx ASR API not part of this build
        object ModelMissing : Result()       // model files not in the APK assets
        class Failed(val error: Throwable) : Result()
    }

    /** Blocking and heavy (copies the model on first run, loads it): worker thread only. */
    fun create(context: Context): Result {
        val cls = try {
            Class.forName(ENGINE_CLASS)
        } catch (e: ClassNotFoundException) {
            return Result.EngineMissing
        } catch (t: Throwable) {
            return Result.Failed(t)
        }
        if (!SttModelInstaller.isBundled(context)) return Result.ModelMissing
        val files = SttModelInstaller.install(context)
            ?: return Result.Failed(IllegalStateException("STT model install failed"))
        return try {
            val ctor = cls.getConstructor(String::class.java, String::class.java, Int::class.javaPrimitiveType)
            val engine = ctor.newInstance(files.model.absolutePath, files.tokens.absolutePath, NUM_THREADS)
            Result.Ready(engine as OfflineSttEngine)
        } catch (t: Throwable) {
            Log.e(TAG, "Could not create the offline STT engine", t)
            Result.Failed(t.cause ?: t)
        }
    }
}
