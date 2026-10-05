package com.jarvis.assistant.speech.tts

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileNotFoundException

/**
 * Makes the bundled offline Persian voice available as real files (the ONNX runtime and espeak-ng
 * need paths, not asset streams). Nothing is ever downloaded here.
 *
 * Expected in app/src/main/assets/[ASSET_DIR]/:
 *   model.onnx          the Piper VITS voice
 *   tokens.txt          its phoneme table
 *   espeak-ng-data/     espeak-ng data directory
 * (see INTEGRATION.md and tools/install-offline-assets.sh)
 */
object TtsModelInstaller {
    private const val TAG = "JarvisTtsModel"
    const val ASSET_DIR = "tts-fa"
    const val MODEL = "model.onnx"
    const val TOKENS = "tokens.txt"
    const val DATA_DIR = "espeak-ng-data"

    /** The voice JARVIS is configured for (installed by tools/install-gyro-voice.sh as assets/tts-fa/voice-id.txt). */
    const val VOICE_ID = "vits-piper-fa_IR-gyro-medium"
    const val VOICE_ID_FILE = "voice-id.txt"

    class Files(val model: File, val tokens: File, val dataDir: File)

    /** True when the three required assets are inside the APK. */
    fun isBundled(context: Context): Boolean = try {
        val names = context.assets.list(ASSET_DIR)?.toSet() ?: emptySet()
        MODEL in names && TOKENS in names && DATA_DIR in names
    } catch (e: Exception) { false }

    /**
     * Copies the voice to internal storage once (re-copies only if the bundled model changed).
     * Blocking: call from a worker thread. Returns null if the voice is not bundled or copying failed.
     */
    fun install(context: Context): Files? {
        val app = context.applicationContext
        if (!isBundled(app)) return null
        val dir = File(app.filesDir, ASSET_DIR)
        val marker = File(dir, ".ok")
        val stamp = stamp(app)
        try {
            if (!(marker.exists() && marker.readText() == stamp)) {
                dir.deleteRecursively()
                copyAsset(app, ASSET_DIR, dir)
                marker.writeText(stamp)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Could not install the voice model", t)
            dir.deleteRecursively()
            return null
        }
        val f = Files(File(dir, MODEL), File(dir, TOKENS), File(dir, DATA_DIR))
        return if (f.model.isFile && f.tokens.isFile && f.dataDir.isDirectory) f else null
    }

    /** Size of the bundled model: changes when the model is replaced, not on every app update. */
    private fun stamp(app: Context): String = try {
        // Includes the voice id, so swapping the old voice for Gyro always re-copies the model.
        val id = try { app.assets.open("$ASSET_DIR/$VOICE_ID_FILE").bufferedReader().use { it.readText().trim() } } catch (e: Exception) { "" }
        app.assets.openFd("$ASSET_DIR/$MODEL").use { "len=${it.length};voice=$id" }
    } catch (e: Exception) {
        try { "upd=" + app.packageManager.getPackageInfo(app.packageName, 0).lastUpdateTime } catch (e2: Exception) { "0" }
    }

    private fun copyAsset(app: Context, path: String, dest: File) {
        val children = app.assets.list(path) ?: emptyArray()
        if (children.isNotEmpty()) {
            dest.mkdirs()
            for (name in children) copyAsset(app, "$path/$name", File(dest, name))
            return
        }
        try {
            dest.parentFile?.mkdirs()
            app.assets.open(path).use { input -> dest.outputStream().use { input.copyTo(it, 64 * 1024) } }
        } catch (e: FileNotFoundException) {
            dest.mkdirs()                      // an empty directory
        }
    }
}
