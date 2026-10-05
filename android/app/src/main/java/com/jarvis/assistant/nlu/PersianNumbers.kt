package com.jarvis.assistant.nlu

/**
 * Numbers inside a normalized token list: ASCII digits ("5") or Persian number words
 * ("پنج", "بیست و پنج", "صد و بیست"). Additive words only (no "هزار"): enough for minutes, hours,
 * percentages and clock times. [toWords] is the reverse direction (7 -> "هفت") so JARVIS can say a time
 * without relying on the TTS to read digits.
 */
object PersianNumbers {

    data class Parsed(val value: Int, /** index of the first token after the number */ val next: Int)

    private val UNITS = mapOf(
        "صفر" to 0, "یک" to 1, "یه" to 1, "دو" to 2, "سه" to 3, "چهار" to 4, "پنج" to 5,
        "شش" to 6, "شیش" to 6, "هفت" to 7, "هشت" to 8, "نه" to 9, "ده" to 10, "یازده" to 11,
        "دوازده" to 12, "سیزده" to 13, "چهارده" to 14, "پانزده" to 15, "پونزده" to 15,
        "شانزده" to 16, "شونزده" to 16, "هفده" to 17, "هیفده" to 17, "هجده" to 18, "هیجده" to 18,
        "نوزده" to 19
    )
    private val TENS = mapOf(
        "بیست" to 20, "سی" to 30, "چهل" to 40, "پنجاه" to 50,
        "شصت" to 60, "هفتاد" to 70, "هشتاد" to 80, "نود" to 90
    )
    private val HUNDREDS = mapOf(
        "صد" to 100, "یکصد" to 100, "دویست" to 200, "سیصد" to 300, "چهارصد" to 400,
        "پانصد" to 500, "ششصد" to 600, "هفتصد" to 700, "هشتصد" to 800, "نهصد" to 900
    )

    private fun wordValue(t: String): Int? = UNITS[t] ?: TENS[t] ?: HUNDREDS[t]

    fun isNumberToken(t: String): Boolean = isDigits(t) || wordValue(t) != null

    private fun isDigits(t: String): Boolean = t.isNotEmpty() && t.length <= 6 && t.all { it in '0'..'9' }

    /** 0 = 0..19, 1 = tens, 2 = hundreds. */
    private fun kind(t: String): Int = when {
        HUNDREDS.containsKey(t) -> 2
        TENS.containsKey(t) -> 1
        else -> 0
    }

    /**
     * Parses a number starting at [start]; null if tokens[start] is not part of a number.
     * Word numbers combine only in valid Persian order (صد و بیست و پنج = 125); "هشت و بیست" is NOT 28 —
     * it stops after "هشت", so "ساعت هشت و بیست دقیقه" reads as 8:20.
     */
    fun parse(tokens: List<String>, start: Int): Parsed? {
        if (start < 0 || start >= tokens.size) return null
        val first = tokens[start]
        if (isDigits(first)) return Parsed(first.toInt(), start + 1)
        var total = wordValue(first) ?: return null
        var prevKind = kind(first)
        var i = start + 1
        while (i + 1 < tokens.size && tokens[i] == "و") {
            val nextWord = tokens[i + 1]
            val v = wordValue(nextWord) ?: break
            val k = kind(nextWord)
            val allowed = when (prevKind) {
                2 -> k <= 1 && v < 100
                1 -> k == 0 && v in 1..9
                else -> false
            }
            if (!allowed) break
            total += v
            prevKind = k
            i += 2
        }
        return Parsed(total, i)
    }

    // ---- number -> words ----------------------------------------------------------------------------

    private val UNIT_WORDS = arrayOf(
        "", "یک", "دو", "سه", "چهار", "پنج", "شش", "هفت", "هشت", "نه", "ده", "یازده", "دوازده",
        "سیزده", "چهارده", "پانزده", "شانزده", "هفده", "هجده", "نوزده"
    )
    private val TENS_WORDS = arrayOf("", "", "بیست", "سی", "چهل", "پنجاه", "شصت", "هفتاد", "هشتاد", "نود")
    private val HUNDRED_WORDS = arrayOf(
        "", "صد", "دویست", "سیصد", "چهارصد", "پانصد", "ششصد", "هفتصد", "هشتصد", "نهصد"
    )

    /** 0..999 as Persian words ("بیست و پنج"); anything else falls back to digits. */
    fun toWords(n: Int): String {
        if (n < 0 || n > 999) return n.toString()
        if (n == 0) return "صفر"
        val parts = ArrayList<String>(3)
        val hundreds = n / 100
        val rest = n % 100
        if (hundreds > 0) parts += HUNDRED_WORDS[hundreds]
        if (rest in 1..19) {
            parts += UNIT_WORDS[rest]
        } else if (rest >= 20) {
            parts += TENS_WORDS[rest / 10]
            if (rest % 10 > 0) parts += UNIT_WORDS[rest % 10]
        }
        return parts.joinToString(" و ")
    }
}
