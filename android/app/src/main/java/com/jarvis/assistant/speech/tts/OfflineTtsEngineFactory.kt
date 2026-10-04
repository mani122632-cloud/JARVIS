package com.jarvis.assistant.speech.tts

import android.content.Context
import android.util.Log

/**
 * Creates the offline engine without a compile-time dependency on sherpa-onnx, so the project builds
 * (and degrades gracefully) even when the sherpa-onnx files from tools/install-offline-assets.sh are absent.
 * [SherpaPiperEngine] only exists in the build when those files are present (see app/build.gradle).
 */
object OfflineTtsEngineFactory {
    private const val TAG = "JarvisTtsFactory"
    private const val ENGINE_CLASS = "com.jarvis.assistant.speech.tts.SherpaPiperEngine"

    sealed class Result {
        class Ready(val engine: OfflineTtsEngine) : Result()
        object EngineMissing : Result()      // sherpa-onnx library not part of this build
        object ModelMissing : Result()       // voice files not in the APK assets
        class Failed(val error: Throwable) : Result()
    }

    /** Blocking and heavy (copies the model on first run, loads it): worker thread only. */
    fun create(context: Context): Result {
        val cls = try { Class.forName(ENGINE_CLASS) } catch (e: ClassNotFoundException) { return Result.EngineMissing }
        if (!TtsModelInstaller.isBundled(context)) return Result.ModelMissing
        val files = TtsModelInstaller.install(context) ?: return Result.Failed(IllegalStateException("model install failed"))
        return try {
            val ctor = cls.getConstructor(String::class.java, String::class.java, String::class.java, Int::class.javaPrimitiveType)
            val engine = ctor.newInstance(files.model.absolutePath, files.tokens.absolutePath, files.dataDir.absolutePath, NUM_THREADS)
            Result.Ready(engine as OfflineTtsEngine)
        } catch (t: Throwable) {
            Log.e(TAG, "Could not create the offline TTS engine", t)
            Result.Failed(t.cause ?: t)
        }
    }

    private const val NUM_THREADS = 2
}
