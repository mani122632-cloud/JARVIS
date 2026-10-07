package com.jarvis.assistant.online

import android.util.Xml
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException

/**
 * Stage 3B-1 "Web Answer": REAL online lookup (no API key, no extra dependency) used by the `web_answer` tool.
 *
 * It downloads live search results (DuckDuckGo HTML + Google News RSS), strips them to short plain-text snippets
 * and returns them as a [ToolResult]; the online model then writes the Persian answer ONLY from those snippets.
 * This is NOT Browser Search (nothing is opened on the phone).
 *
 * Honesty: when nothing could be downloaded or parsed the result is an error ([ToolResult.ok] == false) with a
 * real reason; a success is only returned when at least one real result was downloaded. Blocking: call it from a
 * background thread. Queries and results are never logged.
 */
internal class WebAnswerClient {

    /** Cancels the running download (closes the connection). */
    class Call : Cancellable {
        private val cancelled = AtomicBoolean(false)
        @Volatile var connection: HttpURLConnection? = null
        override val isCancelled: Boolean get() = cancelled.get()
        override fun cancel() {
            cancelled.set(true)
            try { connection?.disconnect() } catch (e: RuntimeException) { /* ignore */ }
        }
    }

    private class Item(val title: String, val snippet: String, val host: String, val date: String)

    private enum class Fail { NETWORK, TIMEOUT, BAD_RESPONSE }

    private class WebException(val kind: Fail) : Exception(kind.name)

    fun answer(query: String, call: Call): ToolResult {
        val q = query.trim()
        if (q.isEmpty()) return ToolResult.error(ToolErrorCode.INVALID_ARGS, "query is empty")

        val items = ArrayList<Item>()
        var failure: Fail? = null
        var anyResponse = false

        try {
            items += searchDuckDuckGo(q, call); anyResponse = true
        } catch (e: WebException) { failure = e.kind }
        if (call.isCancelled) return cancelledResult()

        try {
            items += searchGoogleNews(q, call); anyResponse = true
        } catch (e: WebException) { if (failure == null) failure = e.kind }
        if (call.isCancelled) return cancelledResult()

        val unique = items.distinctBy { it.title.lowercase(Locale.ROOT) }.take(MAX_ITEMS)
        if (unique.isEmpty()) {
            return when {
                !anyResponse && failure == Fail.TIMEOUT ->
                    ToolResult.error(ToolErrorCode.TIMEOUT, "دریافت اطلاعات از اینترنت زمان‌بر شد و جوابی نگرفتم.")
                !anyResponse && failure == Fail.NETWORK ->
                    ToolResult.error(ToolErrorCode.UNSUPPORTED, "اتصال به اینترنت برقرار نشد؛ اطلاعات آنلاین دریافت نشد.")
                !anyResponse ->
                    ToolResult.error(ToolErrorCode.UNSUPPORTED, "سرویس جستجو پاسخ قابل‌استفاده‌ای نداد؛ اطلاعات آنلاین دریافت نشد.")
                else -> ToolResult.error(ToolErrorCode.NOT_FOUND, "نتیجه‌ی قابل‌استفاده‌ای برای این سؤال در اینترنت پیدا نشد.")
            }
        }
        return ToolResult.ok(format(unique))
    }

    private fun cancelledResult() = ToolResult.error(ToolErrorCode.TIMEOUT, "دریافت اطلاعات لغو شد.")

    private fun format(items: List<Item>): String {
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
        val sb = StringBuilder()
        sb.append("LIVE WEB RESULTS (downloaded just now, device time ").append(now).append("). ")
        sb.append("Answer in Persian ONLY from these results; never invent numbers. ")
        sb.append("If they do not contain the answer or disagree, say so honestly. Mention the source/date when it matters.\n")
        var n = 0
        for (it in items) {
            n++
            val line = StringBuilder()
            line.append(n).append(") ").append(it.title)
            if (it.snippet.isNotEmpty()) line.append(" — ").append(it.snippet)
            val meta = listOf(it.host, it.date).filter { m -> m.isNotEmpty() }.joinToString(", ")
            if (meta.isNotEmpty()) line.append(" [").append(meta).append("]")
            if (sb.length + line.length + 1 > MAX_CHARS) break
            sb.append(line).append('\n')
        }
        return sb.toString().trim()
    }

    // ---- DuckDuckGo (HTML) -----------------------------------------------------------------------

    private fun searchDuckDuckGo(query: String, call: Call): List<Item> {
        val html = httpGet("https://html.duckduckgo.com/html/?q=" + enc(query), call)
        val out = ArrayList<Item>()
        val parts = html.split(RESULT_SPLIT).drop(1)
        for (part in parts) {
            if (part.contains("result--ad")) continue
            val a = LINK_RE.find(part) ?: continue
            val title = clean(a.groupValues[2])
            if (title.isEmpty()) continue
            val snippet = clean(SNIPPET_RE.find(part)?.groupValues?.get(1) ?: "").take(MAX_SNIPPET)
            out += Item(title.take(160), snippet, hostOf(realUrl(a.groupValues[1])), "")
            if (out.size >= 6) break
        }
        if (out.isEmpty() && html.length < 200) throw WebException(Fail.BAD_RESPONSE)
        return out
    }

