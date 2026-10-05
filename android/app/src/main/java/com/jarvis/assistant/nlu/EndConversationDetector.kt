package com.jarvis.assistant.nlu

/**
 * Detects the END_CONVERSATION intent: "the user is finished and wants the session to close".
 *
 * It does NOT compare the sentence with a list of goodbyes. A closing is recognized by the ROLE words play in
 * it, so natural variants that were never written down still work:
 *
 *   FAREWELL   a farewell stem            خداحافظ(ی) · خدا نگهدار(ت) · فعلاً · بای · مراقب خودت باش · به امید دیدار
 *   NOTHING    "no more needs" structure  [دیگه] + need-noun (کار/چیز/سوال/حرف/دستور) + negated "have"
 *                                          (دیگه کاری ندارم · چیزی نمیخوام · سوالی ندارم)
 *   DONE       completion word, short     تموم شد · کافیه · بسه · تمام · همین بود
 *   LEAVING    first-person departure     من دیگه میرم · دارم میرم · رفتم · برم
 *   DEFERRAL   "later" + talk/see verb    بعداً صحبت می‌کنیم · فردا می‌بینمت · تا بعد
 *   REST       استراحت کن (go rest)
 *
 * A good-night wish ("شب بخیر") never ends the session alone, but combined with any of the above it does
 * ("شب بخیر، من میرم"), and JARVIS answers it in kind. Thanks alone ("ممنون") is not an ending either.
 *
 * Precision over recall: long sentences and sentences with digits are never endings, and the Brain asks the
 * device-command parser first, so "تایمر تموم شد" or "ساعت ۸ برم دکتر" are not mistaken for a goodbye.
 * Pure, offline, no state.
 */
object EndConversationDetector {

    data class Result(val confidence: Float, val goodNight: Boolean)

    fun detect(raw: String): Result? {
        val tokens = glueVerbPrefix(PersianNormalizer.tokens(raw))
        if (tokens.isEmpty() || tokens.size > MAX_TOKENS) return null
        if (tokens.any { tok -> tok.any { it.isDigit() } }) return null

        val core = tokens.filter { it !in POLITE }
        if (core.isEmpty()) return null                      // only thanks / filler

        // "شب بخیر" / "روز بخیر": a wish. Removed from the sentence, remembered for the reply.
        var goodNight = false
        val rest = ArrayList<String>(core.size)
        for (i in core.indices) {
            val tok = core[i]
            if (tok == "بخیر") continue
            if (tok in WISH_PARTS && core.getOrNull(i + 1) == "بخیر") {
                if (tok == "شب") goodNight = true
                continue
            }
            rest += tok
        }
        if (rest.isEmpty()) return null                      // a wish on its own is small talk, not an ending

        val confidence = when {
            isFarewell(rest) -> 0.95f
            isNothingMore(rest) -> 0.92f
            isLeaving(rest) -> 0.92f
            isDeferral(rest) -> 0.9f
            isDone(rest) -> 0.88f
            isRest(rest) -> 0.88f
            else -> return null
        }
        return Result(confidence, goodNight)
    }

    // ---- roles ----------------------------------------------------------------------------------

    private fun isFarewell(t: List<String>): Boolean {
        // خداحافظ / خدافظ / خداحافظی / خدانگهدار(ت) / bye
        if (t.any { tok -> STRONG_FAREWELL_STEMS.any { tok.startsWith(it) } }) return true
        // "خدا نگهدار(ت)" / "خدا حافظ" written as two words
        for (i in 0 until t.size - 1) {
            if (t[i] == "خدا" && (t[i + 1].startsWith("نگهدار") || t[i + 1].startsWith("حافظ"))) return true
        }
        // "مراقب خودت باش"
        if (t.any { it.startsWith("مراقب") } && t.any { it in SELF_WORDS || it.startsWith("باش") }) return true
        // "به امید دیدار"
        for (i in 0 until t.size - 2) {
            if (t[i] == "به" && t[i + 1] == "امید" && t[i + 2].startsWith("دیدار")) return true
        }
        // Weak farewell words only count when almost nothing else is said ("فعلا" / "فعلا بای").
        val weak = t.count { it in WEAK_FAREWELLS }
        return weak > 0 && t.size - weak <= 1
    }

