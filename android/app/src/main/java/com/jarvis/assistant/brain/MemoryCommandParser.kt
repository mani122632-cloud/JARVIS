package com.jarvis.assistant.brain

import com.jarvis.assistant.nlu.PersianNormalizer

/** An explicit memory request. Pure data: parsing never touches storage. */
sealed class MemoryIntent {
    /** "یادت باشه اسم من علی هست" -> key "اسم", value "علی". */
    data class Remember(val key: String, val value: String) : MemoryIntent()
    /** "یادت باشه ..." that does not contain a "<چیز> من <مقدار>" fact. */
    object RememberUnclear : MemoryIntent()
    /** "اسم من چیه؟" */
    data class Recall(val key: String) : MemoryIntent()
    /** "اسم من رو فراموش کن" */
    data class ForgetKey(val key: String) : MemoryIntent()
    /** "این رو فراموش کن": the most recently remembered / recalled item. */
    object ForgetLast : MemoryIntent()
    /** "همه چیز رو فراموش کن" */
    object ClearAll : MemoryIntent()
}

/**
 * Recognizes ONLY explicit memory commands (Stage 46.4); anything else returns null and is never stored.
 * Deterministic and offline: normalized tokens -> pattern match.
 *
 *   remember: <یادت باشه | یاد بگیر | ...> [که] <key> من <value> [هست]
 *   recall:   <key> من <چیه | چیست | چی هست | میدونی | ...>
 *   forget:   <key> من را فراموش کن · این را فراموش کن
 *   clear:    همه چیز را فراموش کن · حافظه را پاک کن
 */
object MemoryCommandParser {

    fun parse(raw: String): MemoryIntent? {
        val t = normalizeTokens(raw)
        if (t.isEmpty()) return null
        return parseClear(t) ?: parseForget(t) ?: parseRemember(t) ?: parseRecall(t)
    }

    // ---- clear / forget -------------------------------------------------------------------------

    private fun parseClear(t: List<String>): MemoryIntent? {
        val forget = t.any { it in FORGET_WORDS }
        val all = t.any { it in ALL_WORDS }
        if (forget && all) return MemoryIntent.ClearAll
        if (t.contains("حافظه") && t.any { it in ERASE_WORDS }) return MemoryIntent.ClearAll
        return null
    }

    private fun parseForget(t: List<String>): MemoryIntent? {
        if (t.none { it in FORGET_WORDS }) return null
        val me = t.indexOf("من")
        if (me in 1..MAX_KEY_TOKENS) return MemoryIntent.ForgetKey(canonicalKey(t.subList(0, me)))
        val rest = t.filter { it !in FORGET_WORDS && it !in FORGET_FILLER }
        return if (rest.isEmpty()) MemoryIntent.ForgetLast else null
    }

    // ---- remember -------------------------------------------------------------------------------

    private fun parseRemember(t: List<String>): MemoryIntent? {
        val prefix = REMEMBER_PREFIXES.firstOrNull { t.startsWithTokens(it) } ?: return null
        var rest = t.drop(prefix.size)
        if (rest.firstOrNull() == "که") rest = rest.drop(1)
        // "من علی هستم" -> the user's name.
        if (rest.firstOrNull() == "من" && rest.lastOrNull() == "هستم" && rest.size in 3..(MAX_VALUE_TOKENS + 2)) {
            return MemoryIntent.Remember("اسم", rest.subList(1, rest.size - 1).joinToString(" "))
        }
        val me = rest.indexOf("من")
        if (me !in 1..MAX_KEY_TOKENS) return MemoryIntent.RememberUnclear
        var value = rest.drop(me + 1)
        value = value.dropWhile { it == "را" }
        value = stripCopula(value)
        if (value.isEmpty() || value.size > MAX_VALUE_TOKENS) return MemoryIntent.RememberUnclear
        return MemoryIntent.Remember(canonicalKey(rest.subList(0, me)), value.joinToString(" "))
    }

    private fun stripCopula(tokens: List<String>): List<String> {
        var v = tokens
        while (v.isNotEmpty()) {
            val last = v.last()
            v = when {
                last in COPULAS -> v.dropLast(1)
                last == "باشد" && v.getOrNull(v.size - 2) == "می" -> v.dropLast(2)
                else -> return v
            }
        }
        return v
    }

    // ---- recall ---------------------------------------------------------------------------------

    private fun parseRecall(t: List<String>): MemoryIntent? {
        val me = t.indexOf("من")
        if (me !in 1..MAX_KEY_TOKENS) return null
        var tail = t.drop(me + 1)
        tail = tail.dropWhile { it == "را" }
        if (tail.joinToString(" ") !in RECALL_TAILS) return null
        return MemoryIntent.Recall(canonicalKey(t.subList(0, me)))
    }

    // ---- helpers --------------------------------------------------------------------------------

    /** Normalized tokens with colloquial "رو" -> "را", "منو" -> "من را", wake/politeness fillers removed. */
    private fun normalizeTokens(raw: String): List<String> {
        val out = ArrayList<String>()
        for (tok in PersianNormalizer.tokens(raw)) {
            when (tok) {
                "رو" -> out.add("را")
                "منو" -> { out.add("من"); out.add("را") }
                "جارویس", "هی", "لطفا" -> Unit
                else -> out.add(tok)
            }
        }
        return out
    }

    private fun canonicalKey(tokens: List<String>): String {
        val key = tokens.joinToString(" ")
        return KEY_ALIASES[key] ?: key
    }

    private fun List<String>.startsWithTokens(prefix: List<String>): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    private const val MAX_KEY_TOKENS = 2
    private const val MAX_VALUE_TOKENS = 6

    private val REMEMBER_PREFIXES: List<List<String>> = listOf(
        "یادت باشه", "یادت باشد", "یادت باش", "یادت بمونه", "یاد بگیر", "به یاد بسپار", "به خاطر بسپار", "بخاطر بسپار"
    ).map { it.split(" ") }

    private val FORGET_WORDS = setOf("فراموش", "فراموشش")
    private val FORGET_FILLER = setOf("این", "آن", "را", "کن", "بکن", "کنی", "کنید", "ازش")
    private val ALL_WORDS = setOf("همه", "همش", "همهچیز", "تمام", "تمامی")
    private val ERASE_WORDS = setOf("پاک", "خالی", "فراموش")
    private val COPULAS = setOf("هست", "است", "ه", "هستش", "هستم", "هستند")
    private val RECALL_TAILS = setOf(
        "چیه", "چیست", "چی هست", "چی است", "چی بود", "میدونی", "می دونی", "می دانی", "یادته", "یادت هست", "یادت میاد"
    )
    /** Spoken synonyms -> the one stored key. */
    private val KEY_ALIASES = mapOf("نام" to "اسم", "اسم کوچک" to "اسم")
}
