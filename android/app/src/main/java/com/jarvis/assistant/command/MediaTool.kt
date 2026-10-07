package com.jarvis.assistant.command

import android.Manifest
import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.BaseColumns
import android.provider.MediaStore
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Real media playback (no new dependency).
 *
 *  - music   : ONLY audio files on the phone (MediaStore). Nothing online (no YouTube / Spotify / music-app search) is
 *              ever opened for "آهنگ X رو پخش کن"; if the file is not found the user is told so.
 *  - movie   : ONLY video files on the phone (MediaStore, with "قسمت N" / "فصل N" / S01E02 matching in name and folder).
 *              YouTube is never opened for "فیلم X رو پخش کن".
 *  - video   : ("توی یوتیوب ویدئوی X رو پخش کن") the best matching YouTube video is found and ITS OWN page is opened in the
 *              YouTube app. Android has no API to force playback, so the message says "opened", never "playing".
 *              Only if the video page cannot be found, the search page is opened as a fallback and that is said honestly.
 *  - youtube : (no query) launches the YouTube app, else youtube.com
 *
 * The lookup (MediaStore scan, YouTube result page) runs on a background thread ([executeAsync]); every Intent is
 * started on the main thread. Permission for media files is requested through the existing [MediaPermissionActivity].
 */
class MediaTool(private val app: Context) : JarvisTool {

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "jarvis-media").apply { isDaemon = true } }
    private val busy = AtomicBoolean(false)

    override val name: String = NAME

    override fun canHandle(action: JarvisAction): Boolean =
        action is JarvisAction.ToolCall && action.tool == NAME

    /** Synchronous fallback. Music/movie still search the phone only (never online). Prefer [executeAsync]. */
    override fun execute(action: JarvisAction): JarvisActionExecutor.Outcome {
        val (kind, query) = argsOf(action) ?: return JarvisActionExecutor.Outcome(false, "این کار هنوز پشتیبانی نمی‌شود.")
        return try {
            when {
                kind == KIND_YOUTUBE && query.isEmpty() -> openYoutube()
                kind !in KINDS -> JarvisActionExecutor.Outcome(false, "نوع رسانه را متوجه نشدم.")
                query.isEmpty() -> JarvisActionExecutor.Outcome(false, if (kind == KIND_MUSIC) "کدام آهنگ را پخش کنم؟" else if (kind == KIND_MOVIE) "کدام فیلم را پخش کنم؟" else "کدام ویدیو را باز کنم؟")
                kind == KIND_MUSIC || kind == KIND_MOVIE -> {
                    // Local playback is confirmed only by the player itself, so it needs [executeAsync].
                    if (!hasPermission(kind)) { requestPermission(); permissionOutcome() }
                    else JarvisActionExecutor.Outcome(false, FAILURE)
                }
                else -> playYoutube(query, null)
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "Media action failed", e)
            JarvisActionExecutor.Outcome(false, FAILURE)
        }
    }

    /** Lookup on a background thread; [onResult] is invoked on the main thread, exactly once (except an ignored duplicate). */
    fun executeAsync(action: JarvisAction, onResult: (JarvisActionExecutor.Outcome) -> Unit) {
        val (kind, query) = argsOf(action) ?: run {
            onResult(JarvisActionExecutor.Outcome(false, "این کار هنوز پشتیبانی نمی‌شود."))
            return
        }
        if (query.isEmpty() || kind !in KINDS) { onResult(execute(action)); return }
        if (!busy.compareAndSet(false, true)) { Log.w(TAG, "Media already in progress; duplicate ignored"); return }

        val wantsLocal = kind == KIND_MUSIC || kind == KIND_MOVIE
        if (wantsLocal && !hasPermission(kind)) {
            // No access to the phone's media files yet: ask via MediaPermissionActivity, the user then repeats the command.
            busy.set(false)
            requestPermission()
            onResult(permissionOutcome())
            return
        }

        try {
            worker.execute {
                val local = if (wantsLocal) {
                    try { findLocal(kind, query) } catch (e: RuntimeException) { Log.w(TAG, "Local search failed"); null }
                } else null
                val videoId = if (!wantsLocal) {
                    try { YoutubeLookup.firstVideoId(query) } catch (e: Exception) { Log.w(TAG, "YouTube lookup failed"); null }
                } else null
                main.post {
                    val deliver: (JarvisActionExecutor.Outcome) -> Unit = { o -> busy.set(false); onResult(o) }
                    try {
                        if (wantsLocal) finishLocal(kind, query, local, deliver)
                        else deliver(playYoutube(query, videoId))
                    } catch (e: RuntimeException) {
                        Log.w(TAG, "Media action failed", e)
                        deliver(JarvisActionExecutor.Outcome(false, FAILURE))
                    }
                }
            }
        } catch (e: RuntimeException) {
            busy.set(false)
            onResult(JarvisActionExecutor.Outcome(false, FAILURE))
        }
    }

    private fun argsOf(action: JarvisAction): Pair<String, String>? {
        val call = action as? JarvisAction.ToolCall ?: return null
        return Pair(call.args["kind"].orEmpty(), call.args["query"].orEmpty().trim())
    }

    // ---- files on the phone -----------------------------------------------------------------------

    private class LocalFile(val uri: Uri, val mime: String?, val title: String)

    private fun permissionOutcome() = JarvisActionExecutor.Outcome(
        false, "برای پیدا کردن آهنگ و فیلم‌های گوشی اجازه‌ی دسترسی به فایل‌ها لازم است. اجازه را بدهید و دوباره بگویید."
    )

    private fun hasPermission(kind: String): Boolean {
        val perm = if (Build.VERSION.SDK_INT >= 33) {
            if (kind == KIND_MUSIC) "android.permission.READ_MEDIA_AUDIO" else "android.permission.READ_MEDIA_VIDEO"
        } else Manifest.permission.READ_EXTERNAL_STORAGE
        return app.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermission() {
        try {
            app.startActivity(Intent(app, MediaPermissionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: RuntimeException) {
            Log.w(TAG, "Cannot open permission screen", e)
        }
    }

    /** Main thread. Plays the local file inside JARVIS; if there is none says so — NEVER opens anything online. */
    private fun finishLocal(kind: String, query: String, f: LocalFile?, onResult: (JarvisActionExecutor.Outcome) -> Unit) {
        val isMusic = kind == KIND_MUSIC
        if (f == null) {
            val what = if (isMusic) "آهنگ" else "فیلم"
            onResult(JarvisActionExecutor.Outcome(false, "$what «$query» را در فایل‌های گوشی پیدا نکردم. اگر می‌خواهید از یوتیوب پخش شود، بگویید «توی یوتیوب ویدیوی ... را پخش کن»."))
            return
        }
        val done: (Boolean) -> Unit = { ok ->
            onResult(
                if (ok) JarvisActionExecutor.Outcome(true, "پخش «${f.title}» شروع شد.")
                else JarvisActionExecutor.Outcome(false, "«${f.title}» را در گوشی پیدا کردم، اما پخش آن شروع نشد.")
            )
        }
        if (isMusic) InternalMediaPlayer.playAudio(app, f.uri, done)
        else InternalMediaPlayer.playVideo(app, f.uri, f.title, done)
    }

    /** Background thread. Best matching audio / video file, or null. */
    private fun findLocal(kind: String, query: String): LocalFile? {
        val audio = kind == KIND_MUSIC
        val q = MediaMatcher.parse(query, allowEpisode = !audio)
        if (q.tokens.isEmpty()) return null
        val base = if (audio) MediaStore.Audio.Media.EXTERNAL_CONTENT_URI else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val pathCol = if (Build.VERSION.SDK_INT >= 29) MediaStore.MediaColumns.RELATIVE_PATH else MediaStore.MediaColumns.DATA
        val projection = if (audio) {
            arrayOf(BaseColumns._ID, MediaStore.MediaColumns.TITLE, MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.MIME_TYPE, MediaStore.Audio.AudioColumns.ARTIST, pathCol)
        } else {
            arrayOf(BaseColumns._ID, MediaStore.MediaColumns.TITLE, MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.MIME_TYPE, pathCol)
        }
        val cursor = app.contentResolver.query(base, projection, null, null, null) ?: return null
        return cursor.use { c ->
            val iId = c.getColumnIndexOrThrow(BaseColumns._ID)
            val iTitle = c.getColumnIndexOrThrow(MediaStore.MediaColumns.TITLE)
            val iName = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val iMime = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val iArtist = if (audio) c.getColumnIndex(MediaStore.Audio.AudioColumns.ARTIST) else -1
            val iPath = c.getColumnIndex(pathCol)
            var best: LocalFile? = null
            var bestLen = Int.MAX_VALUE
            var rows = 0
            while (c.moveToNext() && rows++ < MAX_ROWS) {
                val title = c.getString(iTitle).orEmpty()
                val name = c.getString(iName).orEmpty().substringBeforeLast('.')
                val artist = if (iArtist >= 0) c.getString(iArtist).orEmpty() else ""
                // Folder names ("Series/S01/") help episode/season matching, but are not used to rank.
                val folder = if (!audio && iPath >= 0) c.getString(iPath).orEmpty() else ""
                val own = MediaMatcher.normalize("$title $name")
                val hay = MediaMatcher.normalize("$title $name $artist $folder")
                if (!q.matches(hay) || own.length >= bestLen) continue
                bestLen = own.length
                best = LocalFile(ContentUris.withAppendedId(base, c.getLong(iId)), c.getString(iMime),
                    title.ifBlank { name })
            }
            best
        }
    }

    // ---- YouTube (only for "توی یوتیوب ویدئوی X رو پخش کن") ----------------------------------------

    /**
     * Main thread. [videoId]: the best matching video found on YouTube. Its own page is opened in the YouTube app.
     * Android cannot force playback from outside, so this reports "opened", never "playing".
     */
    private fun playYoutube(query: String, videoId: String?): JarvisActionExecutor.Outcome {
        if (videoId != null) {
            val uri = Uri.parse("https://www.youtube.com/watch?v=$videoId")
            if (start(Intent(Intent.ACTION_VIEW, uri).setPackage(YOUTUBE_PKG))) {
                return JarvisActionExecutor.Outcome(true, "صفحه‌ی ویدیوی «$query» را در برنامه‌ی یوتیوب باز کردم. اگر خودکار پخش نشد، دکمه‌ی پخش را بزنید.")
            }
            if (start(Intent(Intent.ACTION_VIEW, uri))) {
                return JarvisActionExecutor.Outcome(true, "برنامه‌ی یوتیوب پیدا نشد؛ صفحه‌ی ویدیوی «$query» را با برنامه‌ی پیش‌فرض باز کردم. پخش خودکار تضمین نیست.")
            }
        }
        return searchYoutube(query)
    }

    /** Fallback: the video page could not be found/opened, so only the search results are opened (and it says so). */
    private fun searchYoutube(query: String): JarvisActionExecutor.Outcome {
        val msg = "پخش مستقیم ویدیو ممکن نشد و صفحه‌ی خود ویدیو را پیدا نکردم؛ فقط نتیجه‌ی جستجوی «$query» را در یوتیوب باز کردم. چیزی پخش نشده است."
        val inApp = Intent(Intent.ACTION_SEARCH).apply {
            setPackage(YOUTUBE_PKG)
            putExtra(SearchManager.QUERY, query)
        }
        if (start(inApp)) return JarvisActionExecutor.Outcome(true, msg)
        val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(query)))
        if (start(web)) return JarvisActionExecutor.Outcome(true, msg)
        return JarvisActionExecutor.Outcome(false, "برنامه‌ای برای باز کردن یوتیوب پیدا نشد.")
    }

    private fun openYoutube(): JarvisActionExecutor.Outcome {
        val launch = app.packageManager.getLaunchIntentForPackage(YOUTUBE_PKG)
        if (launch != null && start(launch)) return JarvisActionExecutor.Outcome(true)
        if (start(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com")))) return JarvisActionExecutor.Outcome(true)
        return JarvisActionExecutor.Outcome(false, "برنامه یوتیوب پیدا نشد.")
    }

    private fun start(intent: Intent): Boolean {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            app.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            false
        }
    }

    companion object {
        const val NAME = "play_media"
        const val KIND_MUSIC = "music"
        const val KIND_VIDEO = "video"
        const val KIND_MOVIE = "movie"
        const val KIND_YOUTUBE = "youtube"
        private val KINDS = setOf(KIND_MUSIC, KIND_VIDEO, KIND_MOVIE, KIND_YOUTUBE)
        private const val YOUTUBE_PKG = "com.google.android.youtube"
        private const val TAG = "MediaTool"
        private const val MAX_ROWS = 50_000
        private const val FAILURE = "نتوانستم رسانه را اجرا کنم."
    }
}

/** Finds the best matching video of a YouTube search (videos only) so its own page can be opened. Background thread only. */
internal object YoutubeLookup {
    private val FIRST_ID = Regex("\"videoRenderer\":\\{\"videoId\":\"([A-Za-z0-9_-]{11})\"")
    private val RENDERER = Regex(
        "\"videoRenderer\":\\{\"videoId\":\"([A-Za-z0-9_-]{11})\"(?:(?!\"videoRenderer\").){0,2500}?\"title\":\\{\"runs\":\\[\\{\"text\":\"((?:[^\"\\\\]|\\\\.)*)\"",
        RegexOption.DOT_MATCHES_ALL
    )
    private const val UA = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
    private const val MAX_BYTES = 2_000_000
    private const val MAX_CANDIDATES = 12

    /** Id of the result whose title matches the query best (earliest wins ties); null if nothing was found. */
    fun firstVideoId(query: String): String? {
        val url = URL("https://www.youtube.com/results?search_query=" + URLEncoder.encode(query, "UTF-8") + "&sp=EgIQAQ%3D%3D")
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 4_000
            conn.readTimeout = 5_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", UA)
            conn.setRequestProperty("Accept-Language", "fa,en;q=0.8")
            conn.setRequestProperty("Cookie", "CONSENT=YES+1; SOCS=CAI")
            if (conn.responseCode != 200) return null
            val text = conn.inputStream.use { readLimited(it) }
            return pickBest(text, query)
        } finally {
            conn.disconnect()
        }
    }

    internal fun pickBest(page: String, query: String): String? {
        val tokens = MediaMatcher.parse(query, allowEpisode = false).tokens
        var bestId: String? = null
        var bestScore = -1
        val seen = HashSet<String>()
        for (m in RENDERER.findAll(page)) {
            val id = m.groupValues[1]
            if (!seen.add(id)) continue
            val title = MediaMatcher.normalize(unescape(m.groupValues[2]))
            val score = tokens.count { title.contains(it) }
            if (score > bestScore) { bestScore = score; bestId = id }
            if (seen.size >= MAX_CANDIDATES) break
        }
        return bestId ?: FIRST_ID.find(page)?.groupValues?.get(1)
    }

    private fun unescape(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            if (ch == '\\' && i + 1 < s.length) {
                val n = s[i + 1]
                when {
                    n == 'u' && i + 5 < s.length -> {
                        val code = s.substring(i + 2, i + 6).toIntOrNull(16)
                        if (code != null) { sb.append(code.toChar()); i += 6; continue }
                        sb.append(n); i += 2; continue
                    }
                    n == 'n' || n == 't' -> { sb.append(' '); i += 2; continue }
                    else -> { sb.append(n); i += 2; continue }
                }
            }
            sb.append(ch)
            i++
        }
        return sb.toString()
    }

    private fun readLimited(input: InputStream): String {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            total += n
            if (total > MAX_BYTES) break
        }
        return out.toString("UTF-8")
    }
}

