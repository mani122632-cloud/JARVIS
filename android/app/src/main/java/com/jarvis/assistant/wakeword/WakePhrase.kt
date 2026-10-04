package com.jarvis.assistant.wakeword

import org.json.JSONArray

/**
 * The wake phrase «هی جارویس» and its accepted spellings.
 *
 * Vosk is run with a closed grammar (these phrases + [unk]), which keeps CPU low and makes
 * ordinary speech map to [unk] instead of to the wake phrase. Words missing from the model's
 * vocabulary are ignored by Vosk, so listing several spellings is safe.
 */
object WakePhrase {
    private val VARIANTS = listOf(
        "هی جارویس", "هی جاروس", "هی جاریس", "هی جارویز", "هی جار ویس"
    )

    val grammarJson: String = JSONArray(VARIANTS + "[unk]").toString()

    private val accepted: Set<String> = VARIANTS.map { normalize(it) }.toSet()

    fun normalize(s: String): String = buildString {
        for (c in s) when {
            c == 'ي' -> append('ی')
            c == 'ك' -> append('ک')
            c == '\u200c' || c.isWhitespace() -> {}
            c in '\u064B'..'\u065F' -> {}              // Arabic diacritics
            else -> append(c.lowercaseChar())
        }
    }

    /** True if [text] (a final Vosk result) is the wake phrase, ignoring [unk] filler. */
    fun matches(text: String): Boolean {
        val words = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() && it != "[unk]" }
        if (words.isEmpty() || words.size > 4) return false
        return normalize(words.joinToString("")) in accepted
    }
}
