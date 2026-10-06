package com.jarvis.assistant.online

/**
 * Splits a streamed answer into short chunks for the TTS queue. Not thread-safe: use from one thread.
 *
 * Delimiters: `. ! ؟ ? ؛ \n`. The FIRST chunk is released early for low latency: once about [firstTrigger]
 * characters are buffered it is cut at the next word boundary (words are never cut). A decimal point between
 * digits ("3.5") is not a delimiter. Later chunks shorter than [minLater] wait for the next sentence.
 */
class SentenceChunker(
    private val firstMin: Int = 25,
    private val firstTrigger: Int = 28,
    private val minLater: Int = 10,
    private val maxChunk: Int = 140
) {
    private val buf = StringBuilder()
    private var emitted = 0

    fun push(delta: String): List<String> {
        if (delta.isEmpty()) return emptyList()
        buf.append(delta)
        return drain(final = false)
    }

    /** End of stream: everything that is left, as a last chunk. */
    fun flush(): List<String> {
        val out = drain(final = true).toMutableList()
        val rest = buf.toString().trim()
        buf.setLength(0)
        if (hasContent(rest)) { out += rest; emitted++ }
        return out
    }

    fun reset() { buf.setLength(0); emitted = 0 }

    private fun drain(final: Boolean): List<String> {
        val out = ArrayList<String>()
        while (true) {
            val cut = nextCut(final)
            if (cut <= 0) break
            val chunk = buf.substring(0, cut).trim()
            buf.delete(0, cut)
            if (hasContent(chunk)) { out += chunk; emitted++ }
        }
        return out
    }

    /** End index (exclusive) of the next chunk in [buf], or -1 when more text is needed. */
    private fun nextCut(final: Boolean): Int {
        val minLen = if (emitted == 0) 2 else minLater
        var i = 0
        while (i < buf.length) {
            val c = buf[i]
            if (isDelimiter(c)) {
                if (c == '.' && isDecimalPoint(i, final)) { if (i == buf.length - 1) return -1; i++; continue }
                var end = i + 1
                while (end < buf.length && isDelimiter(buf[end])) end++          // "..." / "?!" stay together
                if (end == buf.length && !final && c == '.' && end - 1 == i && i > 0 && buf[i - 1].isDigitAny()) return -1
                if (visibleLength(end) >= minLen) return end
                i = end
                continue
            }
            i++
        }
        if (emitted == 0 && buf.length >= firstTrigger) {
            wordBoundaryFrom(firstMin)?.let { return it }
        }
        if (buf.length >= maxChunk) {
            val soft = lastSoftBreakBefore(maxChunk)
            if (soft > 0) return soft
            wordBoundaryFrom(maxChunk)?.let { return it }
        }
        return -1
    }

    private fun isDecimalPoint(i: Int, final: Boolean): Boolean {
        if (i == 0 || !buf[i - 1].isDigitAny()) return false
        if (i + 1 >= buf.length) return !final      // next char unknown yet: wait
        return buf[i + 1].isDigitAny()
    }

    private fun wordBoundaryFrom(from: Int): Int? {
        for (j in from until buf.length) if (buf[j].isWhitespace()) return j + 1
        return null
    }

    private fun lastSoftBreakBefore(limit: Int): Int {
        for (j in minOf(limit, buf.length) - 1 downTo firstMin) {
            if (buf[j] == '،' || buf[j] == ',') return j + 1
        }
        return -1
    }

    private fun visibleLength(end: Int): Int {
        var n = 0
        for (j in 0 until end) if (buf[j].isLetterOrDigit()) n++
        return n
    }

    private fun hasContent(s: String) = s.any { it.isLetterOrDigit() }
    private fun isDelimiter(c: Char) = c == '.' || c == '!' || c == '؟' || c == '?' || c == '؛' || c == '\n'
    private fun Char.isDigitAny() = this in '0'..'9' || this in '۰'..'۹' || this in '٠'..'٩'
}