/** Matches a spoken "name [فصل M] [قسمت N]" against file names / titles / folders. Pure (no Android). */
internal object MediaMatcher {

    class Query(val tokens: List<String>, val episode: Int?, val season: Int?) {

        /** [hay] must already be [normalize]d. */
        fun matches(hay: String): Boolean {
            if (tokens.isEmpty()) return false
            val missing = tokens.count { !hay.contains(it) }
            if (missing > (if (tokens.size >= 4) 1 else 0)) return false
            if (episode == null && season == null) return true
            return episodeMatches(hay)
        }

        private fun episodeMatches(hay: String): Boolean {
            // 1) explicit season+episode pairs: S01E02, 1x02
            val pairs = ArrayList<Pair<Int?, Int?>>()
            SE.findAll(hay).forEach { pairs.add(Pair(it.groupValues[1].toIntOrNull(), it.groupValues[2].toIntOrNull())) }
            SX.findAll(hay).forEach { pairs.add(Pair(it.groupValues[1].toIntOrNull(), it.groupValues[2].toIntOrNull())) }
            if (pairs.isNotEmpty()) {
                return pairs.any { (s, e) -> (episode == null || e == episode) && (season == null || s == season) }
            }
            // 2) separate season tag (فصل 2 / season 2 / S02) and episode tag (قسمت 5 / E05 / episode 5)
            if (season != null) {
                val seasons = SEASON_TAG.findAll(hay).mapNotNull { (it.groupValues[1].ifEmpty { it.groupValues[2] }).toIntOrNull() }.toList()
                if (seasons.isNotEmpty() && season !in seasons) return false
            }
            if (episode == null) return true
            val tagged = TAGGED.findAll(hay).toList()
            if (tagged.isNotEmpty()) return tagged.any { it.groupValues[1].toIntOrNull() == episode }
            return Regex("(?<![0-9])0*$episode(?![0-9])").containsMatchIn(hay)
        }
    }

