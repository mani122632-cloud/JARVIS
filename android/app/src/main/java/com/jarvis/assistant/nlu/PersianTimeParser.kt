package com.jarvis.assistant.nlu

import com.jarvis.assistant.command.DayPeriod
import java.time.LocalTime
import kotlin.math.ceil

/** A resolved wall-clock time. [confidence] is lower when AM/PM had to be guessed. */
data class ClockTime(val hour: Int, val minute: Int, val confidence: Float)

/**
 * Extracts TIME information from normalized Persian tokens (see [PersianNormalizer]). It knows nothing about
 * alarms or timers: it only answers "is there a duration / a clock time / a day / a part of the day in here?",
 * so the intent layer, the slot filler (answers like "هفت صبح") and any future tool can share it.
 *
 *  - [parseDuration]: "20 دقیقه", "بیست دقیقه", "یک ساعت و نیم", "نیم ساعت", "ربع ساعت", "2 ساعت و 30 دقیقه"
 *  - [parseClock]:    "ساعت 7", "7:30", "هفت و نیم", "هفت صبح", "صبح 7", "ساعت هشت و ربع شب", "ربع به 9"
 *  - [dayOffset]:     "امروز" / "امشب" = 0, "فردا" = 1, "پس فردا" = 2
 *  - [period]:        صبح / ظهر / بعد از ظهر / عصر / شب
 *
 * [clock] is injectable only so a bare hour ("ساعت 8", no صبح/شب) can be tested.
 */
class PersianTimeParser(private val clock: () -> LocalTime = { LocalTime.now() }) {

    /** Splits glued digit+word tokens ("20دقیقه" -> "20", "دقیقه"). Idempotent. */
    fun prepare(tokens: List<String>): List<String> =
        tokens.flatMap { tok ->
            DIGIT_LETTER.matchEntire(tok)?.let { listOf(it.groupValues[1], it.groupValues[2]) } ?: listOf(tok)
        }

    // ---- date / part of day ---------------------------------------------------------------------

    /** Days from today: null when no day word is present. */
    fun dayOffset(raw: List<String>): Int? {
        val t = prepare(raw)
        for (i in t.indices) {
            if (t[i] == "پسفردا" || (t[i] == "پس" && t.getOrNull(i + 1) == "فردا")) return 2
        }
        if (t.contains("فردا")) return 1
        if (t.contains("امروز") || t.contains("امشب")) return 0
        return null
    }

    fun period(raw: List<String>): DayPeriod? {
        val t = prepare(raw)
        for (i in t.indices) {
            if (t[i] == "بعدازظهر") return DayPeriod.AFTERNOON
            if (t[i] == "بعد" && t.getOrNull(i + 1) == "از" && t.getOrNull(i + 2) == "ظهر") return DayPeriod.AFTERNOON
        }
        for (tok in t) PERIOD_WORDS[tok]?.let { return it }
        return null
    }

    // ---- duration -------------------------------------------------------------------------------

    /** "5 دقیقه دیگه" / "بعد از 5 دقیقه": the duration is counted from now. */
    fun isRelative(raw: List<String>): Boolean {
        val t = prepare(raw)
        if (t.contains("دیگه") || t.contains("دیگر")) return true
        return t.indices.any { i ->
            t[i] == "بعد" && t.getOrNull(i + 1) == "از" &&
                (PersianNumbers.parse(t, i + 2) != null || t.getOrNull(i + 2)?.let { it in FRACTIONS } == true)
        }
    }

    /**
     * Total seconds of every "<number> <unit>" group, or null when there is none.
     * [bareUnitSeconds]: when the user answered a question like "برای چند دقیقه؟" with just a number ("بیست"),
     * that number is taken in this unit (60 = minutes).
     */
    fun parseDuration(raw: List<String>, bareUnitSeconds: Int? = null): Int? {
        val t = prepare(raw)
        fun frac(i: Int): Double? = t.getOrNull(i)?.let { FRACTIONS[it] }
        fun unit(i: Int): Int? = t.getOrNull(i)?.let { UNIT_SECONDS[it] }

        var total = 0L
        var found = false
        var i = 0
        while (i < t.size) {
            // "نیم ساعت", "ربع ساعت"
            val f = frac(i)
            if (f != null) {
                val u = unit(i + 1)
                if (u != null) {
                    total += (f * u).toLong()
                    found = true
                    i += 2
                    continue
                }
            }
            val num = PersianNumbers.parse(t, i)
            if (num != null) {
                var value = num.value.toDouble()
                var j = num.next
                // "یک و نیم ساعت"
                if (t.getOrNull(j) == "و" && frac(j + 1) != null && unit(j + 2) != null) {
                    value += frac(j + 1)!!
                    j += 2
                }
                val u = unit(j)
                if (u != null) {
                    total += (value * u).toLong()
                    found = true
                    j++
                    // "دو ساعت و نیم"
                    if (t.getOrNull(j) == "و" && frac(j + 1) != null) {
                        total += (frac(j + 1)!! * u).toLong()
                        j += 2
                    }
                    i = j
                    continue
                }
            }
            i++
        }
        if (found) return total.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

        if (bareUnitSeconds != null) {
            for (k in t.indices) {
                val num = PersianNumbers.parse(t, k) ?: continue
                return (num.value.toLong() * bareUnitSeconds).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            }
        }
        return null
    }

