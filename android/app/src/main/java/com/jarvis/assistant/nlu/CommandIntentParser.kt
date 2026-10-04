package com.jarvis.assistant.nlu

import com.jarvis.assistant.command.AppEntry
import com.jarvis.assistant.command.AppRegistry
import com.jarvis.assistant.command.JarvisAction
import com.jarvis.assistant.command.TorchMode
import com.jarvis.assistant.command.VolumeChange
import java.time.LocalTime

/**
 * Offline, deterministic Persian command parser (Stage 46.3). No AI / network / randomness.
 *
 * text -> PersianNormalizer -> every rule proposes a (action, confidence) -> the highest confidence wins
 * (ties: earlier rule). Nothing matched -> [JarvisAction.Unknown] with confidence 0.
 *
 * Add a command: write a `matchXxx(Input): Candidate?` and append it to [rules].
 * [clock] is injectable only so "ساعت ۸" (no صبح/شب) and "۵ دقیقه دیگه" can be tested.
 */
class CommandIntentParser(
    apps: AppRegistry = AppRegistry.default(),
    private val clock: () -> LocalTime = { LocalTime.now() }
) {

    data class Parsed(val action: JarvisAction, val confidence: Float, val normalized: String)

    private class Input(val text: String, val tokens: List<String>)
    private class Candidate(val action: JarvisAction, val confidence: Float)
    private class AliasEntry(val app: AppEntry, val tokens: List<String>)
    private data class ClockTime(val hour: Int, val minute: Int, val confidence: Float)
    private enum class Period { AM, NOON, AFTERNOON, NIGHT }

    private val aliases: List<AliasEntry> = apps.entries
        .flatMap { app -> app.aliases.map { AliasEntry(app, PersianNormalizer.tokens(it)) } }
        .filter { it.tokens.isNotEmpty() }
        .sortedByDescending { it.tokens.size }          // "گوگل کروم" before "کروم"

    private val rules: List<(Input) -> Candidate?> = listOf(
        this::matchDismiss,
        this::matchNavigation,
        this::matchFlashlight,
        this::matchVolume,
        this::matchTimer,
        this::matchAlarm,
        this::matchSettings,
        this::matchApp
    )

    fun parse(raw: String): Parsed {
        val tokens = PersianNormalizer.tokens(raw)
        val text = tokens.joinToString(" ")
        if (tokens.isEmpty()) return Parsed(JarvisAction.Unknown(raw), 0f, text)
        val input = Input(text, tokens)
        var best: Candidate? = null
        for (rule in rules) {
            val c = rule(input) ?: continue
            if (best == null || c.confidence > best.confidence) best = c
        }
        return if (best != null) Parsed(best.action, best.confidence, text)
        else Parsed(JarvisAction.Unknown(raw), 0f, text)
    }

    // ---- dismiss ------------------------------------------------------------------------------------

    private fun matchDismiss(inp: Input): Candidate? {
        val core = inp.tokens.filter { it !in FILLER }.joinToString(" ")
        return if (core in DISMISS_PHRASES) Candidate(JarvisAction.DismissAssistant, 0.95f) else null
    }

    // ---- navigation ---------------------------------------------------------------------------------

    private fun matchNavigation(inp: Input): Candidate? {
        val t = inp.tokens
        val homeWord = t.any { it == "هوم" || it == "home" } ||
            (t.contains("صفحه") && t.any { it == "اصلی" || it == "خانه" || it == "اول" })
        if (homeWord) {
            val verb = t.any { it in OPEN_VERBS || it in BACK_WORDS }
            return Candidate(JarvisAction.GoHome, if (verb) 0.95f else 0.88f)
        }
        val backWord = t.any { it in BACK_WORDS } ||
            (t.contains("صفحه") && t.any { it == "قبل" || it == "قبلی" })
        if (!backWord) return null
        val extra = t.filter { it !in NOISE && it !in BACK_WORDS && it !in BACK_PAGE_WORDS }
        return Candidate(JarvisAction.GoBack, if (extra.isEmpty()) 0.92f else 0.55f)
    }

    // ---- flashlight ---------------------------------------------------------------------------------

    private fun matchFlashlight(inp: Input): Candidate? {
        val t = inp.tokens
        val named = inp.text.contains("چراغ قوه") || t.any { it in FLASH_WORDS }
        if (!named) return null
        val on = t.any { it in ON_WORDS }
        val off = t.any { it in OFF_WORDS }
        return when {
            on && off -> null
            on -> Candidate(JarvisAction.ToggleFlashlight(TorchMode.ON), 0.95f)
            off -> Candidate(JarvisAction.ToggleFlashlight(TorchMode.OFF), 0.95f)
            else -> Candidate(JarvisAction.ToggleFlashlight(TorchMode.TOGGLE), 0.75f)
        }
    }

    // ---- volume -------------------------------------------------------------------------------------

    private class PercentHit(val value: Int, val explicit: Boolean)

    private fun matchVolume(inp: Input): Candidate? {
        val t = inp.tokens
        if (t.none { it in VOLUME_NAMES }) return null
        val up = t.any { it in UP_WORDS }
        val down = t.any { it in DOWN_WORDS }

        val pct = findPercent(t)
        if (pct != null) {
            if (up || down || pct.value !in 0..100) return null
            return Candidate(
                JarvisAction.SetVolume(VolumeChange.Percent(pct.value)),
                if (pct.explicit) 0.95f else 0.8f
            )
        }
        return when {
            up && down -> null
            up -> Candidate(JarvisAction.SetVolume(VolumeChange.Up), 0.95f)
            down -> Candidate(JarvisAction.SetVolume(VolumeChange.Down), 0.95f)
            t.any { it in MAX_WORDS } -> Candidate(JarvisAction.SetVolume(VolumeChange.Percent(100)), 0.9f)
            else -> null
        }
    }

    /** "50 درصد", "50%", "پنجاه درصد" (explicit) or "روی 50" / "50 کن" (implicit, lower confidence). */
    private fun findPercent(t: List<String>): PercentHit? {
        val hasSetVerb = t.any { it in SET_VERBS }
        for (i in t.indices) {
            val tok = t[i]
            if (tok.length in 2..4 && tok.endsWith("%") && tok.dropLast(1).all { it in '0'..'9' }) {
                return PercentHit(tok.dropLast(1).toInt(), true)
            }
            val num = PersianNumbers.parse(t, i) ?: continue
            val next = t.getOrNull(num.next)
            if (next == "درصد" || next == "%") return PercentHit(num.value, true)
            val isDigits = tok.all { it in '0'..'9' }
            if (hasSetVerb || (isDigits && t.contains("کن"))) return PercentHit(num.value, false)
        }
        return null
    }

    // ---- timer / alarm ------------------------------------------------------------------------------

    private fun matchTimer(inp: Input): Candidate? {
        val t = inp.tokens
        val named = t.any { it in TIMER_WORDS } || inp.text.contains("زمان سنج") || inp.text.contains("شمارش معکوس")
        if (!named) return null
        val secs = parseDurationSeconds(t) ?: return null
        if (secs !in 1..MAX_TIMER_SECONDS) return null
        return Candidate(JarvisAction.CreateTimer(secs), 0.95f)
    }

    private fun matchAlarm(inp: Input): Candidate? {
        val t = inp.tokens
        val named = t.any { it in ALARM_WORDS } ||
            (t.contains("زنگ") && t.any { it == "بذار" || it == "بزار" || it == "بگذار" || it == "تنظیم" })
        if (!named) return null

        // "۵ دقیقه دیگه آلارم بذار" -> now + duration, rounded UP to the next whole minute.
        if (t.contains("دیگه") || t.contains("دیگر")) {
            val secs = parseDurationSeconds(t)
            if (secs != null && secs in 1..MAX_TIMER_SECONDS) {
                var target = clock().withSecond(0).withNano(0).plusSeconds(secs.toLong())
                if (clock().second > 0 || clock().nano > 0) target = target.plusMinutes(1)
                return Candidate(JarvisAction.CreateAlarm(target.hour, target.minute), 0.9f)
            }
        }
        val time = parseClockTime(t) ?: return null
        return Candidate(JarvisAction.CreateAlarm(time.hour, time.minute), time.confidence)
    }

    /** Sum of "<number> <unit>" groups: "5 دقیقه", "یک ساعت و نیم", "نیم ساعت", "ربع ساعت", "2 ساعت و 30 دقیقه". */
    private fun parseDurationSeconds(t: List<String>): Int? {
        var total = 0L
        var found = false
        var i = 0
        while (i < t.size) {
            val frac = FRACTIONS[t[i]]
            if (frac != null && i + 1 < t.size) {
                val unit = UNIT_SECONDS[t[i + 1]]
                if (unit != null) {
                    total += (frac * unit).toLong()
                    found = true
                    i += 2
                    continue
                }
            }
            val num = PersianNumbers.parse(t, i)
            if (num != null && num.next < t.size) {
                val unit = UNIT_SECONDS[t[num.next]]
                if (unit != null) {
                    total += num.value.toLong() * unit
                    found = true
                    var j = num.next + 1
                    val extra = if (j + 1 < t.size && t[j] == "و") FRACTIONS[t[j + 1]] else null
                    if (extra != null) {
                        total += (extra * unit).toLong()
                        j += 2
                    }
                    i = j
                    continue
                }
            }
            i++
        }
        return if (found) total.coerceAtMost(Int.MAX_VALUE.toLong()).toInt() else null
    }

    /**
     * Clock time: "ساعت 8 صبح", "ساعت 8:30", "ساعت هشت و نیم شب", "8 و ربع", "ربع به 9", "10 دقیقه به 9".
     * With صبح/ظهر/عصر/شب the result is certain (0.95); with a 24h hour (13..23) 0.9;
     * a bare 1..12 is resolved to the NEXT occurrence from now (0.85).
     */
    private fun parseClockTime(t: List<String>): ClockTime? {
        val period = findPeriod(t)
        for (i in t.indices) {
            val tok = t[i]

            TIME_REGEX.matchEntire(tok)?.let { m ->
                val h = m.groupValues[1].toInt()
                val min = m.groupValues[2].toInt()
                if (h <= 24 && min <= 59) return finishTime(h, min, 0, period)
            }

            // "ربع به 9"
            if (tok == "ربع" && t.getOrNull(i + 1) == "به") {
                val h = PersianNumbers.parse(t, i + 2)
                if (h != null && h.value in 1..24) return finishTime(h.value, 0, -15, period)
            }

            val num = PersianNumbers.parse(t, i) ?: continue

            // "10 دقیقه به 9"
            if (t.getOrNull(num.next) == "دقیقه" && t.getOrNull(num.next + 1) == "به" && num.value in 1..59) {
                val h = PersianNumbers.parse(t, num.next + 2)
                if (h != null && h.value in 1..24) return finishTime(h.value, 0, -num.value, period)
                continue
            }

            val next = t.getOrNull(num.next)
            val afterSaat = t.getOrNull(i - 1) == "ساعت"
            val beforePeriod = next != null && (next in PERIOD_WORDS || next == "بعد")
            if (!afterSaat && !beforePeriod) continue
            if (num.value > 24) continue

            var minute = 0
            var j = num.next
            if (j + 1 < t.size && t[j] == "و") {
                val x = t[j + 1]
                if (x == "نیم") { minute = 30 } else if (x == "ربع") { minute = 15 } else {
                    val mn = PersianNumbers.parse(t, j + 1)
                    if (mn != null && mn.value in 0..59) minute = mn.value
                }
            }
            return finishTime(num.value, minute, 0, period)
        }
        return null
    }

    private fun finishTime(hourRaw: Int, minute: Int, offsetMinutes: Int, period: Period?): ClockTime {
        val h = if (hourRaw == 24) 0 else hourRaw
        val hour24: Int
        val confidence: Float
        if (h > 12 || h == 0) {
            hour24 = h
            confidence = if (period == null && h > 12) 0.9f else 0.95f
        } else if (period != null) {
            hour24 = when (period) {
                Period.AM -> if (h == 12) 0 else h
                Period.NOON -> if (h in 1..6) h + 12 else h
                Period.AFTERNOON -> if (h < 12) h + 12 else h
                Period.NIGHT -> when {
                    h == 12 -> 0
                    h in 1..5 -> h
                    else -> h + 12
                }
            }
            confidence = 0.95f
        } else {
            // No period: whichever of h:mm (am) / h:mm (pm) comes next.
            val now = clock()
            val nowMin = now.hour * 60 + now.minute
            val am = (h % 12) * 60 + minute
            val pm = am + 720
            fun delta(target: Int): Int = ((target - nowMin) % 1440 + 1440) % 1440
            fun wait(target: Int): Int = delta(target).let { if (it == 0) 1440 else it }
            hour24 = if (wait(am) <= wait(pm)) am / 60 else pm / 60
            confidence = 0.85f
        }
        val total = (((hour24 * 60 + minute + offsetMinutes) % 1440) + 1440) % 1440
        return ClockTime(total / 60, total % 60, confidence)
    }

    private fun findPeriod(t: List<String>): Period? {
        for (tok in t) PERIOD_WORDS[tok]?.let { return it }
        return null
    }

    // ---- settings -----------------------------------------------------------------------------------

    private fun matchSettings(inp: Input): Candidate? {
        val t = inp.tokens
        val text = inp.text
        val settingsWord = t.any { it in SETTINGS_WORDS }
        val wifi = text.contains("وای فای") || text.contains("وایفای") || text.contains("wifi") || text.contains("wi fi")
        val bluetooth = text.contains("بلوتوث") || text.contains("بلوتوس") || text.contains("bluetooth")
        val openVerb = t.any { it in OPEN_VERBS }
        if (wifi && bluetooth) return null
        if (wifi || bluetooth) {
            val confidence = when {
                settingsWord -> 0.95f
                openVerb -> 0.85f
                else -> return null          // "وای فای رو روشن کن": toggling is not possible from an app
            }
            return Candidate(
                if (wifi) JarvisAction.OpenWifiSettings else JarvisAction.OpenBluetoothSettings,
                confidence
            )
        }
        if (!settingsWord) return null
        val extra = t.filter { it !in NOISE && it !in SETTINGS_WORDS }
        val confidence = if (extra.isEmpty()) (if (openVerb) 0.95f else 0.88f) else 0.5f
        return Candidate(JarvisAction.OpenSettings, confidence)
    }

    // ---- apps ---------------------------------------------------------------------------------------

    private fun matchApp(inp: Input): Candidate? {
        val t = inp.tokens
        if (t.any { it in NEGATIONS || it in CLOSE_WORDS }) return null
        for (a in aliases) {
            val idx = indexOfAlias(t, a.tokens)
            if (idx < 0) continue
            val action = JarvisAction.OpenApp(a.app.id, a.app.label, a.app.packageNames)
            if (t.any { it in OPEN_VERBS }) return Candidate(action, 0.95f)
            val rest = t.filterIndexed { i, tok -> (i < idx || i >= idx + a.tokens.size) && tok !in FILLER && tok !in CLITICS }
            return if (rest.isEmpty()) Candidate(action, 0.85f) else null
        }
        return null
    }

    /** Index of [alias] inside [t]; a single-word alias may carry a colloquial clitic ("اینستاگرامو"). */
    private fun indexOfAlias(t: List<String>, alias: List<String>): Int {
        if (alias.size == 1) {
            val a = alias[0]
            for (i in t.indices) {
                val tok = t[i]
                if (tok == a) return i
                if (a.length >= 3 && tok.startsWith(a) && tok.substring(a.length) in CLITIC_SUFFIXES) return i
            }
            return -1
        }
        for (i in 0..t.size - alias.size) {
            if (alias.indices.all { t[i + it] == alias[it] }) return i
        }
        return -1
    }

    private companion object {
        const val MAX_TIMER_SECONDS = 86_400
        val TIME_REGEX = Regex("^(\\d{1,2}):(\\d{2})$")

        val FILLER = setOf("جارویس", "جارویز", "هی", "لطفا", "ممنون", "مرسی")
        val CLITICS = setOf("رو", "را", "ی", "به", "توی", "تو", "داخل", "در", "گوشی")
        val CLITIC_SUFFIXES = setOf("رو", "را", "و")
        val OPEN_VERBS = setOf("باز", "برو", "بیا", "اجرا", "بزن", "بیار", "بیاور", "open", "run", "start")
        val NOISE = FILLER + OPEN_VERBS + CLITICS +
            setOf("کن", "کنید", "بکن", "بذار", "بزار", "اون", "این", "یه")
        val NEGATIONS = setOf("نکن", "نه", "نمیخوام")
        val CLOSE_WORDS = setOf("ببند", "ببندش", "حذف", "پاک", "نصب", "آپدیت")

        val DISMISS_PHRASES = setOf(
            "هیچی", "ولش کن", "بیخیال", "بی خیال", "لغو", "کنسل", "خداحافظ", "بسه", "تمام",
            "مهم نیست", "فراموشش کن"
        )
        val BACK_WORDS = setOf("برگرد", "برگردید", "بازگشت", "عقب")
        val BACK_PAGE_WORDS = setOf("صفحه", "قبل", "قبلی")

        val FLASH_WORDS = setOf("چراغقوه", "فلش", "فلشلایت", "flashlight", "torch", "تورچ")
        val ON_WORDS = setOf("روشن", "بزن", "وصل", "فعال", "on")
        val OFF_WORDS = setOf("خاموش", "قطع", "غیرفعال", "off")

        val VOLUME_NAMES = setOf("صدا", "صدارو", "صداو", "صدای", "صدام", "ولوم", "volume")
        val UP_WORDS = setOf("زیاد", "زیادتر", "بیشتر", "بالا", "بلند", "بلندتر", "افزایش")
        val DOWN_WORDS = setOf("کم", "کمتر", "پایین", "آروم", "آرومتر", "آهسته", "کاهش")
        val MAX_WORDS = setOf("حداکثر", "ماکزیمم", "فول", "max")
        val SET_VERBS = setOf("بذار", "بزار", "بگذار", "تنظیم", "روی")

        val TIMER_WORDS = setOf("تایمر", "تایمیر", "timer", "زمانسنج")
        val ALARM_WORDS = setOf("آلارم", "الارم", "آلارام", "الارام", "alarm", "بیدارم")

        val FRACTIONS = mapOf("نیم" to 0.5, "ربع" to 0.25)
        val UNIT_SECONDS = mapOf(
            "ثانیه" to 1, "second" to 1, "seconds" to 1, "sec" to 1,
            "دقیقه" to 60, "minute" to 60, "minutes" to 60, "min" to 60,
            "ساعت" to 3600, "ساعته" to 3600, "hour" to 3600, "hours" to 3600
        )
        val PERIOD_WORDS = mapOf(
            "صبح" to Period.AM, "بامداد" to Period.AM, "سحر" to Period.AM,
            "ظهر" to Period.NOON,
            "بعدازظهر" to Period.AFTERNOON, "عصر" to Period.AFTERNOON,
            "شب" to Period.NIGHT, "امشب" to Period.NIGHT
        )
        val SETTINGS_WORDS = setOf("تنظیمات", "settings", "setting")
    }
}