    private const val ORD = "اول|دوم|سوم|چهارم|پنجم|ششم|هفتم|هشتم|نهم|دهم"
    private val ORDINALS = mapOf(
        "اول" to 1, "دوم" to 2, "سوم" to 3, "چهارم" to 4, "پنجم" to 5,
        "ششم" to 6, "هفتم" to 7, "هشتم" to 8, "نهم" to 9, "دهم" to 10
    )
    private val SE = Regex("(?<![a-z0-9])s0*(\\d{1,2}) ?e0*(\\d{1,3})(?![0-9])")
    private val SX = Regex("(?<![a-z0-9])0*(\\d{1,2})x0*(\\d{1,3})(?![0-9a-z])")
    private val TAGGED = Regex("(?:(?<![a-z0-9])(?:e|ep|episode)|قسمت) ?0*(\\d{1,3})(?![0-9])")
    private val EPISODE = Regex("(?:قسمت|episode|ep) ?0*(\\d{1,3}|$ORD)")
    private val SEASON = Regex("(?:فصل|season) ?0*(\\d{1,2})")
    private val SEASON_TAG = Regex("(?:(?:فصل|season) ?0*(\\d{1,2})|(?<![a-z0-9])s0*(\\d{1,2})(?![0-9a-z]))")
    private val STOP = setOf(
        "از", "رو", "را", "آهنگ", "اهنگ", "موزیک", "فیلم", "سریال", "ویدیو", "ویدئو", "کلیپ",
        "song", "music", "movie", "film", "video"
    )

