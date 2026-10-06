package com.jarvis.assistant.speech

/**
 * Cuts text that arrives piece by piece into natural, speakable segments. Pure Kotlin, no state outside the buffer.
 *
 *  - A segment ends at a sentence end (. ! ؟ ? ؛ ; … or a line break) that is followed by white space, so decimals
 *    like «۳.۵» are never split. A sentence shorter than [minSegmentChars] is joined with the next one.
 *  - To start speaking sooner, the FIRST segment may also end at a comma / colon (، , :) once it has
 *    [firstSoftBreakChars] characters.
 *  - A very long sentence is cut at the last comma, otherwise at the last white space, within [maxSegmentChars].
 *  - Cuts are only made at white space or punctuation followed by white space, never inside a word. The half-space
 *    (U+200C) is not white space, so «می‌خواهم» stays whole.
 *  - Every character goes out exactly once: the buffer is consumed, so segments can neither overlap nor repeat.
 */
class SpeechChunker(
    private val minSegmentChars: Int = MIN_SEGMENT_CHARS,
    private val firstSoftBreakChars: Int = FIRST_SOFT_BREAK_CHARS,
    private val maxSegmentChars: Int = MAX_SEGMENT_CHARS
) {
    private val buf = StringBuilder()
    private var emittedAny = false

    /** Adds [delta]; returns the segments that are complete now (usually none or one). */
    fun feed(delta: String): List<String> {
        if (delta.isEmpty()) return emptyList()
        buf.append(delta)
        return drain(false)
    }

    /** End of text: returns everything that is left. */
    fun flush(): List<String> = drain(true)

    fun reset() {
        buf.setLength(0)
        emittedAny = false
    }

    private fun drain(final: Boolean): List<String> {
        val out = ArrayList<String>()
        while (buf.isNotEmpty()) {
            var cut = nextCut(final)
            if (cut < 0) {
                if (!final) break
                cut = buf.length
            }
            val seg = WHITESPACE.replace(buf.substring(0, cut), " ").trim()
            buf.delete(0, cut)
            while (buf.isNotEmpty() && buf[0].isWhitespace()) buf.deleteCharAt(0)
            if (seg.any { it.isLetterOrDigit() }) {
                out.add(seg)
                emittedAny = true
            }
        }
        return out
    }

    /** Exclusive end of the next segment inside [buf], or -1 when more text is needed. */
    private fun nextCut(final: Boolean): Int {
        val sentence = sentenceCut(final)
        if (!emittedAny) {
            val soft = firstSoftCut()
            if (soft > 0 && (sentence < 0 || soft < sentence)) return soft
        }
        if (sentence in 1..maxSegmentChars) return sentence
        val long = longCut()
        if (long > 0) return long
        return sentence
    }

    private fun sentenceCut(final: Boolean): Int {
        val n = buf.length
        var i = 0
        while (i < n) {
            val c = buf[i]
            if (c == '\n' || c in TERMINATORS) {
                var j = i + 1
                if (c != '\n') {
                    while (j < n && (buf[j] in TERMINATORS || buf[j] in CLOSERS)) j++
                }
                val confirmed = c == '\n' || (j < n && buf[j].isWhitespace()) || (j >= n && final)
                if (confirmed && buf.substring(0, j).trim().length >= minSegmentChars) return j
                i = j
            } else {
                i++
            }
        }
        return -1
    }

    private fun firstSoftCut(): Int {
        val n = buf.length
        var k = firstSoftBreakChars - 1
        while (k < n - 1) {
            if (buf[k] in SOFT && buf[k + 1].isWhitespace()) return k + 1
            k++
        }
        return -1
    }

    private fun longCut(): Int {
        val n = buf.length
        if (n <= maxSegmentChars) return -1
        var k = maxSegmentChars
        while (k >= MIN_LONG_SOFT_CHARS) {
            if (buf[k] in SOFT && k + 1 < n && buf[k + 1].isWhitespace()) return k + 1
            k--
        }
        k = maxSegmentChars
        while (k >= minSegmentChars) {
            if (buf[k].isWhitespace()) return k
            k--
        }
        return -1
    }

    companion object {
        const val MIN_SEGMENT_CHARS = 10
        const val FIRST_SOFT_BREAK_CHARS = 24
        const val MAX_SEGMENT_CHARS = 110
        private const val MIN_LONG_SOFT_CHARS = 30
        private const val TERMINATORS = ".!؟?؛;…"
        private const val CLOSERS = "\"'”’»)]"
        private const val SOFT = "،,:"
        private val WHITESPACE = Regex("\\s+")
    }
}
