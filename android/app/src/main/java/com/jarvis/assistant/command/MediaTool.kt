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
 *  - music   : audio files on the phone (MediaStore) -> YouTube video found and opened -> music app search -> YouTube search page
 *  - movie   : video files on the phone (MediaStore, with "قسمت N" episode matching) -> YouTube video found and opened -> search page
 *  - video   : YouTube video found by name and opened in the YouTube app (it starts playing) -> search page only as a last resort
 *  - youtube : (no query) launches the YouTube app, else youtube.com
 *
 * The lookup (MediaStore scan, YouTube result page) runs on a background thread ([executeAsync]); every Intent is
 * started on the main thread. Success is reported only when Android accepted an Intent, and the message says what was
 * really done (a local file opened / a video opened in YouTube / only a search opened).
 */
class MediaTool(private val app: Context) : JarvisTool {

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "jarvis-media").apply { isDaemon = true } }
    private val busy = AtomicBoolean(false)
    private val prefs by lazy { app.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    override val name: String = NAME

    override fun canHandle(action: JarvisAction): Boolean =
        action is JarvisAction.ToolCall && action.tool == NAME

    /** Synchronous fallback (no scan, no network): used for questions and the plain "open YouTube". Prefer [executeAsync]. */
    override fun execute(action: JarvisAction): JarvisActionExecutor.Outcome {
        val (kind, query) = argsOf(action) ?: return JarvisActionExecutor.Outcome(false, "این کار هنوز پشتیبانی نمی‌شود.")
        return try {
            when {
                kind == KIND_YOUTUBE && query.isEmpty() -> openYoutube()
                kind !in KINDS -> JarvisActionExecutor.Outcome(false, "نوع رسانه را متوجه نشدم.")
                query.isEmpty() -> JarvisActionExecutor.Outcome(false, if (kind == KIND_MUSIC) "کدام آهنگ را پخش کنم؟" else "کدام ویدیو را باز کنم؟")
                else -> playOnline(kind, query, null)
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
        if (wantsLocal && !hasPermission(kind) && !prefs.getBoolean(KEY_ASKED, false)) {
            // First time only: ask for access to the phone's media files, then the user repeats the command.
            prefs.edit().putBoolean(KEY_ASKED, true).apply()
            busy.set(false)
            requestPermission()
            onResult(JarvisActionExecutor.Outcome(false, "برای پیدا کردن آهنگ و فیلم‌های گوشی اجازه‌ی دسترسی به فایل‌ها لازم است. اجازه را بدهید و دوباره بگویید."))
            return
        }

        try {
            worker.execute {
                val local = if (wantsLocal && hasPermission(kind)) {
                    try { findLocal(kind, query) } catch (e: RuntimeException) { Log.w(TAG, "Local search failed"); null }
                } else null
                val videoId = if (local == null) {
                    try { YoutubeLookup.firstVideoId(query) } catch (e: Exception) { Log.w(TAG, "YouTube lookup failed"); null }
                } else null
                main.post {
                    val outcome = try {
                        (if (local != null) playLocal(local, kind) else null) ?: playOnline(kind, query, videoId)
                    } catch (e: RuntimeException) {
                        Log.w(TAG, "Media action failed", e)
                        JarvisActionExecutor.Outcome(false, FAILURE)
                    } finally { busy.set(false) }
                    onResult(outcome)
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

    /** Background thread. Best matching audio / video file, or null. */
    private fun findLocal(kind: String, query: String): LocalFile? {
        val audio = kind == KIND_MUSIC
        val q = MediaMatcher.parse(query, allowEpisode = !audio)
        if (q.tokens.isEmpty()) return null
        val base = if (audio) MediaStore.Audio.Media.EXTERNAL_CONTENT_URI else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val projection = if (audio) {
            arrayOf(BaseColumns._ID, MediaStore.MediaColumns.TITLE, MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.MIME_TYPE, MediaStore.Audio.AudioColumns.ARTIST)
        } else {
            arrayOf(BaseColumns._ID, MediaStore.MediaColumns.TITLE, MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.MIME_TYPE)
        }
        val cursor = app.contentResolver.query(base, projection, null, null, null) ?: return null
        return cursor.use { c ->
            val iId = c.getColumnIndexOrThrow(BaseColumns._ID)
            val iTitle = c.getColumnIndexOrThrow(MediaStore.MediaColumns.TITLE)
            val iName = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val iMime = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val iArtist = if (audio) c.getColumnIndex(MediaStore.Audio.AudioColumns.ARTIST) else -1
            var best: LocalFile? = null
            var bestLen = Int.MAX_VALUE
            var rows = 0
            while (c.moveToNext() && rows++ < MAX_ROWS) {
                val title = c.getString(iTitle).orEmpty()
                val name = c.getString(iName).orEmpty().substringBeforeLast('.')
                val artist = if (iArtist >= 0) c.getString(iArtist).orEmpty() else ""
                val hay = MediaMatcher.normalize("$title $name $artist")
                if (!q.matches(hay) || hay.length >= bestLen) continue
                bestLen = hay.length
                best = LocalFile(ContentUris.withAppendedId(base, c.getLong(iId)), c.getString(iMime),
                    title.ifBlank { name })
            }
            best
        }
    }

    /** Main thread. Null = no app could open the file (the caller falls back to the online source). */
    private fun playLocal(f: LocalFile, kind: String): JarvisActionExecutor.Outcome? {
        val fallback = if (kind == KIND_MUSIC) "audio/*" else "video/*"
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(f.uri, f.mime?.takeIf { it.isNotBlank() } ?: fallback)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return if (start(intent)) JarvisActionExecutor.Outcome(true, "پخش «${f.title}» را از فایل‌های گوشی شروع کردم.") else null
    }

    // ---- online ------------------------------------------------------------------------------------

    /** Main thread. [videoId]: a video already found on YouTube (opened in the app, which starts playing it). */
    private fun playOnline(kind: String, query: String, videoId: String?): JarvisActionExecutor.Outcome {
        val noun = when (kind) { KIND_MUSIC -> "آهنگ"; KIND_MOVIE -> "فیلم"; else -> "ویدیو" }
        if (videoId != null && openYoutubeVideo(videoId)) {
            return JarvisActionExecutor.Outcome(true, "$noun «$query» را در یوتیوب باز کردم تا پخش شود.")
        }
        if (kind == KIND_MUSIC) {
            val play = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
                putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
                putExtra(SearchManager.QUERY, query)
            }
            if (start(play)) return JarvisActionExecutor.Outcome(true, "درخواست پخش «$query» را به برنامه‌ی موسیقی دادم.")
        }
        return searchYoutube(query, noun)
    }

    private fun openYoutubeVideo(id: String): Boolean {
        val uri = Uri.parse("https://www.youtube.com/watch?v=$id")
        if (start(Intent(Intent.ACTION_VIEW, uri).setPackage(YOUTUBE_PKG))) return true
        return start(Intent(Intent.ACTION_VIEW, uri))
    }

    /** Last resort: the video itself could not be found, so only the search results are opened (and said so). */
    private fun searchYoutube(query: String, noun: String): JarvisActionExecutor.Outcome {
        val msg = "نتوانستم $noun را مستقیم پیدا کنم؛ نتیجه‌ی جستجوی «$query» را در یوتیوب باز کردم."
        val inApp = Intent(Intent.ACTION_SEARCH).apply {
            setPackage(YOUTUBE_PKG)
            putExtra(SearchManager.QUERY, query)
        }
        if (start(inApp)) return JarvisActionExecutor.Outcome(true, msg)
        val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(query)))
        if (start(web)) return JarvisActionExecutor.Outcome(true, msg)
        return JarvisActionExecutor.Outcome(false, "برنامه‌ای برای پخش $noun پیدا نشد.")
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
        private const val PREFS = "jarvis_media"
        private const val KEY_ASKED = "media_permission_asked"
        private const val MAX_ROWS = 50_000
        private const val FAILURE = "نتوانستم رسانه را اجرا کنم."
    }
}

/** Finds the first video of a YouTube search (videos only) so it can be opened directly. Background thread only. */
internal object YoutubeLookup {
    private val VIDEO_ID = Regex("\"videoRenderer\":\\{\"videoId\":\"([A-Za-z0-9_-]{11})\"")
    private const val UA = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
    private const val MAX_BYTES = 2_000_000

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
            return VIDEO_ID.find(text)?.groupValues?.get(1)
        } finally {
            conn.disconnect()
        }
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

/** Matches a spoken "name [قسمت N]" against file names / titles. Pure (no Android). */
internal object MediaMatcher {

    class Query(val tokens: List<String>, val episode: Int?, val season: Int?) {

        /** [hay] must already be [normalize]d. */
        fun matches(hay: String): Boolean {
            if (tokens.isEmpty()) return false
            val missing = tokens.count { !hay.contains(it) }
            if (missing > (if (tokens.size >= 4) 1 else 0)) return false
            return episode == null || episodeMatches(hay, episode)
        }

        private fun episodeMatches(hay: String, ep: Int): Boolean {
            val se = SE.findAll(hay).toList()
            if (se.isNotEmpty()) {
                return se.any { it.groupValues[2].toIntOrNull() == ep && (season == null || it.groupValues[1].toIntOrNull() == season) }
            }
            val tagged = TAGGED.findAll(hay).toList()
            if (tagged.isNotEmpty()) return tagged.any { it.groupValues[1].toIntOrNull() == ep }
            return Regex("(?<![0-9])0*$ep(?![0-9])").containsMatchIn(hay)
        }
    }

    private const val ORD = "اول|دوم|سوم|چهارم|پنجم|ششم|هفتم|هشتم|نهم|دهم"
    private val ORDINALS = mapOf(
        "اول" to 1, "دوم" to 2, "سوم" to 3, "چهارم" to 4, "پنجم" to 5,
        "ششم" to 6, "هفتم" to 7, "هشتم" to 8, "نهم" to 9, "دهم" to 10
    )
    private val SE = Regex("(?<![a-z0-9])s0*(\\d{1,2}) ?e0*(\\d{1,3})(?![0-9])")
    private val TAGGED = Regex("(?:(?<![a-z0-9])(?:e|ep|episode)|قسمت) ?0*(\\d{1,3})(?![0-9])")
    private val EPISODE = Regex("(?:قسمت|episode|ep) ?0*(\\d{1,3}|$ORD)")
    private val SEASON = Regex("(?:فصل|season) ?0*(\\d{1,2})")
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

    private fun norm(s: String): String = s.lowercase()
        .replace('ي', 'ی').replace('ك', 'ک')
        .replace("\u200c", " ").replace(Regex("[\\p{Punct}،؟؛.!]"), " ")
        .replace(Regex("\\s+"), " ").trim()

    fun parse(text: String): JarvisAction? {
        val t = norm(text)
        if (t.isEmpty()) return null
        if (openYoutube.matches(t)) return call(MediaTool.KIND_YOUTUBE, "")
        for (r in listOf(musicA, musicB)) r.matchEntire(t)?.let { return query(MediaTool.KIND_MUSIC, it.groupValues[1]) }
        inYoutube.matchEntire(t)?.let { return query(MediaTool.KIND_VIDEO, it.groupValues[1]) }
        for (r in listOf(videoA, videoB)) r.matchEntire(t)?.let { return query(MediaTool.KIND_VIDEO, it.groupValues[1]) }
        for (r in listOf(movieA, movieB)) r.matchEntire(t)?.let { return query(MediaTool.KIND_MOVIE, it.groupValues[1]) }
        return null
    }

    private fun query(kind: String, q: String): JarvisAction? {
        val clean = q.trim().removeSuffix(" رو").removeSuffix(" را").trim()
        return if (clean.isEmpty()) null else call(kind, clean)
    }

    private fun call(kind: String, q: String): JarvisAction =
        JarvisAction.ToolCall(MediaTool.NAME, mapOf("kind" to kind, "query" to q))
}
