package com.jarvis.assistant.speech.stt

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileNotFoundException

/**
 * Makes the bundled offline Persian STT model available as real files (the ONNX runtime needs paths).
 * Nothing is ever downloaded here.
 *
 * Expected in app/src/main/assets/[ASSET_DIR]/ (installed by tools/install-persian-stt.sh):
 *   model.onnx     NeMo FastConformer CTC, exported for sherpa-onnx
 *   tokens.txt     its token table
 *   model-id.txt   (optional) source + revision, used only to detect a changed model
 */
object SttModelInstaller {
    private const val TAG = "JarvisSttModel"
    const val ASSET_DIR = "stt-fa"
    const val MODEL = "model.onnx"
    const val TOKENS = "tokens.txt"
    const val MODEL_ID_FILE = "model-id.txt"

    class Files(val model: File, val tokens: File)

    fun isBundled(context: Context): Boolean = try {
        val names = context.assets.list(ASSET_DIR)?.toSet() ?: emptySet()
        MODEL in names && TOKENS in names
    } catch (e: Exception) { false }

    /**
     * Copies the model to internal storage once (again only when the bundled model changed).
     * Blocking: worker thread only. Returns null if the model is not bundled or copying failed.
     */
    fun install(context: Context): Files? {
        val app = context.applicationContext
        if (!isBundled(app)) return null
        val dir = File(app.filesDir, ASSET_DIR)
        val marker = File(dir, ".ok")
        val stamp = stamp(app)
        try {
            if (!(marker.exists() && marker.readText() == stamp && File(dir, MODEL).isFile && File(dir, TOKENS).isFile)) {
                dir.deleteRecursively()
                dir.mkdirs()
                copy(app, "$ASSET_DIR/$MODEL", File(dir, MODEL))
                copy(app, "$ASSET_DIR/$TOKENS", File(dir, TOKENS))
                marker.writeText(stamp)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Could not install the STT model", t)
            dir.deleteRecursively()
            return null
        }
        val f = Files(File(dir, MODEL), File(dir, TOKENS))
        return if (f.model.isFile && f.tokens.isFile) f else null
    }

    private fun stamp(app: Context): String {
        val size = try {
            app.assets.openFd("$ASSET_DIR/$MODEL").use { it.length }       // .onnx is stored uncompressed (noCompress)
        } catch (e: Exception) { -1L }
        val id = try {
            app.assets.open("$ASSET_DIR/$MODEL_ID_FILE").bufferedReader().use { it.readText().trim() }
        } catch (e: FileNotFoundException) { "" } catch (e: Exception) { "" }
        return "$size|$id"
    }

    private fun copy(app: Context, asset: String, dest: File) {
        app.assets.open(asset).use { input ->
            dest.outputStream().use { out -> input.copyTo(out, 256 * 1024) }
        }
    }
}
