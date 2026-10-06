package com.jarvis.assistant.nlu

import kotlin.math.abs

/**
 * Recognizes the CALL_CONTACT intent from the STRUCTURE of a sentence, not from fixed phrases (Stage 46.6).
 *
 *   noun   : زنگ · تماس · تلفن · کال/call · شماره
 *   verb   : the verb that belongs to that noun (زنگ بزن · تماس بگیر/برقرار کن · تلفن بزن/کن · کال کن · شماره بگیر)
 *   name   : whatever is left once the call words, prepositions (به/با/برای), clitics (رو/را), filler and
 *            honorifics (آقای/خانم ... جان/جون) are removed -> contact_name (or phone_number for digits)
 *
 * so word order and politeness do not matter:
 *   «به علی زنگ بزن» · «به علی یه زنگ بزن» · «زنگ بزن به علی» · «با علی تماس بگیر» · «تماس بگیر با علی» ·
 *   «با علی تماس برقرار کن» · «به علی تلفن بزن» · «علی رو کال کن» · «شماره علی رو بگیر» · «میشه به علی زنگ بزنی» ·
 *   «میخوام به علی زنگ بزنم» · «تماس با علی» · «زنگ به علی» · «call ali»  ->  contact_name = علی
 *
 * Not a call: negations (زنگ نزن), scheduled/timed speech (ساعت، فردا، دقیقه), "alarm" speech (آلارم/زنگ بذار),
 * ringtone talk (صدای زنگ تلفن رو کم کن), device words as the "name" (تلفن رو خاموش کن), statements like
 * «باید زنگ بزنم» (confidence below the execution threshold).
 *
 * Input: normalized tokens of the RAW utterance (see [PersianNormalizer]); the keyword canonicalizer of
 * [CommandIntentParser] is deliberately not applied, so contact names are never "corrected" into keywords. Pure.
 */
class CallContactParser {

    class Match(val contactName: String?, val phoneNumber: String?, val confidence: Float)

    private enum class Form { IMPERATIVE, POLITE, FIRST_PERSON }

    fun match(rawTokens: List<String>): Match? {
        val tokens = expandGlued(rawTokens.filter { it !in FILLER })
        if (tokens.isEmpty() || tokens.size > MAX_TOKENS) return null
        if (tokens.any { it in NEGATIONS || it in TIME_WORDS }) return null

        // 1. The (noun, verb) pair: the closest pair wins ("به علی یه زنگ بزن", "زنگ بزن به علی").
        var nounIdx = -1
        var verbIdx = -1
        var form = Form.IMPERATIVE
        var gap = Int.MAX_VALUE
        for (i in tokens.indices) {
            val verbs = NOUN_VERBS[tokens[i]] ?: continue
            for (j in tokens.indices) {
                if (j == i) continue
                val f = verbs[tokens[j]] ?: continue
                val d = abs(i - j)
                if (d < gap) { gap = d; nounIdx = i; verbIdx = j; form = f }
            }
        }
        if (nounIdx < 0) return matchNounPhrase(tokens)

        val base = when (form) {
            Form.IMPERATIVE -> 0.95f
            Form.POLITE -> 0.92f
            // «میخوام زنگ بزنم» is a request, «باید زنگ بزنم» / «زنگ بزنم» is only a statement.
            Form.FIRST_PERSON -> if (tokens.any { it in DESIRE_WORDS }) 0.85f else 0.6f
        }

        // 2. Name / number = what remains.
        val rest = tokens.filterIndexed { i, tok -> i != nounIdx && i != verbIdx && tok !in NOISE }
        val name = stripHonorifics(rest)
        if (name.any { it in NOT_A_NAME }) return null

        if (name.any { tok -> tok.any { it.isDigit() } }) {
            if (!name.all { tok -> tok.all { it.isDigit() } }) return null
            val number = name.joinToString("")
            return if (number.length in 3..15) Match(null, number, base) else null
        }
        if (name.isEmpty()) {
            // «زنگ بزن» alone is clearly a call request without a target; "شماره بگیر" alone is not.
            return if (tokens[nounIdx] == "شماره") null else Match(null, null, minOf(base, 0.8f))
        }
        if (name.size > MAX_NAME_TOKENS) return null
        return Match(name.joinToString(" "), null, base)
    }

    /** «تماس با علی» / «زنگ به علی» / «یه زنگ به علی»: noun + its preposition + name, nothing else. */
    private fun matchNounPhrase(tokens: List<String>): Match? {
        for (i in tokens.indices) {
            val prep = when (tokens[i]) {
                "زنگ" -> "به"
                "تماس" -> "با"
                else -> continue
            }
            if (tokens.getOrNull(i + 1) != prep) continue
            if (!tokens.take(i).all { it in NOISE }) return null
            val name = stripHonorifics(tokens.drop(i + 2).filter { it !in NOISE })
            if (name.isEmpty() || name.size > 2) return null
            if (name.any { it in NOT_A_NAME || it.any { c -> c.isDigit() } }) return null
            return Match(name.joinToString(" "), null, 0.75f)
        }
        return null
    }

