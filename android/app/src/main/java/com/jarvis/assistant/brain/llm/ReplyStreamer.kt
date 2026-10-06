package com.jarvis.assistant.brain.llm

import com.jarvis.assistant.speech.SpeechChunker

/**
 * Turns the raw text deltas of ONE LLM round into speakable segments, applying the same rules the Brain applies to a
 * complete reply: reasoning and tool-call markup and chat-template tokens are removed, markdown becomes white space,
 * a tool call printed as text is never spoken, sentences in a script the Persian voice cannot read (Chinese, ...)
 * are dropped, and the reply is capped at [maxChars].
 *
 * What it emits ([emit]) is exactly what [spoken] contains, so the text kept in the conversation history is the text
 * that was said. Main thread only (the Brain feeds it from its main-thread handler).
 */
internal class ReplyStreamer(
    private val maxChars: Int,
    private val emit: (String) -> Unit
) {
    private val chunker = SpeechChunker()
    private val held = StringBuilder()          // text that starts like a tag and cannot be judged yet
    private val spokenBuf = StringBuilder()
    private var inThink = false
    private var markup = false                  // a tool call written as text: nothing more is speakable
    private var toolStarted = false             // a real tool call: this round is not a spoken answer
    private var sawText = false
    private var finished = false

    /** True once any delta (even an empty one) arrived: the server really streams. */
    var sawDeltas: Boolean = false
        private set

    /** The length cap was reached; the stream needs no further reading. */
    var capped: Boolean = false
        private set

    /** Everything that was handed to [emit], joined with single spaces. */
    val spoken: String get() = spokenBuf.toString()

    val hasSpoken: Boolean get() = spokenBuf.isNotEmpty()

    fun onToolCallStarted() { toolStarted = true }

    /** @return false when the stream does not need to be read any further. */
    fun onDelta(delta: String): Boolean {
        sawDeltas = true
        if (capped) return false
        if (markup || toolStarted || finished) return true
        val text = filter(delta)
        if (!sawText) {
            val t = text.trimStart()
            if (t.isNotEmpty()) {
                sawText = true
                if (t[0] == '{' || t[0] == '[') {            // a tool call printed as text, not an answer
                    markup = true
                    return true
                }
            }
        }
        if (text.isEmpty()) return true
        for (seg in chunker.feed(text)) if (!speak(seg)) return false
        return true
    }

    /** End of the round's text: speaks the rest, unless this round is a tool call or was cut. */
    fun finish() {
        if (finished) return
        finished = true
        if (capped || markup || toolStarted) return
        for (seg in chunker.flush()) if (!speak(seg)) return
    }

    private fun speak(raw: String): Boolean {
        if (capped) return false
        val seg = cleanSegment(raw)
        if (seg.isEmpty()) return true
        if (spokenBuf.isNotEmpty() && spokenBuf.length + 1 + seg.length > maxChars) {
            capped = true
            return false
        }
        if (spokenBuf.isNotEmpty()) spokenBuf.append(' ')
        spokenBuf.append(seg)
        emit(seg)
        return true
    }

    private fun cleanSegment(raw: String): String {
        var s = WHITESPACE.replace(raw, " ").trim()
        if (s.isEmpty()) return ""
        if (FOREIGN_SCRIPT.containsMatchIn(s)) {
            s = s.split(SENTENCE_SPLIT).filter { !FOREIGN_SCRIPT.containsMatchIn(it) }.joinToString(" ").trim()
        }
        return if (s.any { it.isLetterOrDigit() }) s else ""
    }

    /**
     * Removes <think>...</think>, template tokens and markdown characters; stops at <tool_call>. A tag that may still
     * be completed by the next delta is held back (and prepended to it) instead of being spoken.
     */
    private fun filter(delta: String): String {
        val s = if (held.isEmpty()) delta else held.toString() + delta
        held.setLength(0)
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            if (inThink) {
                val end = s.indexOf(THINK_END, i)
                if (end >= 0) {
                    inThink = false
                    i = end + THINK_END.length
                    continue
                }
                holdPartial(s, i, THINK_END)
                return out.toString()
            }
            val c = s[i]
            if (c == '<') {
                val rest = s.substring(i)
                when {
                    rest.startsWith(THINK_START) -> { inThink = true; i += THINK_START.length }
                    rest.startsWith(THINK_END) -> i += THINK_END.length
                    rest.startsWith(TOOL_START) -> { markup = true; return out.toString() }
                    rest.startsWith("<|") -> {
                        val e = rest.indexOf("|>")
                        if (e >= 0) {
                            i += e + 2
                        } else if (rest.length < MAX_TOKEN_CHARS) {
                            held.append(rest)
                            return out.toString()
                        } else {
                            i += 2
                        }
                    }
                    isPartialTag(rest) -> { held.append(rest); return out.toString() }
                    else -> i++                                    // a stray '<'
                }
                continue
            }
            out.append(if (c in MARKDOWN) ' ' else c)
            i++
        }
        return out.toString()
    }

    private fun isPartialTag(rest: String): Boolean = TAGS.any { it.length > rest.length && it.startsWith(rest) }

    /** Keeps the tail of [s] (from [from]) that could be the beginning of [tag]. */
    private fun holdPartial(s: String, from: Int, tag: String) {
        var k = maxOf(from, s.length - tag.length + 1)
        while (k < s.length) {
            if (tag.startsWith(s.substring(k))) {
                held.append(s, k, s.length)
                return
            }
            k++
        }
    }

    private companion object {
        const val THINK_START = "<think>"
        const val THINK_END = "</think>"
        const val TOOL_START = "<tool_call>"
        const val MAX_TOKEN_CHARS = 24
        const val MARKDOWN = "*#`_>~|"
        val TAGS = listOf(THINK_START, THINK_END, TOOL_START)
        val WHITESPACE = Regex("\\s+")
        val SENTENCE_SPLIT = Regex("(?<=[.!؟?؛])\\s*")
        val FOREIGN_SCRIPT = Regex("[\\u3040-\\u30FF\\u3400-\\u4DBF\\u4E00-\\u9FFF\\uAC00-\\uD7AF]")
    }
}
