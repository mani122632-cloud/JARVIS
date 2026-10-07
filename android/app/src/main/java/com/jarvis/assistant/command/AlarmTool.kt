package com.jarvis.assistant.command

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.AlarmClock
import android.util.Log
import com.jarvis.assistant.nlu.PersianTimeFormat
import java.time.LocalDateTime
import java.time.ZoneId

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
 *
 * Managing alarms ([JarvisAction.ManageAlarm]): every alarm created here is also written to an [AlarmRegistry],
 * because Android cannot list the clock app's alarms. Show = the registry. Switch off = `ACTION_DISMISS_ALARM`
 * (by time) and the registry entry stays, marked off. Delete = `DELETE_ALARM` on Android 16+; before that no
 * public API deletes a clock-app alarm, so it is switched off, removed from the registry and the user is told
 * honestly. The registry changes only when the clock app accepted the request.
 */
class AlarmTool(
    context: Context,
    private val now: () -> LocalDateTime = { LocalDateTime.now() },
    private val registry: AlarmRegistry = AlarmRegistry(context)
) : JarvisTool {

    private val app = context.applicationContext

    override val name: String = NAME

    override fun canHandle(action: JarvisAction): Boolean =
        action is JarvisAction.CreateAlarm || action is JarvisAction.ManageAlarm ||
            (action is JarvisAction.ToolCall && action.tool == NAME)

    override fun execute(action: JarvisAction): JarvisActionExecutor.Outcome {
        if (action is JarvisAction.ManageAlarm) return manage(action)
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
        try {
            registry.add(alarm.hour, alarm.minute, next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
        } catch (e: RuntimeException) {
            Log.w(TAG, "Alarm registry not updated", e)
        }

        val day = if (next.toLocalDate() != current.toLocalDate()) "فردا " else ""
        return JarvisActionExecutor.Outcome(
            true,
            "حتماً ارباب، آلارم ${day}ساعت ${PersianTimeFormat.clock(alarm.hour, alarm.minute)} تنظیم شد."
        )
    }

    // ---- manage (show / switch off / delete) ----------------------------------------------------

    private enum class Applied { DONE, DONE_PARTIAL, FAILED }

    private fun manage(a: JarvisAction.ManageAlarm): JarvisActionExecutor.Outcome {
        val all = registry.entries()
        if (a.op == AlarmOp.LIST) return list(all)

        if (a.all) {
            val targets = if (a.op == AlarmOp.DISABLE) all.filter { it.enabled } else all
            if (targets.isEmpty()) {
                return JarvisActionExecutor.Outcome(false, if (all.isEmpty()) NO_ALARMS else ALL_ALREADY_OFF)
            }
            val results = targets.map { apply(it, a.op) }
            val ok = results.count { it != Applied.FAILED }
            val partial = results.any { it == Applied.DONE_PARTIAL }
            return when {
                ok == 0 -> JarvisActionExecutor.Outcome(false, FAILURE_MANAGE)
                ok < targets.size -> JarvisActionExecutor.Outcome(false, "فقط $ok آلارم از ${targets.size} آلارم ${if (a.op == AlarmOp.DISABLE) "خاموش" else "حذف"} شد.")
                a.op == AlarmOp.DISABLE -> JarvisActionExecutor.Outcome(true, "حتماً ارباب، $ok آلارم خاموش شد.")
                partial -> JarvisActionExecutor.Outcome(true, "$ok آلارم را خاموش کردم و از فهرست جارویس حذف کردم. $NO_FULL_DELETE")
                else -> JarvisActionExecutor.Outcome(true, "حتماً ارباب، $ok آلارم حذف شد.")
            }
        }

        val hour = a.hour ?: return JarvisActionExecutor.Outcome(
            false,
            if (all.isEmpty()) NO_ALARMS else "کدام آلارم؟ ${describe(all)}. ساعتش را بگویید."
        )
        val matches = all.filter { it.minute == a.minute && (if (a.exact) it.hour == hour else it.hour % 12 == hour % 12) }
        val label = spoken(hour, a.minute, a.exact)
        if (matches.isEmpty()) {
            return JarvisActionExecutor.Outcome(false, "آلارمی ساعت $label که خودم تنظیم کرده باشم پیدا نکردم.")
        }
        val candidates = if (a.op == AlarmOp.DISABLE) matches.filter { it.enabled } else matches
        if (candidates.isEmpty()) {
            return JarvisActionExecutor.Outcome(true, "آلارم ساعت ${spoken(matches[0].hour, matches[0].minute, true)} از قبل خاموش است.")
        }
        if (candidates.size > 1) {
            return JarvisActionExecutor.Outcome(
                false,
                "چند آلارم شبیه هم دارم: ${describe(candidates)}. کدام را می‌گویید؟ مثلاً بگویید آلارم ${spoken(candidates[0].hour, candidates[0].minute, true)} را ${if (a.op == AlarmOp.DISABLE) "خاموش" else "حذف"} کن."
            )
        }
        val e = candidates[0]
        val at = spoken(e.hour, e.minute, true)
        return when (apply(e, a.op)) {
            Applied.FAILED -> JarvisActionExecutor.Outcome(false, FAILURE_MANAGE)
            Applied.DONE_PARTIAL -> JarvisActionExecutor.Outcome(true, "آلارم ساعت $at را خاموش کردم و از فهرست جارویس حذف کردم. $NO_FULL_DELETE")
            Applied.DONE -> JarvisActionExecutor.Outcome(
                true,
                if (a.op == AlarmOp.DISABLE) "حتماً ارباب، آلارم ساعت $at خاموش شد." else "حتماً ارباب، آلارم ساعت $at حذف شد."
            )
        }
    }

    private fun list(all: List<AlarmRegistry.Entry>): JarvisActionExecutor.Outcome {
        if (all.isEmpty()) return JarvisActionExecutor.Outcome(true, NO_ALARMS)
        val on = all.filter { it.enabled }
        val off = all.filter { !it.enabled }
        val parts = ArrayList<String>()
        if (on.isNotEmpty()) parts += "روشن: ${describe(on)}"
        if (off.isNotEmpty()) parts += "خاموش: ${describe(off)}"
        return JarvisActionExecutor.Outcome(true, "آلارم‌هایی که تنظیم کرده‌ام، ${parts.joinToString("، ")}.")
    }

    /** Runs the request in the clock app; the registry is updated only when the clock app accepted it. */
    private fun apply(e: AlarmRegistry.Entry, op: AlarmOp): Applied {
        if (op == AlarmOp.DISABLE) {
            if (!dismiss(e)) return Applied.FAILED
            registry.setEnabled(e.hour, e.minute, false)
            return Applied.DONE
        }
        // DELETE
        if (Build.VERSION.SDK_INT >= 36 && start(searchIntent(DELETE_ACTION, e))) {
            registry.remove(e.hour, e.minute)
            return Applied.DONE
        }
        if (e.enabled && !dismiss(e)) return Applied.FAILED
        registry.remove(e.hour, e.minute)
        return Applied.DONE_PARTIAL
    }

    private fun dismiss(e: AlarmRegistry.Entry): Boolean = start(searchIntent(AlarmClock.ACTION_DISMISS_ALARM, e))

    private fun searchIntent(action: String, e: AlarmRegistry.Entry): Intent = Intent(action)
        .putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_TIME)
        .putExtra(AlarmClock.EXTRA_HOUR, e.hour)
        .putExtra(AlarmClock.EXTRA_MINUTES, e.minute)

    private fun start(intent: Intent): Boolean = try {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        app.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        Log.w(TAG, "No clock app handles ${intent.action}", e)
        false
    } catch (e: SecurityException) {
        Log.w(TAG, "Alarm request refused", e)
        false
    }

    private fun describe(list: List<AlarmRegistry.Entry>): String =
        list.joinToString(" و ") { spoken(it.hour, it.minute, true) }

    /** "۷ صبح", "۷:۳۰ شب". With [exact] false only the number is said ("۷"), the part of the day was not given. */
    private fun spoken(hour: Int, minute: Int, exact: Boolean): String {
        val h12 = if (hour % 12 == 0) 12 else hour % 12
        val digits = if (minute == 0) "$h12" else "$h12:${minute.toString().padStart(2, '0')}"
        val period = when (hour) {
            0 -> "نیمه‌شب"
            in 1..11 -> "صبح"
            12 -> "ظهر"
            in 13..17 -> "بعدازظهر"
            else -> "شب"
        }
        val text = if (exact) "$digits $period" else digits
        return text.map { if (it in '0'..'9') ('۰' + (it - '0')) else it }.joinToString("")
    }

    companion object {
        const val NAME = "alarm"
        private const val DELETE_ACTION = "android.intent.action.DELETE_ALARM"      // AlarmClock.ACTION_DELETE_ALARM, API 36
        private const val NO_ALARMS = "آلارمی که خودم تنظیم کرده باشم ندارم."
        private const val ALL_ALREADY_OFF = "همهٔ آلارم‌هایی که تنظیم کرده‌ام از قبل خاموش‌اند."
        private const val FAILURE_MANAGE = "نتوانستم آلارم را تغییر بدهم، برنامه ساعت درخواست را نپذیرفت."
        private const val NO_FULL_DELETE = "اندروید اجازهٔ حذف کامل آلارم از برنامه ساعت را به برنامه‌ها نمی‌دهد."
        private const val TAG = "AlarmTool"
        private const val LABEL = "JARVIS"
        private const val FAILURE = "نتوانستم آلارم را تنظیم کنم."
        private const val BAD_TIME = "زمان را درست متوجه نشدم."
        private const val TOO_FAR = "فقط می‌توانم آلارم را برای ۲۴ ساعت آینده تنظیم کنم."
        private const val NO_CLOCK_APP = "برنامه ساعت برای تنظیم آلارم پیدا نشد."
        private const val NO_PERMISSION = "اجازه تنظیم آلارم به من داده نشده است."
    }
}
