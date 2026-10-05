package com.jarvis.assistant.speech.stt

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File

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

        Log.i(TAG, "ABIs=${Build.SUPPORTED_ABIS.joinToString()} model=${files.model.length()} bytes tokens=${files.tokens.length()} bytes")
        preflight(files)?.let { return Result.Failed(IllegalStateException(it)) }

        // A native abort (exit()/SIGSEGV/OOM-kill inside sherpa-onnx or ONNX Runtime) cannot be caught in Kotlin and
        // kills the process. The marker survives such a death and counts the attempts that died. It must NEVER make
        // the voice mode unavailable for good (one low-memory kill while Vosk + Gyro + STT were loading used to do
        // exactly that): only [MAX_NATIVE_CRASHES] dead attempts of this build within [CRASH_MEMORY_MS] block a
        // retry, then the counter expires by itself. A new install/update starts clean.
        val marker = File(files.model.parentFile, MARKER)
        val build = buildStamp(context)
        val deadAttempts = readDeadAttempts(marker, build)
        if (deadAttempts >= MAX_NATIVE_CRASHES) {
            Log.e(TAG, "The native load of the STT model killed the process $deadAttempts times in a row " +
                "(model/native library incompatible or out of memory). Pausing retries; see logcat tag JarvisSttEngine.")
            return Result.Failed(IllegalStateException("previous native STT loads crashed the process"))
        }
        try { marker.writeText("$build|${deadAttempts + 1}|${System.currentTimeMillis()}") } catch (t: Throwable) { Log.w(TAG, "could not write load marker", t) }

        return try {
            val ctor = cls.getConstructor(String::class.java, String::class.java, Int::class.javaPrimitiveType)
            val engine = ctor.newInstance(files.model.absolutePath, files.tokens.absolutePath, NUM_THREADS)
            Result.Ready(engine as OfflineSttEngine)
        } catch (t: Throwable) {
            Log.e(TAG, "Could not create the offline STT engine", t)
            Result.Failed(t.cause ?: t)
        } finally {
            marker.delete()               // control came back to Kotlin: the load did not kill the process
        }
    }

    /** Number of earlier load attempts of THIS build that never came back (process died); 0 if none / stale / other build. */
    private fun readDeadAttempts(marker: File, build: String): Int {
        if (!marker.isFile) return 0
        val parts = readQuietly(marker).split('|')
        if (parts.size < 3 || parts[0] != build) return 0
        val count = parts[1].toIntOrNull() ?: return 0
        val at = parts[2].toLongOrNull() ?: return 0
        val age = System.currentTimeMillis() - at
        return if (age < 0 || age > CRASH_MEMORY_MS) 0 else count
    }

    /** Cheap sanity checks that turn an obviously broken install into a log line instead of a native abort. */
    private fun preflight(files: SttModelInstaller.Files): String? {
        if (files.model.length() < MIN_MODEL_BYTES) return "model.onnx is only ${files.model.length()} bytes (truncated copy or Git LFS pointer)"
        val tokensOk = try {
            files.tokens.bufferedReader().useLines { lines -> lines.any { it.isNotBlank() } }
        } catch (t: Throwable) { false }
        if (!tokensOk) return "tokens.txt is empty or unreadable"
        return null
    }

    private fun buildStamp(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime.toString()
    } catch (t: Throwable) { "0" }

    private fun readQuietly(f: File): String = try { f.readText().trim() } catch (t: Throwable) { "" }

    private const val MARKER = ".native-load"
    private const val MAX_NATIVE_CRASHES = 3
    private const val CRASH_MEMORY_MS = 30L * 60_000L
    private const val MIN_MODEL_BYTES = 50_000_000L
}
