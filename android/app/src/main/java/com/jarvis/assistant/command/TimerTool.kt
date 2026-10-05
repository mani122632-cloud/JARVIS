package com.jarvis.assistant.command

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import android.util.Log
import com.jarvis.assistant.nlu.PersianTimeFormat

/**
 * Creates AND STARTS a real countdown timer in the system clock app: `AlarmClock.ACTION_SET_TIMER` with
 * `EXTRA_LENGTH` and `EXTRA_SKIP_UI` (the timer starts immediately; the clock screen does not open). Needs the
 * normal permission `com.android.alarm.permission.SET_ALARM`. 1 second .. 24 hours.
 *
 * Success means the clock app accepted the request; Android gives no callback beyond that.
 */
class TimerTool(context: Context) : JarvisTool {

    private val app = context.applicationContext

    override val name: String = NAME

    override fun canHandle(action: JarvisAction): Boolean =
        action is JarvisAction.CreateTimer || (action is JarvisAction.ToolCall && action.tool == NAME)

    override fun execute(action: JarvisAction): JarvisActionExecutor.Outcome {
        val seconds = when (action) {
            is JarvisAction.CreateTimer -> action.seconds
            is JarvisAction.ToolCall -> action.args["seconds"]?.toIntOrNull()
                ?: action.args["minutes"]?.toIntOrNull()?.let { it * 60 }
                ?: -1
            else -> return JarvisActionExecutor.Outcome(false, FAILURE)
        }
        if (seconds !in 1..MAX_SECONDS) return JarvisActionExecutor.Outcome(false, BAD_TIME)

        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            .putExtra(AlarmClock.EXTRA_MESSAGE, LABEL)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        val launched = try {
            launchActivity(app, intent, NO_CLOCK_APP)
        } catch (e: SecurityException) {
            Log.w(TAG, "SET_ALARM permission missing", e)
            return JarvisActionExecutor.Outcome(false, NO_PERMISSION)
        }
        if (!launched.success) return launched
        return JarvisActionExecutor.Outcome(true, "حتماً ارباب، ${PersianTimeFormat.timerPhrase(seconds)} شروع شد.")
    }

    companion object {
        const val NAME = "timer"
        private const val TAG = "TimerTool"
        private const val LABEL = "JARVIS"
        private const val MAX_SECONDS = 86_400
        private const val FAILURE = "نتوانستم تایمر را شروع کنم."
        private const val BAD_TIME = "زمان را درست متوجه نشدم."
        private const val NO_CLOCK_APP = "برنامه ساعت برای تنظیم تایمر پیدا نشد."
        private const val NO_PERMISSION = "اجازه تنظیم تایمر به من داده نشده است."
    }
}