    fun normalize(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s.lowercase()) {
            when {
                ch in '\u064B'..'\u065F' || ch == '\u0670' -> { /* diacritics: dropped */ }
                ch == 'ي' -> sb.append('ی')
                ch == 'ك' -> sb.append('ک')
                ch in '۰'..'۹' -> sb.append('0' + (ch - '۰'))
                ch in '٠'..'٩' -> sb.append('0' + (ch - '٠'))
                ch.isLetterOrDigit() -> sb.append(ch)
                else -> sb.append(' ')
            }
        }
        return sb.toString().replace(Regex("\\s+"), " ").trim()
    }

    fun parse(raw: String, allowEpisode: Boolean): Query {
        var q = normalize(raw)
        var episode: Int? = null
        var season: Int? = null
        if (allowEpisode) {
            val se = SE.find(q)
            if (se != null) {
                season = se.groupValues[1].toIntOrNull()
                episode = se.groupValues[2].toIntOrNull()
                q = q.removeRange(se.range)
            }
            if (episode == null) {
                val ep = EPISODE.find(q)
                if (ep != null) {
                    val v = ep.groupValues[1]
                    episode = v.toIntOrNull() ?: ORDINALS[v]
                    q = q.removeRange(ep.range)
                }
            }
            val sn = SEASON.find(q)
            if (sn != null) {
                season = sn.groupValues[1].toIntOrNull()
                q = q.removeRange(sn.range)
            }
        }
        val tokens = q.split(' ').filter { it.isNotEmpty() && it !in STOP }
        return Query(tokens, episode, season)
    }
}

