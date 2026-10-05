package com.jarvis.assistant.wakeword

import org.json.JSONArray
import org.json.JSONObject

/**
 * The wake phrase «هی جارویس» and the (deliberately forgiving) rules that decide whether a Vosk result is it.
 *
 * Matching is done on the COMPACT form of the whole utterance: letters unified (ي→ی, ك→ک, أ/إ→ا ...), and
 * punctuation, diacritics, half-spaces and ALL whitespace removed. "هی جارویس", "هی جاروویس", "هی جارو یس",
 * "هی، جارویس!" and "هی جار ویس" therefore all become one of the accepted compact spellings.
 *
 * Protection against random speech comes from three cheap, natural-speech-friendly rules:
 *  1. the WHOLE utterance must be the phrase (no extra words, no [unk]) - "هی" or "میدونم" never match;
 *  2. a closed grammar with look-alike decoy words and [unk] gives ordinary speech somewhere else to go;
 *  3. a very low average word confidence floor (final results only) and a low energy floor (see engine).
 * There are NO word-duration / gap thresholds any more: they rejected normal speech at normal speed.
 */
object WakePhrase {

    private const val FIRST_WORD = "هی"
    private val LATIN_FIRST = listOf("hey", "hay", "hi")

    /** Accepted spellings of the name (compact form is computed from these). */
    private val NAMES = listOf(
        "جارویس", "جاروویس", "جارویز", "جاروس", "جاریس", "جارویش", "جارویث", "جارویسس",
        "جارو یس", "جار ویس", "جار ویز", "جارو ویس"
    )
    private val LATIN_NAMES = listOf("jarvis", "jarvice", "jarviss", "jervis", "garvis")

    /** Look-alike / very common words that give normal speech a competing path in the grammar. */
    private val DECOY_PHRASES = listOf(
        "میدونم", "نمیدونم", "می دونم", "نمی دونم", "میدانم", "نمیدانم", "میخوام", "نمیخوام",
        "هیچی", "هیچ", "همین", "همینه", "جاری", "جار", "ویس", "هی", "میرم", "نمیرم", "آره", "نه", "خب", "چی"
    )

    /** Minimum mean word confidence of a FINAL result (when Vosk reports it). Low on purpose. */
    const val MIN_AVG_CONF = 0.30

    /** Very quiet audio (TV far away, hiss) cannot wake JARVIS. Deliberately low. */
    const val MIN_UTTERANCE_RMS = 120.0

    class Verdict(val accepted: Boolean, val reason: String)

    fun energyVerdict(rms: Double): Verdict =
        if (rms >= MIN_UTTERANCE_RMS) Verdict(true, "energy ok rms=%.0f".format(rms))
        else Verdict(false, "too quiet rms=%.0f".format(rms))

    /** Compact forms of every accepted full phrase. */
    private val ACCEPTED: Set<String> by lazy {
        val s = LinkedHashSet<String>()
        for (n in NAMES) s += compact(FIRST_WORD + n)
        for (f in LATIN_FIRST) for (n in LATIN_NAMES) s += compact(f + n)
        // The name alone (without «هی») is NOT accepted on purpose: too easy to hit by accident.
        s
    }

    /** True when [text] (a partial or final Vosk text) is exactly the wake phrase. */
    fun matches(text: String): Boolean {
        if (text.isBlank() || text.contains("[unk]")) return false
        return compact(text) in ACCEPTED
    }

    /** The grammar given to Vosk plus the evaluation of final results. */
    class Grammar internal constructor(val json: String?) {

        /** Decides on one FINAL Vosk result (JSON from Recognizer.getResult()). */
        fun evaluateFinal(resultJson: String): Verdict {
            val o = try { JSONObject(resultJson) } catch (e: Exception) { return Verdict(false, "bad json") }
            val text = o.optString("text")
            if (text.isBlank()) return Verdict(false, "empty")
            if (!matches(text)) return Verdict(false, "not the wake phrase: $text")
            val arr = o.optJSONArray("result")
            if (arr != null && arr.length() > 0) {
                var sum = 0.0
                var n = 0
                for (i in 0 until arr.length()) {
                    val w = arr.optJSONObject(i) ?: continue
                    if (w.has("conf")) { sum += w.optDouble("conf", 1.0); n++ }
                }
                if (n > 0 && sum / n < MIN_AVG_CONF) return Verdict(false, "confidence too low: %.2f".format(sum / n))
            }
            return Verdict(true, "ok")
        }
    }

    /**
     * Builds the grammar for a model. [vocabulary] is the subset of [candidateWords] that exists in the
     * model; null means "unknown, assume everything exists". Returns a grammar whose [Grammar.json] is null
     * (= free recognition, still matched with [matches]) when the model's vocabulary cannot express the phrase
     * as a closed grammar, instead of giving up like earlier versions did.
     */
    fun build(vocabulary: Set<String>?): Grammar {
        fun known(w: String) = vocabulary == null || compact(w) in vocabulary
        val phrases = LinkedHashSet<String>()
        if (known(FIRST_WORD)) {
            for (n in NAMES) {
                val parts = n.split(' ')
                if (parts.all { known(it) }) phrases += (listOf(FIRST_WORD) + parts).joinToString(" ")
            }
        }
        val hasPhrase = phrases.isNotEmpty()
        if (!hasPhrase) return Grammar(null)
        DECOY_PHRASES.filter { p -> p.split(' ').all { known(it) } }.forEach { phrases += it }
        phrases += "[unk]"
        return Grammar(JSONArray(phrases.toList()).toString())
    }

    /** Every word [build] might use; the engine looks these up in the model vocabulary. */
    fun candidateWords(): Set<String> {
        val s = LinkedHashSet<String>()
        s += compact(FIRST_WORD)
        NAMES.forEach { n -> n.split(' ').forEach { s += compact(it) } }
        DECOY_PHRASES.forEach { p -> p.split(' ').forEach { s += compact(it) } }
        return s
    }

    /** Per-word normalization used for vocabulary lookup (same as [compact]). */
    fun normalize(s: String): String = compact(s)

    /**
     * Unifies Arabic/Persian letters, lowercases Latin, and drops everything that is not a letter or digit
     * (punctuation, diacritics, tatweel, half-space, whitespace).
     */
    fun compact(s: String): String = buildString {
        for (c in s) when {
            c == 'ي' || c == 'ى' || c == 'ئ' -> append('ی')
            c == 'ك' -> append('ک')
            c == 'أ' || c == 'إ' || c == 'ٱ' || c == 'آ' -> append('ا')
            c == 'ة' || c == 'ۀ' -> append('ه')
            c == 'ؤ' -> append('و')
            c in '\u064B'..'\u065F' || c == '\u0670' || c == '\u0640' -> {}
            c.isLetterOrDigit() -> append(c.lowercaseChar())
            else -> {}
        }
    }
}
