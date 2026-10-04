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
        return when (phrase) {
            in GREETINGS -> JarvisPhrases.GREETING
            in HOW_ARE_YOU -> JarvisPhrases.FINE
            in WHO_ARE_YOU -> JarvisPhrases.MY_NAME
            else -> null
        }
    }

    private companion object {
        /** Wake-word leftovers and politeness that do not change the meaning. */
        val FILLER = setOf("جارویس", "هی", "لطفا")

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
