package com.jarvis.assistant.command

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Starts [intent] as a new task from a non-activity context. Shared by the executor and the tools, so the
 * failure handling (no app can handle it -> a spoken message, never a crash) lives in one place.
 */
internal fun launchActivity(app: Context, intent: Intent, failureMessage: String): JarvisActionExecutor.Outcome {
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return try {
        app.startActivity(intent)
        JarvisActionExecutor.Outcome(true)
    } catch (e: ActivityNotFoundException) {
        Log.w("JarvisActions", "No activity for $intent", e)
        JarvisActionExecutor.Outcome(false, failureMessage)
    }
}
