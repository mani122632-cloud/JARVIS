package com.jarvis.assistant.brain

import com.jarvis.assistant.nlu.PersianNormalizer
import com.jarvis.assistant.speech.JarvisPhrases

/**
 * Fixed small-talk fallback (Stage 46.4). A lookup table, NOT a language model: an utterance either matches
 * one of the known phrases exactly (after normalization) or there is no reply (null).
 * Add a phrase: put its normalized form in one of the sets below.
 */
class BasicConversation {

    fun reply(text: String): String? {
        val tokens = PersianNormalizer.tokens(text).filter { it !in FILLER }
        if (tokens.isEmpty()) return null
        val phrase = tokens.joinToString(" ")
        val set = tokens.toSet()
        return when {
            phrase in GREETINGS || (tokens.size <= 2 && tokens.all { it in GREETING_WORDS }) -> JarvisPhrases.GREETING
            phrase in HOW_ARE_YOU || (set.contains("حالت") && set.any { it in HOW_WORDS }) -> JarvisPhrases.FINE
            "من" !in set && (phrase in WHO_ARE_YOU ||
                ((set.contains("اسمت") || set.contains("نامت") || (set.contains("تو") && (set.contains("اسم") || set.contains("نام")))) &&
                    set.any { it in WHAT_WORDS })) -> JarvisPhrases.MY_NAME
            // "چه کارهایی می‌تونی انجام بدی؟" / "چکار بلدی؟" / "چه کاری از دستت برمیاد؟"
            tokens.any { it.startsWith("کار") || it == "چیکار" || it == "چکار" } &&
                set.any { it in ABILITY_WORDS } -> JarvisPhrases.CAPABILITIES
            else -> null
        }
    }

    private companion object {
        /** Wake-word leftovers and politeness that do not change the meaning. */
        val FILLER = setOf("جارویس", "هی", "لطفا", "ارباب", "بله")

        val GREETING_WORDS = setOf("سلام", "درود", "علیکم")
        val HOW_WORDS = setOf("چطوره", "چطور", "چطوری", "خوبه", "چطوره؟")
        val WHAT_WORDS = setOf("چیه", "چیست", "چی", "هست", "چیه؟")
        val ABILITY_WORDS = setOf("تونی", "میتونی", "بلدی", "بلد", "انجام", "برمیاد", "برمی", "توانی", "میتوانی")
        val GREETINGS = setOf("سلام", "درود", "سلام علیکم")
        val HOW_ARE_YOU = setOf(
            "خوبی", "خوبید", "حالت چطوره", "حالت چطور است", "چطوری", "حال شما چطوره", "حالتون چطوره"
        )
        val WHO_ARE_YOU = setOf(
            "اسمت چیه", "اسمت چی هست", "اسمت چیست", "اسم تو چیه", "اسم تو چی هست", "اسم تو چیست",
            "نامت چیه", "نام تو چیست", "تو کی هستی", "کی هستی"
        )
    }
}
