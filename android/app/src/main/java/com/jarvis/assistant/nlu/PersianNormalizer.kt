package com.jarvis.assistant.nlu

/**
 * The single general-purpose Persian text normalizer for commands (Stage 46.3).
 *
 * (There was no shared normalizer before this stage: `WakePhrase.normalize` is a per-word helper of the
 * wake-word detector that deliberately removes ALL whitespace, and `PersianTtsText.prepare` is a TTS-input
 * cleaner. Both belong to other subsystems and are intentionally left untouched.)
 *
 * Output is a lowercase, single-space-separated string made only of letters, digits, '%' and digit-internal
 * ':' / '.', so the parser can split on ' ' and compare tokens directly.
 *
 *  - ي ى → ی        ك → ک        أ إ ٱ → ا        ة ۀ → ه
 *  - half-space (ZWNJ) / NBSP → space ("دقیقه‌ای" → "دقیقه ای", "چراغ‌قوه" → "چراغ قوه")
 *  - diacritics, tatweel, RLM/LRM/ZWJ removed
 *  - Persian (۰-۹) and Arabic-Indic (٠-٩) digits → ASCII digits; ٪ → %; ٫ → .
 *  - punctuation (، ؛ ؟ ! . , " « » …) → space; repeated spaces collapsed
 */
object PersianNormalizer {

    fun normalize(input: String): String {
        val sb = StringBuilder(input.length)
        for (i in input.indices) {
            val c = input[i]
            when {
                c == 'ي' || c == 'ى' -> sb.append('ی')
                c == 'ك' -> sb.append('ک')
                c == 'أ' || c == 'إ' || c == 'ٱ' -> sb.append('ا')
                c == 'ة' || c == 'ۀ' -> sb.append('ه')
                c == '\u200c' || c == '\u00a0' -> sb.append(' ')
                c == '\u200d' || c == '\u200e' || c == '\u200f' || c == '\uFEFF' -> {}
                c in '\u064B'..'\u065F' || c == '\u0670' || c == '\u0640' -> {}
                c in '\u06F0'..'\u06F9' -> sb.append('0' + (c - '\u06F0'))
                c in '\u0660'..'\u0669' -> sb.append('0' + (c - '\u0660'))
                c == '٪' || c == '%' -> sb.append('%')
                c == '٫' -> sb.append('.')
                c == '٬' -> {}
                (c == ':' || c == '.') && isDigitAt(input, i - 1) && isDigitAt(input, i + 1) ->
                    sb.append(if (c == '.') '.' else ':')
                c.isLetterOrDigit() -> sb.append(c.lowercaseChar())
                else -> sb.append(' ')
            }
        }
        return sb.toString().replace(MULTI_SPACE, " ").trim()
    }

    /** Normalized text split into tokens (empty list for blank input). */
    fun tokens(input: String): List<String> =
        normalize(input).let { if (it.isEmpty()) emptyList() else it.split(' ') }

    private fun isDigitAt(s: String, i: Int): Boolean {
        if (i < 0 || i >= s.length) return false
        val c = s[i]
        return c in '0'..'9' || c in '\u06F0'..'\u06F9' || c in '\u0660'..'\u0669'
    }

    private val MULTI_SPACE = Regex("\\s+")
}
