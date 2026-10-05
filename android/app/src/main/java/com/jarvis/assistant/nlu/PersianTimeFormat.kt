package com.jarvis.assistant.nlu

/**
 * Times and durations as spoken Persian words, for JARVIS's confirmations ("آلارم ساعت هفت و نیم صبح تنظیم شد.").
 * Words, not digits: the offline TTS then never has to guess how to read "7:30".
 */
object PersianTimeFormat {

    /** 7:30 -> "هفت و نیم صبح", 19:15 -> "هفت و ربع شب", 0:00 -> "دوازده شب". Without the word "ساعت". */
    fun clock(hour24: Int, minute: Int): String {
        val h12 = (hour24 % 12).let { if (it == 0) 12 else it }
        val minutePart = when (minute) {
            0 -> ""
            15 -> " و ربع"
            30 -> " و نیم"
            else -> " و ${PersianNumbers.toWords(minute)} دقیقه"
        }
        val period = when (hour24) {
            0 -> "شب"
            in 1..4 -> "بامداد"
            in 5..11 -> "صبح"
            12 -> "ظهر"
            in 13..15 -> "بعد از ظهر"
            in 16..18 -> "عصر"
            else -> "شب"
        }
        return "${PersianNumbers.toWords(h12)}$minutePart $period"
    }

    /** 1200 -> "بیست دقیقه", 5400 -> "یک ساعت و سی دقیقه". */
    fun duration(totalSeconds: Int): String {
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        val parts = ArrayList<String>(3)
        if (h > 0) parts += "${PersianNumbers.toWords(h)} ساعت"
        if (m > 0) parts += "${PersianNumbers.toWords(m)} دقیقه"
        if (s > 0) parts += "${PersianNumbers.toWords(s)} ثانیه"
        return parts.joinToString(" و ")
    }

    /** "تایمر بیست دقیقه‌ای" for a single unit, otherwise "تایمر برای یک ساعت و سی دقیقه". */
    fun timerPhrase(totalSeconds: Int): String {
        val d = duration(totalSeconds)
        return if (d.contains(" و ")) "تایمر برای $d" else "تایمر $d\u200cای"
    }
}
