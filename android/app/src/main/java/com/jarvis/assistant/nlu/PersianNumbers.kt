package com.jarvis.assistant.nlu

/**
 * Numbers inside a normalized token list: ASCII digits ("5") or Persian number words
 * ("پنج", "بیست و پنج", "صد و بیست"). Additive words only (no "هزار"): enough for minutes, hours,
 * percentages and clock times.
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
}
