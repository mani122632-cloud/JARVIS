package com.jarvis.assistant.command

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.util.Log

/** Runs a [JarvisAction]. Never throws. */
class JarvisActionExecutor(context: Context) {

    private val app = context.applicationContext

    /** @param success false means the action did not happen; [message] is what JARVIS should say. */
    data class Outcome(val success: Boolean, val message: String? = null)

    fun execute(action: JarvisAction): Outcome = when (action) {
        is JarvisAction.OpenApp -> openApp(action)
        JarvisAction.DismissAssistant -> Outcome(true)
    }

    private fun openApp(action: JarvisAction.OpenApp): Outcome {
        val intent = try {
            app.packageManager.getLaunchIntentForPackage(action.packageName)
        } catch (e: RuntimeException) { null }
            ?: return Outcome(false, "${action.label} روی گوشی نصب نیست.")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return try {
            app.startActivity(intent)
            Outcome(true)
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "No activity for ${action.packageName}", e)
            Outcome(false, "${action.label} روی گوشی نصب نیست.")
        } catch (e: RuntimeException) {   // SecurityException, background start refused, ...
            Log.e(TAG, "Could not launch ${action.packageName}", e)
            Outcome(false, "نتوانستم ${action.label} را باز کنم.")
        }
    }

    private companion object { const val TAG = "JarvisActions" }
}