/**
 * Fast-path parser: "آهنگ X رو پخش کن", "ویدئوی X رو پخش کن" (YouTube), "فیلم X قسمت Y رو پخش کن" (phone files first),
 * "YouTube رو باز کن". Null = not a media command.
 */
object MediaCommandParser {

    private const val VERB_PLAY = "(?:پخش کن|پخش کنید|پلی کن|پلی کنید|بزن|بذار|بگذار|اجرا کن|play)"
    private const val VERB_OPEN = "(?:باز کن|باز کنید|پخش کن|پخش کنید|اجرا کن|نشون بده|نشان بده|ببینم|play|open)"
    private const val OBJ = "(?:\\s+(?:رو|را))?"
    private const val VIDEO_WORDS = "(?:ویدیو|ویدئو|کلیپ|video)"
    private const val MOVIE_WORDS = "(?:فیلم|سریال|movie|film)"

    private val musicA = Regex("^(?:لطفا\\s+)?(?:یه\\s+|یک\\s+)?(?:آهنگ|اهنگ|موزیک|music|song)ی?\\s+(.+?)$OBJ\\s+$VERB_PLAY$")
    private val musicB = Regex("^(?:لطفا\\s+)?(?:$VERB_PLAY)\\s+(?:آهنگ|اهنگ|موزیک|music|song)ی?\\s+(.+)$")
    private val videoA = Regex("^(?:لطفا\\s+)?$VIDEO_WORDS" + "ی?\\s+(.+?)$OBJ\\s+$VERB_OPEN$")
    private val videoB = Regex("^(?:لطفا\\s+)?(?:$VERB_OPEN)\\s+$VIDEO_WORDS" + "ی?\\s+(.+)$")
    private val movieA = Regex("^(?:لطفا\\s+)?$MOVIE_WORDS" + "ی?\\s+(.+?)$OBJ\\s+$VERB_OPEN$")
    private val movieB = Regex("^(?:لطفا\\s+)?(?:$VERB_OPEN)\\s+$MOVIE_WORDS" + "ی?\\s+(.+)$")
    private val inYoutube = Regex("^(?:لطفا\\s+)?(?:تو|توی|در|داخل|on|in)\\s+(?:یوتیوب|یوتوب|youtube)\\s+(.+?)$OBJ\\s+(?:پخش کن|باز کن|play|open)$")
    private val openYoutube = Regex("^(?:لطفا\\s+)?(?:برنامه\\s+)?(?:یوتیوب|یوتوب|یوتیوپ|یو تیوب|youtube)$OBJ\\s+(?:رو\\s+)?(?:باز کن|باز کنید|اجرا کن|open)$")

