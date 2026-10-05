package com.jarvis.assistant.nlu

import com.jarvis.assistant.command.JarvisAction
import com.jarvis.assistant.command.SlotTarget

/**
 * Recognizes the two scheduling INTENTS, Alarm and Timer, from the STRUCTURE of the sentence rather than from
 * fixed phrases. Each utterance is reduced to a handful of independent features:
 *
 *   noun      : does it name the thing (آلارم / تایمر)?
 *   semantics : wake-up verb (بیدارم کن، بیدار شم)  /  remind-notify verb (یادم بنداز، خبرم کن، صدام کن)
 *   time      : duration ("20 دقیقه") · clock time ("ساعت 7") · day (فردا) · part of day (صبح)   [PersianTimeParser]
 *
 * and a small decision table maps feature combinations to an action:
 *
 *   Timer  = timer noun, OR (duration counted from now + notify verb)
 *            -> CreateTimer(seconds), or NeedsInfo(TIMER) when the duration is missing
 *   Alarm  = alarm noun, OR wake-up request, OR (remind verb + clock time)
 *            -> CreateAlarm(hour, minute, day), or NeedsInfo(ALARM) when the hour is missing
 *
 * so "آلارم بذار", "یه آلارم برام تنظیم کن", "فردا صبح بیدارم کن", "ساعت ۷ بیدارم کن" and
 * "یادم بنداز ساعت ۸ بیدار شم" all reach the same Alarm intent, and "تایمر بذار", "یه تایمر ۲۰ دقیقه‌ای می‌خوام",
 * "۲۰ دقیقه دیگه خبرم کن", "برای ۵ دقیقه تایمر بزن" the same Timer intent. The few word sets below are
 * grammatical CUES (nouns, verb stems), not a phrase list.
 *
 * Input: normalized tokens (see [PersianNormalizer]). Pure; the only clock access is inside [PersianTimeParser].
 */
class SchedulingIntentParser(private val time: PersianTimeParser) {

    class Match(val action: JarvisAction, val confidence: Float)

    fun match(rawTokens: List<String>): Match? {
        val t = time.prepare(rawTokens)
        if (t.isEmpty()) return null
        val joined = t.joinToString(" ")

        val timerNoun = t.any { it in TIMER_NOUNS } || joined.contains("زمان سنج") || joined.contains("شمارش معکوس")
        val alarmNoun = t.any { it in ALARM_NOUNS } || (t.contains("زنگ") && t.any { it in RING_SET_VERBS })
        val wakeWord = t.any { isWakeWord(it) }
        val remind = t.any { it in REMIND_WORDS }
        val notify = remind || t.any { it in NOTIFY_WORDS } || (t.contains("خبر") && t.any { it in GIVE_VERBS })
        if (!timerNoun && !alarmNoun && !wakeWord && !notify) return null
        if (timerNoun && alarmNoun) return null                       // "آلارم و تایمر": ambiguous, do nothing

        val duration = time.parseDuration(t)
        val relative = duration != null && time.isRelative(t)
        val dayOffset = time.dayOffset(t)
        val period = time.period(t)
        val show = t.any { it in SHOW_VERBS }

        // ---- Timer ----
        if (timerNoun) {
            if (duration == null) {
                return if (show) Match(JarvisAction.OpenTimerScreen, 0.85f)
                else Match(JarvisAction.NeedsInfo(SlotTarget.TIMER), 0.9f)
            }
            if (duration !in 1..MAX_TIMER_SECONDS) return null
            return Match(JarvisAction.CreateTimer(duration), 0.95f)
        }
        // "20 دقیقه دیگه خبرم کن" / "بعد از 5 دقیقه یادم بنداز": a countdown without the word "تایمر".
        if (relative && !alarmNoun && !wakeWord && notify) {
            if (duration!! !in 1..MAX_TIMER_SECONDS) return null
            return Match(JarvisAction.CreateTimer(duration), 0.92f)
        }

        // ---- Alarm ----
        val preferMorning = wakeWord || (dayOffset ?: 0) >= 1
        val clock = time.parseClock(t, bare = false, defaultPeriod = null, preferMorning = preferMorning)
        // "بیداری؟" (are you awake) is not a request: a wake-up verb needs a request marker or a clock time.
        val wakeRequest = wakeWord && (clock != null || t.any { it in WAKE_MARKERS })
        val asksAlarm = alarmNoun || wakeRequest || (remind && clock != null)
        if (!asksAlarm) return null

        // "20 دقیقه دیگه بیدارم کن" -> now + duration.
        if (relative && (alarmNoun || wakeRequest)) {
            if (duration!! !in 1..MAX_TIMER_SECONDS) return null
            val at = time.afterDuration(duration)
            return Match(JarvisAction.CreateAlarm(at.hour, at.minute, 0), 0.9f)
        }
        if (clock != null) {
            val sure = alarmNoun || wakeRequest
            val confidence = if (sure) clock.confidence else minOf(clock.confidence, 0.75f)
            return Match(JarvisAction.CreateAlarm(clock.hour, clock.minute, dayOffset ?: 0), confidence)
        }
        if (show && alarmNoun) return Match(JarvisAction.OpenAlarmScreen, 0.85f)
        return Match(JarvisAction.NeedsInfo(SlotTarget.ALARM, dayOffset ?: 0, period, preferMorning), 0.9f)
    }

    private fun isWakeWord(tok: String): Boolean =
        tok.startsWith("بیدار") && tok != "بیداری" && tok != "بیدارید" && tok != "بیدارین"

    private companion object {
        const val MAX_TIMER_SECONDS = 86_400

        val TIMER_NOUNS = setOf("تایمر", "تایمیر", "timer", "زمانسنج")
        val ALARM_NOUNS = setOf("آلارم", "الارم", "آلارام", "الارام", "alarm")
        val RING_SET_VERBS = setOf("بذار", "بزار", "بگذار", "تنظیم")
        /** The request part of "بیدارم کن / بیدار شم / بیدارم کنی". */
        val WAKE_MARKERS = setOf("کن", "کنی", "بکن", "کنید", "شم", "بشم", "بشیم", "بشو", "بنداز", "بده", "میخوام", "میخواهم")
        val REMIND_WORDS = setOf("یادم", "یادآوری", "یادآور")
        val NOTIFY_WORDS = setOf("خبرم", "صدام", "اطلاعم", "هشدار")
        val GIVE_VERBS = setOf("بده", "بدی", "بزن")
        /** Only "show me" verbs: "تایمر بزن" must create a timer, not open the clock. */
        val SHOW_VERBS = setOf("باز", "بازکن", "نشون", "نشان", "لیست", "ببینم", "اجرا")
    }
}