    private fun stripHonorifics(words: List<String>): List<String> {
        var from = 0
        var to = words.size
        while (from < to && words[from] in LEADING_HONORIFICS) from++
        while (to > from && words[to - 1] in TRAILING_HONORIFICS) to--
        return words.subList(from, to)
    }

    /** STT often glues the two words: "زنگبزن", "تماسبگیر", "کالکن". */
    private fun expandGlued(tokens: List<String>): List<String> {
        val out = ArrayList<String>(tokens.size + 2)
        for (tok in tokens) {
            val parts = GLUED[tok]
            if (parts == null) out += tok else out.addAll(parts)
        }
        return out
    }

    private companion object {
        const val MAX_TOKENS = 12
        const val MAX_NAME_TOKENS = 3

        fun forms(imperative: List<String>, polite: List<String>, first: List<String>): Map<String, Form> =
            HashMap<String, Form>().also { m ->
                imperative.forEach { m[it] = Form.IMPERATIVE }
                polite.forEach { m[it] = Form.POLITE }
                first.forEach { m[it] = Form.FIRST_PERSON }
            }

        val NOUN_VERBS: Map<String, Map<String, Form>> = mapOf(
            "زنگ" to forms(listOf("بزن"), listOf("بزنی", "بزنید", "بزنین"), listOf("بزنم", "بزنیم")),
            "تماس" to forms(
                listOf("بگیر", "برقرار", "بزن"),
                listOf("بگیری", "بگیرید", "بگیرین", "بزنی"),
                listOf("بگیرم", "بگیریم", "بزنم")
            ),
            "تلفن" to forms(
                listOf("بزن", "کن", "بکن"),
                listOf("بزنی", "کنی", "بزنید", "کنید"),
                listOf("بزنم", "کنم")
            ),
            "کال" to forms(listOf("کن", "بکن", "بزن"), listOf("کنی", "کنید"), listOf("کنم")),
            "call" to forms(listOf("کن", "بکن"), listOf("کنی", "کنید"), listOf("کنم")),
            "شماره" to forms(listOf("بگیر", "بزن"), listOf("بگیری", "بگیرید", "بزنی"), listOf("بگیرم", "بزنم"))
        )

        val GLUED: Map<String, List<String>> = HashMap<String, List<String>>().also { m ->
            for ((noun, verbs) in NOUN_VERBS) for (verb in verbs.keys) m.putIfAbsent(noun + verb, listOf(noun, verb))
        }

        val CALL_NOUNS = NOUN_VERBS.keys
        val ALL_VERBS: Set<String> = NOUN_VERBS.values.flatMap { it.keys }.toSet()

        val FILLER = setOf("جارویس", "جارویز", "هی", "لطفا", "ممنون", "مرسی", "ارباب", "بله")
        val NEGATIONS = setOf("نزن", "نزنی", "نکن", "نگیر", "نگیری", "نه", "نمیخوام", "نباید")
        val TIME_WORDS = setOf("ساعت", "دقیقه", "ثانیه", "فردا", "پسفردا", "بعدا", "آلارم", "الارم", "تایمر", "بیدارم")
        val DESIRE_WORDS = setOf("میخوام", "میخواهم", "میخواستم", "خواستم", "میشه", "بذار", "بزار", "بگذار", "بتونم")

        val PREPOSITIONS = setOf(
            "به", "با", "برای", "برا", "برام", "از", "رو", "را", "ی", "یه", "یک", "یکی", "تو", "توی", "اون", "این",
            "که", "هم", "الان", "همین", "حالا", "سریع", "میشه", "میتونی", "میتونید", "تونی", "ممکنه", "میخوام",
            "میخواهم", "میخواستم", "خواستم", "بذار", "بزار", "بگذار", "باید", "بتونم", "موبایل", "گوشی", "خط",
            "برقرار", "کن", "کنی", "کنید", "کنین", "بکن", "کنم"
        )
        val NOISE: Set<String> = PREPOSITIONS + CALL_NOUNS + ALL_VERBS

        val LEADING_HONORIFICS = setOf("آقای", "خانم", "خانوم", "آقا")
        val TRAILING_HONORIFICS = setOf("جان", "جون", "جانم", "عزیزم", "عزیز")

        /** Words that show the "name" is really something else: device switches, volume, questions ... */
        val NOT_A_NAME = setOf(
            "روشن", "خاموش", "قطع", "وصل", "فعال", "غیرفعال", "باز", "ببند", "صدا", "ولوم", "زیاد", "کم", "بیشتر",
            "کمتر", "بالا", "پایین", "تنظیمات", "وایفای", "بلوتوث", "چراغ", "قوه", "فلش", "صفحه", "برنامه", "اپ",
            "حالت", "پرواز", "سایلنت", "میوت", "سکوت", "چرا", "کی", "کجا", "چطور", "چی", "آیا", "اگه", "اگر", "چقدر",
            "صدای", "بعد", "قبل", "دیگه", "نمیتونم", "نمیتونی"
        )
    }
}
