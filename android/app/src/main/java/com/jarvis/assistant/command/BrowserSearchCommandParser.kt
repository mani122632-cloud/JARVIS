package com.jarvis.assistant.command

/**
 * Stage 3B-2: "سرچ کن X" / "درباره X سرچ کن" / "X رو جستجو کن" / "گوگل کن X" -> a real Chrome search.
 * Pure (no Android, no I/O). Only an explicit search VERB triggers it; questions answered online
 * (Web Answer) are never routed here. Returns a [JarvisAction.ToolCall] for [BrowserSearchTool.NAME];
 * an empty query is passed on so the tool can ask what to search.
 */
object BrowserSearchCommandParser {

    private val VERB = Regex(
        "(?:^|\\s)(?:سرچ|جستجو|جست و جو|جستوجو|گوگل|search|google)\\s*(?:کن|بکن|کنید|بکنید|بزن|بزنید|کنین|بکنین)(?=\\s|$)",
        RegexOption.IGNORE_CASE
    )
    private val ENGLISH = Regex("^(?:please\\s+)?(?:search|google)\\s+(?:for\\s+)?(.+)$", RegexOption.IGNORE_CASE)

    private val LEAD = Regex(
        "^(?:(?:لطفا|لطفاً|جارویس|برام|برایم|برای من|یه|یک|کمی|در مورد|درباره|دربارهٔ|درباره ی|راجع به|راجب|در اینترنت|توی اینترنت|تو اینترنت|در گوگل|توی گوگل|تو گوگل|در کروم|توی کروم|تو کروم|در مرورگر|توی مرورگر|تو مرورگر|اینترنت|گوگل|کروم|مرورگر)\\s+)+"
    )
    private val TRAIL = Regex(
        "(?:\\s+(?:لطفا|لطفاً|رو|را|برام|برایم|جارویس|در اینترنت|توی اینترنت|تو اینترنت|در گوگل|توی گوگل|تو گوگل|در کروم|توی کروم|تو کروم|در مرورگر|توی مرورگر|تو مرورگر|ارباب))+$"
    )

    fun parse(text: String): JarvisAction? {
        val t = normalize(text)
        if (t.isEmpty()) return null
        val query: String
        val m = VERB.find(t)
        if (m != null) {
            query = (t.substring(0, m.range.first) + " " + t.substring(m.range.last + 1)).trim()
        } else {
            val e = ENGLISH.find(t) ?: return null
            query = e.groupValues[1]
        }
        return JarvisAction.ToolCall(BrowserSearchTool.NAME, mapOf("query" to clean(query)))
    }

    private fun clean(raw: String): String {
        var q = raw.replace(Regex("\\s+"), " ").trim()
        var prev: String
        do {
            prev = q
            q = LEAD.replace(q, "").trim()
            q = TRAIL.replace(q, "").trim()
            if (q in FILLERS) q = ""
        } while (q != prev)
        return q.take(MAX_QUERY)
    }

    private fun normalize(s: String): String = s
        .replace('ي', 'ی').replace('ك', 'ک')
        .replace("\u200c", " ").replace("\u200f", " ").replace("\u200e", " ")
        .replace(Regex("[\\u064B-\\u065F\\u0670]"), "")
        .replace(Regex("[.,;:!?؟،؛]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private val FILLERS = setOf("رو", "را", "اینو", "این", "آن", "لطفا", "لطفاً", "جارویس")
    const val MAX_QUERY = 200
}