    private fun realUrl(href: String): String {
        val h = decodeEntities(href)
        val i = h.indexOf("uddg=")
        if (i < 0) return if (h.startsWith("//")) "https:$h" else h
        val end = h.indexOf('&', i).let { if (it < 0) h.length else it }
        return try { URLDecoder.decode(h.substring(i + 5, end), "UTF-8") } catch (e: Exception) { h }
    }

    private fun hostOf(url: String): String = try {
        URL(url).host.removePrefix("www.")
    } catch (e: MalformedURLException) { "" }

    // ---- Google News (RSS) -----------------------------------------------------------------------

    private fun searchGoogleNews(query: String, call: Call): List<Item> {
        val xml = httpGet("https://news.google.com/rss/search?q=" + enc(query) + "&hl=fa&gl=IR&ceid=IR:fa", call)
        val out = ArrayList<Item>()
        try {
            val p = Xml.newPullParser()
            p.setInput(StringReader(xml))
            var inItem = false
            var title = ""; var date = ""; var src = ""
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    when (p.name) {
                        "item" -> { inItem = true; title = ""; date = ""; src = "" }
                        "title" -> if (inItem) title = p.nextText()
                        "pubDate" -> if (inItem) date = p.nextText()
                        "source" -> if (inItem) src = p.nextText()
                    }
                } else if (ev == XmlPullParser.END_TAG && p.name == "item") {
                    inItem = false
                    val t = clean(title)
                    if (t.isNotEmpty()) out += Item(t.take(200), "", clean(src), date.trim().take(31))
                    if (out.size >= 5) break
                }
                ev = p.next()
            }
        } catch (e: XmlPullParserException) {
            throw WebException(Fail.BAD_RESPONSE)
        } catch (e: IOException) {
            throw WebException(Fail.BAD_RESPONSE)
        }
        return out
    }

    // ---- HTTP ------------------------------------------------------------------------------------

    private fun httpGet(url: String, call: Call): String {
        if (call.isCancelled) throw WebException(Fail.NETWORK)
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "text/html,application/xml,application/rss+xml;q=0.9,*/*;q=0.8")
                setRequestProperty("Accept-Language", "fa,en;q=0.7")
            }
            call.connection = conn
            if (call.isCancelled) conn.disconnect()
            val code = conn.responseCode
            if (code != 200) throw WebException(Fail.BAD_RESPONSE)        // 202 = bot challenge, 4xx/5xx = refused
            conn.inputStream.use { input ->
                val buf = ByteArray(8192)
                val out = ByteArrayOutputStream()
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > MAX_BYTES) break
                }
                return out.toString("UTF-8")
            }
        } catch (e: SocketTimeoutException) {
            throw WebException(Fail.TIMEOUT)
        } catch (e: IOException) {
            throw WebException(Fail.NETWORK)
        } catch (e: RuntimeException) {
            throw WebException(Fail.NETWORK)
        } finally {
            try { conn?.disconnect() } catch (e: RuntimeException) { /* ignore */ }
            call.connection = null
        }
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    // ---- text cleanup ----------------------------------------------------------------------------

    private fun clean(raw: String): String =
        decodeEntities(TAG_RE.replace(raw, " ")).replace(WS_RE, " ").trim()

    private fun decodeEntities(s: String): String {
        if (!s.contains('&')) return s
        var r = NUMERIC_ENTITY_RE.replace(s) { m ->
            val v = m.groupValues[2]
            val cp = if (m.groupValues[1].isNotEmpty()) v.toIntOrNull(16) else v.toIntOrNull()
            if (cp != null && cp in 1..0x10FFFF) String(Character.toChars(cp)) else m.value
        }
        r = r.replace("&quot;", "\"").replace("&apos;", "'").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&nbsp;", " ").replace("&amp;", "&")
        return r
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 5_000
        const val READ_TIMEOUT_MS = 6_000
        const val MAX_BYTES = 600_000
        const val MAX_ITEMS = 9
        const val MAX_SNIPPET = 320
        const val MAX_CHARS = 3_500
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

        val RESULT_SPLIT = Regex("""class="result\s+results_links""")
        val LINK_RE = Regex("""<a[^>]*class="result__a"[^>]*href="([^"]+)"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
        val SNIPPET_RE = Regex("""<a[^>]*class="result__snippet"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
        val TAG_RE = Regex("<[^>]*>")
        val WS_RE = Regex("\\s+")
        val NUMERIC_ENTITY_RE = Regex("&#([xX]?)([0-9a-fA-F]+);")
    }
}