    /** "آهنگ رو پخش کن" / "یه آهنگ پخش کن" / "پخش کن آهنگ": a song request without a name. */
    private val musicNoName = Regex("^(?:لطفا\\s+)?(?:(?:یه|یک)\\s+)?(?:آهنگ|اهنگ|موزیک|music|song)ی?$OBJ\\s+$VERB_PLAY$|^(?:لطفا\\s+)?$VERB_PLAY\\s+(?:(?:یه|یک)\\s+)?(?:آهنگ|اهنگ|موزیک|music|song)$")

    private fun norm(s: String): String = s.lowercase()
        .replace('ي', 'ی').replace('ك', 'ک')
        .replace("\u200c", " ").replace(Regex("[\\p{Punct}،؟؛.!]"), " ")
        .replace(Regex("\\s+"), " ").trim()

    fun parse(text: String): JarvisAction? {
        val t = norm(text)
        if (t.isEmpty()) return null
        if (openYoutube.matches(t)) return call(MediaTool.KIND_YOUTUBE, "")
        if (musicNoName.matches(t)) return call(MediaTool.KIND_MUSIC, "")
        for (r in listOf(musicA, musicB)) r.matchEntire(t)?.let { return query(MediaTool.KIND_MUSIC, it.groupValues[1]) }
        inYoutube.matchEntire(t)?.let { return query(MediaTool.KIND_VIDEO, it.groupValues[1]) }
        for (r in listOf(videoA, videoB)) r.matchEntire(t)?.let { return query(MediaTool.KIND_VIDEO, it.groupValues[1]) }
        for (r in listOf(movieA, movieB)) r.matchEntire(t)?.let { return query(MediaTool.KIND_MOVIE, it.groupValues[1]) }
        return null
    }

    private fun query(kind: String, q: String): JarvisAction? {
        val clean = q.trim().removeSuffix(" رو").removeSuffix(" را").trim()
        return if (clean.isEmpty() || clean == "رو" || clean == "را") null else call(kind, clean)
    }

    /** True for a song request that has no name yet (starts the language / name questions). */
    fun isNamelessMusic(a: JarvisAction?): Boolean =
        a is JarvisAction.ToolCall && a.tool == MediaTool.NAME && a.args["kind"] == MediaTool.KIND_MUSIC && a.args["query"].orEmpty().isBlank()

    private fun call(kind: String, q: String): JarvisAction =
        JarvisAction.ToolCall(MediaTool.NAME, mapOf("kind" to kind, "query" to q))
}
