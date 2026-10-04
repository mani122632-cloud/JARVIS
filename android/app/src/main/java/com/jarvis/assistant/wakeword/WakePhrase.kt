package com.jarvis.assistant.wakeword

import org.json.JSONArray
import org.json.JSONObject

/**
 * The wake phrase «هی جارویس» and the rules that decide whether a Vosk FINAL result really is it.
 *
 * Why Stage 45 activated on «میدونم»:
 *  - The old matcher glued all non-[unk] words together and compared the result with a spelling list.
 *    A grammar that contains «جار ویس» (two ordinary words) lets a short utterance decode into
 *    «هی جار ویس», which then matched once the spaces were removed.
 *  - Only the AVERAGE confidence was checked (0.6). In a closed grammar with almost no competing paths
 *    the posterior of the only matching path is close to 1.0, so confidence alone proves nothing.
 *
 * What is required now (ALL of it, on a final result only; partial results are never looked at):
 *  1. The result words, as emitted, are EXACTLY one of the accepted word sequences. No joining of
 *     words, no split spellings, no [unk] inside the match, no extra words.
 *  2. Every word has conf >= [Tier.minWordConf] and the mean is >= [Tier.minAvgConf].
 *  3. Timing sanity from Vosk's word times: the phrase lasts 0.45–2.2 s, the name word lasts at least
 *     0.30 s and the gap between the words is at most 0.5 s. A short word like «میدونم» cannot fake that.
 *  4. The grammar also contains common look-alike words (and [unk]) so ordinary speech has somewhere
 *     else to go instead of being forced onto the wake phrase. This is supplementary; rules 1-3 are
 *     the actual guard and do not depend on any particular word list.
 *
 * Only spellings that exist in the model's vocabulary can be recognised (Vosk silently drops unknown
 * words from a grammar). [build] therefore filters the variants through the model's vocabulary.
 */
object WakePhrase {

    /** Thresholds per spelling tier. Raise to be stricter, lower if the real phrase is missed. */
    enum class Tier(val minWordConf: Double, val minAvgConf: Double) {
        PRIMARY(0.70, 0.80),
        /** Only when the model lacks both primary spellings; stricter because the spelling is further away. */
        FALLBACK(0.80, 0.90)
    }

    private const val FIRST_WORD = "هی"

    /** The intended phrase and its one natural variant. */
    private val PRIMARY_NAMES = listOf("جارویس", "جاروویس")

    /** Closest remaining spellings. NO split spellings such as «جار ویس». */
    private val FALLBACK_NAMES = listOf("جاروس", "جاریس")

    /** Look-alike / very common words that give normal speech a competing path in the grammar. */
    private val DECOY_PHRASES = listOf(
        "میدونم", "نمیدونم", "می دونم", "نمی دونم",
        "میدانم", "نمیدانم", "میخوام", "نمیخوام", "هیچی", "هیچ", "همین", "همینه",
        "جاری", "جار", "ویس", "هی", "میرم", "نمیرم", "آره", "نه", "خب", "چی"
    )

    class Word(val text: String, val conf: Double, val start: Double, val end: Double)

    class Verdict(val accepted: Boolean, val reason: String)

    /**
     * Energy gate on the audio of the utterance that produced a final result (RMS of 16-bit samples).
     * Very quiet audio (far-away TV, background hiss that the decoder forced onto the phrase) cannot wake
     * JARVIS. Deliberately low: it only removes the clearly-too-quiet cases; the word/time/confidence
     * rules above remain the main guard.
     */
    const val MIN_UTTERANCE_RMS = 250.0

    fun energyVerdict(rms: Double): Verdict =
        if (rms >= MIN_UTTERANCE_RMS) Verdict(true, "energy ok rms=%.0f".format(rms))
        else Verdict(false, "too quiet rms=%.0f".format(rms))