    /** The wall-clock time [seconds] from now, rounded UP to the next whole minute (alarms have no seconds). */
    fun afterDuration(seconds: Int): ClockTime {
        val now = clock()
        val totalSeconds = now.hour * 3600L + now.minute * 60L + now.second + seconds
        val minutes = ceil(totalSeconds / 60.0).toLong()
        val dayMinutes = ((minutes % 1440) + 1440) % 1440
        return ClockTime((dayMinutes / 60).toInt(), (dayMinutes % 60).toInt(), 0.9f)
    }

    // ---- clock time -----------------------------------------------------------------------------

    /**
     * A clock time inside the tokens, or null.
     *
     * Without [bare] an hour must be marked ("ساعت 8", "8 صبح", "صبح 8", "8:30"); with [bare] (the user is
     * answering "چه ساعتی؟") a lone number or "هفت و نیم" is enough.
     * [defaultPeriod] is used when the tokens themselves name no part of the day (it came from an earlier turn);
     * [preferMorning] resolves an unmarked 1..11 as AM (wake-up requests, "tomorrow").
     * With صبح/ظهر/عصر/شب the result is certain (0.95); a 24h hour 0.9; an unmarked hour is resolved to the
     * NEXT occurrence from now (0.85).
     */
    fun parseClock(
        raw: List<String>,
        bare: Boolean = false,
        defaultPeriod: DayPeriod? = null,
        preferMorning: Boolean = false
    ): ClockTime? {
        val t = prepare(raw)
        val period = period(t) ?: defaultPeriod
        for (i in t.indices) {
            val tok = t[i]

            TIME_REGEX.matchEntire(tok)?.let { m ->
                val h = m.groupValues[1].toInt()
                val min = m.groupValues[2].toInt()
                if (h <= 24 && min <= 59) return finishTime(h, min, 0, period, preferMorning)
            }

            // "ربع به 9"
            if (tok == "ربع" && t.getOrNull(i + 1) == "به") {
                val h = PersianNumbers.parse(t, i + 2)
                if (h != null && h.value in 1..24) return finishTime(h.value, 0, -15, period, preferMorning)
            }

            val num = PersianNumbers.parse(t, i) ?: continue

            // "10 دقیقه به 9"
            if (t.getOrNull(num.next) == "دقیقه" && t.getOrNull(num.next + 1) == "به" && num.value in 1..59) {
                val h = PersianNumbers.parse(t, num.next + 2)
                if (h != null && h.value in 1..24) return finishTime(h.value, 0, -num.value, period, preferMorning)
                continue
            }

            val next = t.getOrNull(num.next)
            val afterSaat = t.getOrNull(i - 1) == "ساعت"
            val afterPeriod = i > 0 && t[i - 1] in PERIOD_WORDS
            val beforePeriod = next != null && (next in PERIOD_WORDS || next == "بعد")
            if (!bare && !afterSaat && !afterPeriod && !beforePeriod) continue
            // "20 دقیقه" / "2 ساعت" is a length of time, not a clock time ("ساعت 2" is).
            if (!afterSaat && next != null && next in UNIT_SECONDS) continue
            if (num.value > 24) continue

            var minute = 0
            val j = num.next
            if (j + 1 < t.size && t[j] == "و") {
                val x = t[j + 1]
                if (x == "نیم") {
                    minute = 30
                } else if (x == "ربع") {
                    minute = 15
                } else {
                    val mn = PersianNumbers.parse(t, j + 1)
                    if (mn != null && mn.value in 0..59) minute = mn.value
                }
            }
            return finishTime(num.value, minute, 0, period, preferMorning)
        }
        return null
    }

    private fun finishTime(hourRaw: Int, minute: Int, offsetMinutes: Int, period: DayPeriod?, preferMorning: Boolean): ClockTime {
        val h = if (hourRaw == 24) 0 else hourRaw
        val hour24: Int
        val confidence: Float
        if (h > 12 || h == 0) {
            hour24 = h
            confidence = if (period == null && h > 12) 0.9f else 0.95f
        } else if (period != null) {
            hour24 = when (period) {
                DayPeriod.AM -> if (h == 12) 0 else h
                DayPeriod.NOON -> if (h in 1..6) h + 12 else h
                DayPeriod.AFTERNOON -> if (h < 12) h + 12 else h
                DayPeriod.NIGHT -> when {
                    h == 12 -> 0
                    h in 1..5 -> h
                    else -> h + 12
                }
            }
            confidence = 0.95f
        } else if (preferMorning && h in 1..11) {
            hour24 = h
            confidence = 0.9f
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

    private companion object {
        val TIME_REGEX = Regex("^(\\d{1,2}):(\\d{2})$")
        val DIGIT_LETTER = Regex("^(\\d+)(\\p{L}+)$")

        val FRACTIONS = mapOf("نیم" to 0.5, "ربع" to 0.25)
        val UNIT_SECONDS = mapOf(
            "ثانیه" to 1, "second" to 1, "seconds" to 1, "sec" to 1,
            "دقیقه" to 60, "minute" to 60, "minutes" to 60, "min" to 60,
            "ساعت" to 3600, "ساعته" to 3600, "hour" to 3600, "hours" to 3600
        )
        val PERIOD_WORDS = mapOf(
            "صبح" to DayPeriod.AM, "بامداد" to DayPeriod.AM, "سحر" to DayPeriod.AM,
            "ظهر" to DayPeriod.NOON,
            "بعدازظهر" to DayPeriod.AFTERNOON, "عصر" to DayPeriod.AFTERNOON,
            "شب" to DayPeriod.NIGHT, "امشب" to DayPeriod.NIGHT
        )
    }
}
