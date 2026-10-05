package com.jarvis.assistant.command

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import android.util.Log
import com.jarvis.assistant.nlu.PersianTimeFormat
import java.time.LocalDateTime

/**
 * Creates a REAL alarm in the system clock app: `AlarmClock.ACTION_SET_ALARM` with `EXTRA_SKIP_UI`, so the alarm
 * is stored and enabled without the clock screen opening (needs the normal permission
 * `com.android.alarm.permission.SET_ALARM` in the manifest).
 *
 * The clock app always schedules the NEXT occurrence of hour:minute. For "فردا ساعت ۷" that is only the right
 * day when the next occurrence really is tomorrow; if it is not (for example it is 05:00 now and 07:00 would be
 * today) or the day is further away, the alarm is NOT created and JARVIS says so, instead of setting a wrong one.
 *
 * Success means the clock app accepted the request; Android gives no callback beyond that.
 */
class AlarmTool(
    context: Context,
    private val now: () -> LocalDateTime = { LocalDateTime.now() }
) : JarvisTool {

    private val app = context.applicationContext

    override val name: String = NAME

    override fun canHandle(action: JarvisAction): Boolean =
        action is JarvisAction.CreateAlarm || (action is JarvisAction.ToolCall && action.tool == NAME)

    override fun execute(action: JarvisAction): JarvisActionExecutor.Outcome {
        val alarm = when (action) {
            is JarvisAction.CreateAlarm -> action
            is JarvisAction.ToolCall -> JarvisAction.CreateAlarm(
                action.args["hour"]?.toIntOrNull() ?: -1,
                action.args["minute"]?.toIntOrNull() ?: 0,
                action.args["dayOffset"]?.toIntOrNull() ?: 0
            )
            else -> return JarvisActionExecutor.Outcome(false, FAILURE)
        }
        if (alarm.hour !in 0..23 || alarm.minute !in 0..59 || alarm.dayOffset < 0) {
            return JarvisActionExecutor.Outcome(false, BAD_TIME)
        }

        val current = now()
        var next = current.toLocalDate().atTime(alarm.hour, alarm.minute)
        if (!next.isAfter(current)) next = next.plusDays(1)
        if (alarm.dayOffset >= 1 && next.toLocalDate() != current.toLocalDate().plusDays(alarm.dayOffset.toLong())) {
            return JarvisActionExecutor.Outcome(false, TOO_FAR)
        }

        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, alarm.hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, alarm.minute)
            .putExtra(AlarmClock.EXTRA_MESSAGE, LABEL)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        val launched = try {
            launchActivity(app, intent, NO_CLOCK_APP)
        } catch (e: SecurityException) {
            Log.w(TAG, "SET_ALARM permission missing", e)
            return JarvisActionExecutor.Outcome(false, NO_PERMISSION)
        }
        if (!launched.success) return launched

        val day = if (next.toLocalDate() != current.toLocalDate()) "فردا " else ""
        return JarvisActionExecutor.Outcome(
            true,
            "حتماً ارباب، آلارم ${day}ساعت ${PersianTimeFormat.clock(alarm.hour, alarm.minute)} تنظیم شد."
        )
    }

    companion object {
        const val NAME = "alarm"
        private const val TAG = "AlarmTool"
        private const val LABEL = "JARVIS"
        private const val FAILURE = "نتوانستم آلارم را تنظیم کنم."
        private const val BAD_TIME = "زمان را درست متوجه نشدم."
        private const val TOO_FAR = "فقط می‌توانم آلارم را برای ۲۴ ساعت آینده تنظیم کنم."
        private const val NO_CLOCK_APP = "برنامه ساعت برای تنظیم آلارم پیدا نشد."
        private const val NO_PERMISSION = "اجازه تنظیم آلارم به من داده نشده است."
    }
}