    /** The grammar actually given to Vosk plus the word sequences accepted for it. */
    class Grammar internal constructor(
        val json: String,
        val tier: Tier,
        internal val sequences: List<List<String>>
    ) {
        /** Decides on one final Vosk result (the JSON returned by Recognizer.getResult()). */
        fun evaluate(resultJson: String): Verdict {
            val o = try { JSONObject(resultJson) } catch (e: Exception) { return reject("bad json") }
            val text = o.optString("text")
            if (text.isBlank()) return reject("empty")
            if (text.contains("[unk]")) return reject("contains [unk]: $text")

            val arr = o.optJSONArray("result") ?: return reject("no word details")
            val words = ArrayList<Word>(arr.length())
            for (i in 0 until arr.length()) {
                val w = arr.optJSONObject(i) ?: return reject("bad word entry")
                words += Word(
                    text = normalize(w.optString("word")),
                    conf = w.optDouble("conf", 0.0),
                    start = w.optDouble("start", 0.0),
                    end = w.optDouble("end", 0.0)
                )
            }
            return evaluateWords(words)
        }

        internal fun evaluateWords(words: List<Word>): Verdict {
            val seq = words.map { it.text }
            if (words.size != 2) return reject("expected exactly 2 words, got ${words.size}")
            if (seq !in sequences) return reject("not the wake phrase: ${seq.joinToString(" ")}")
            if (words.any { it.conf < tier.minWordConf }) {
                return reject("word confidence too low: ${words.joinToString { "%.2f".format(it.conf) }}")
            }
            val avg = words.sumOf { it.conf } / words.size
            if (avg < tier.minAvgConf) return reject("average confidence too low: %.2f".format(avg))

            val first = words.first()
            val last = words.last()
            val total = last.end - first.start
            if (total < MIN_TOTAL_S || total > MAX_TOTAL_S) return reject("duration %.2fs out of range".format(total))
            if (last.end - last.start < MIN_NAME_S) return reject("name word too short")
            if (last.end - last.start > MAX_NAME_S) return reject("name word too long")
            if (first.end - first.start < MIN_FIRST_S) return reject("first word too short")
            if (first.end - first.start > MAX_FIRST_S) return reject("first word too long")
            for (w in words) if (w.end <= w.start) return reject("invalid word timing")
            for (i in 1 until words.size) {
                val gap = words[i].start - words[i - 1].end
                if (gap > MAX_GAP_S) return reject("gap between words too long")
                if (gap < -MAX_OVERLAP_S) return reject("words overlap")
            }
            return Verdict(true, "ok avg=%.2f total=%.2fs".format(avg, total))
        }

        private fun reject(why: String) = Verdict(false, why)
    }

    /**
     * Builds the grammar for a model. [vocabulary] is the subset of [candidateWords] that exists in the
     * model; null means "unknown, assume everything exists" (the model's word list was unreadable).
     * Returns null when the model cannot express the phrase at all; the engine then reports that
     * instead of running with a degenerate grammar.
     */
    fun build(vocabulary: Set<String>?): Grammar? {
        fun known(w: String) = vocabulary == null || normalize(w) in vocabulary
        fun sequencesFor(names: List<String>): List<List<String>> =
            if (known(FIRST_WORD)) names.filter { known(it) }.map { listOf(normalize(FIRST_WORD), normalize(it)) }
            else emptyList()

        var tier = Tier.PRIMARY
        var sequences = sequencesFor(PRIMARY_NAMES)
        if (sequences.isEmpty() && vocabulary != null) {
            tier = Tier.FALLBACK
            sequences = sequencesFor(FALLBACK_NAMES)
        }
        if (sequences.isEmpty()) return null

        val phrases = LinkedHashSet<String>()
        sequences.forEach { phrases += it.joinToString(" ") }
        DECOY_PHRASES.filter { p -> p.split(' ').all { known(it) } }.forEach { phrases += it }
        phrases += "[unk]"
        return Grammar(JSONArray(phrases.toList()).toString(), tier, sequences)
    }

    /** Every word that [build] might use; the engine looks these up in the model vocabulary. */
    fun candidateWords(): Set<String> {
        val s = LinkedHashSet<String>()
        s += normalize(FIRST_WORD)
        (PRIMARY_NAMES + FALLBACK_NAMES).forEach { s += normalize(it) }
        DECOY_PHRASES.forEach { p -> p.split(' ').forEach { s += normalize(it) } }
        return s
    }

    /** Unifies Arabic/Persian letters and drops diacritics, half-spaces and whitespace inside a single word. */
    fun normalize(s: String): String = buildString {
        for (c in s) when {
            c == 'ي' || c == 'ى' -> append('ی')
            c == 'ك' -> append('ک')
            c == '\u200c' || c.isWhitespace() -> {}
            c in '\u064B'..'\u065F' -> {}              // Arabic diacritics
            else -> append(c.lowercaseChar())
        }
    }

    private const val MIN_TOTAL_S = 0.45
    private const val MAX_TOTAL_S = 2.2
    private const val MIN_NAME_S = 0.30
    private const val MAX_NAME_S = 1.30
    private const val MIN_FIRST_S = 0.08
    private const val MAX_FIRST_S = 0.80
    private const val MAX_GAP_S = 0.5
    private const val MAX_OVERLAP_S = 0.05
}