    /** [دیگه] + need-noun + negated have/need: the user has nothing more to ask. */
    private fun isNothingMore(t: List<String>): Boolean {
        val need = t.any { tok -> NEED_NOUNS.any { tok.startsWith(it) } }
        val negated = t.any { it in NEGATED_HAVE }
        if (!need || !negated) return false
        return t.contains("دیگه") || t.contains("دیگر") || t.size <= 3
    }

    /** First-person departure: "من دیگه میرم", "دارم میرم", "رفتم", "برم". */
    private fun isLeaving(t: List<String>): Boolean {
        // Short on purpose; real commands never get here because the Brain asks the command parser first.
        return t.size <= MAX_LEAVING_TOKENS && t.any { it in LEAVING_VERBS }
    }

    /** "بعداً" / "فردا" / "تا بعد" + a talk-or-see verb: "we will continue later". */
    private fun isDeferral(t: List<String>): Boolean {
        if (t.size > MAX_DEFERRAL_TOKENS) return false
        for (i in 0 until t.size - 1) {
            if (t[i] == "تا" && (t[i + 1] in LATER_WORDS || t[i + 1].startsWith("دیدار"))) return true
        }
        val later = t.any { it in LATER_WORDS }
        val converse = t.any { tok -> CONVERSE_STEMS.any { tok.startsWith(it) } }
        return later && converse
    }

    /** "تموم شد" / "کافیه" / "بسه" / "همین بود": a completion word in a very short sentence. */
    private fun isDone(t: List<String>): Boolean {
        if (t.size > 3) return false
        if (t.any { it in DONE_WORDS }) return true
        return t.contains("همین") && t.any { it in HAMIN_PARTNERS }
    }

    private fun isRest(t: List<String>): Boolean = t.size <= 3 && t.any { it.startsWith("استراحت") }

    // ---- helpers --------------------------------------------------------------------------------

    /** The verb prefix می/نمی is often written (or heard) as a separate word: "می رم" -> "میرم". */
    private fun glueVerbPrefix(tokens: List<String>): List<String> {
        val out = ArrayList<String>(tokens.size)
        var i = 0
        while (i < tokens.size) {
            val tok = tokens[i]
            if ((tok == "می" || tok == "نمی") && i + 1 < tokens.size) {
                out += tok + tokens[i + 1]
                i += 2
            } else {
                out += tok
                i++
            }
        }
        return out
    }

    private const val MAX_TOKENS = 8
    private const val MAX_LEAVING_TOKENS = 4
    private const val MAX_DEFERRAL_TOKENS = 5

    /** Wake-word leftovers, politeness and thanks: they never decide whether a sentence is an ending. */
    private val POLITE = setOf(
        "جارویس", "جارویز", "هی", "لطفا", "ارباب", "بله", "آقا", "خب", "خوب", "باشه", "اوکی",
        "ممنون", "ممنونم", "مرسی", "متشکرم", "سپاس", "تشکر", "مچکرم", "دستت", "درد", "نکنه", "دمت", "گرم",
        "ازت", "بابت", "همه", "چیز", "خیلی"
    )
    private val WISH_PARTS = setOf("شب", "روز", "عصر", "ظهر")

    private val STRONG_FAREWELL_STEMS = listOf("خداحافظ", "خدافظ", "خدانگهدار", "خدانگه", "bye")
    private val WEAK_FAREWELLS = setOf("فعلا", "بای", "فعلن")
    private val SELF_WORDS = setOf("خودت", "خودتو", "خودتون", "خودتان", "خود")

    private val NEED_NOUNS = listOf("کار", "چیز", "سوال", "حرف", "دستور", "امر", "درخواست", "نیاز")
    private val NEGATED_HAVE = setOf(
        "ندارم", "نداریم", "ندارین", "ندارید", "نیست", "نمونده", "نمیخوام", "نمیخواهم", "نمیخواد", "نمیخوایم"
    )

    private val LEAVING_VERBS = setOf("میرم", "میروم", "برم", "بروم", "رفتم", "میریم", "بریم", "میرین", "رفتیم")

    private val LATER_WORDS = setOf("بعدا", "بعد", "فردا", "بعدش", "دوباره")
    private val CONVERSE_STEMS = listOf(
        "صحبت", "حرف", "میبین", "برمیگرد", "میام", "میایم", "میرسیم", "تماس"
    )

    private val DONE_WORDS = setOf("تموم", "تمام", "تمومه", "تمامه", "کافیه", "کافی", "بسه", "بس")
    private val HAMIN_PARTNERS = setOf("بود", "بسه", "کافیه", "دیگه", "طور")
}
