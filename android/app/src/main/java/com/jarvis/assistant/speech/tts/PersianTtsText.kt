package com.jarvis.assistant.speech.tts

/**
 * Prepares Persian text for the phonemizer. Only unifies/removes characters the voice does not need;
 * it never changes meaning.
 *
 * [PRONUNCIATION] is the hook for words the voice reads badly: add "spelling" to "better spelling"
 * after listening to the result.
 */
object PersianTtsText {
    private val PRONUNCIATION = mapOf<String, String>(
        // "حتماً" -> "حتما": the tanvin sign is dropped below; add overrides here if the voice misreads a word.
    )

    fun prepare(text: String): String {
        var t = text
        for ((from, to) in PRONUNCIATION) t = t.replace(from, to)
        val sb = StringBuilder(t.length)
        for (c in t) when {
            c == 'ي' || c == 'ى' -> sb.append('ی')
            c == 'ك' -> sb.append('ک')
            c == '\u200c' -> {}                                  // half-space: "می‌خوام" -> "میخوام"
            c in '\u064B'..'\u065F' || c == '\u0670' || c == '\u0640' -> {}   // diacritics, tatweel
            else -> sb.append(c)
        }
        return sb.toString().replace(Regex("\\s+"), " ").trim()
    }

    /** Splits into sentences so the first one can start playing before a long answer is fully synthesized. */
    fun sentences(text: String): List<String> =
        text.split(Regex("(?<=[.!؟?!\\n])\\s*"))
            .map { it.trim() }
            .filter { it.any { ch -> ch.isLetterOrDigit() } }
}
