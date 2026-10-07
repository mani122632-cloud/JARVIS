package com.jarvis.assistant.command

import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.util.Log

/**
 * Real media playback through Intents and the apps already on the phone (no new dependency).
 *
 *  - music : MEDIA_PLAY_FROM_SEARCH (music apps) -> YouTube search -> youtube.com search page
 *  - video : YouTube search -> youtube.com search page
 *  - youtube (no query): launches the YouTube app, else youtube.com
 *
 * Success is reported only when Android accepted an Intent; the message says what was really done
 * (a request handed to an app / a search opened), never that playback is confirmed.
 */
class MediaTool(private val app: Context) : JarvisTool {

    override val name: String = NAME

    override fun canHandle(action: JarvisAction): Boolean =
        action is JarvisAction.ToolCall && action.tool == NAME

    override fun execute(action: JarvisAction): JarvisActionExecutor.Outcome {
        val call = action as? JarvisAction.ToolCall
            ?: return JarvisActionExecutor.Outcome(false, "این کار هنوز پشتیبانی نمی‌شود.")
        val kind = call.args["kind"].orEmpty()
        val query = call.args["query"].orEmpty().trim()
        return try {
            when (kind) {
                KIND_MUSIC -> if (query.isEmpty()) ask("کدام آهنگ را پخش کنم؟") else playMusic(query)
                KIND_VIDEO -> if (query.isEmpty()) ask("کدام ویدیو را باز کنم؟") else playVideo(query)
                KIND_YOUTUBE -> if (query.isEmpty()) openYoutube() else playVideo(query)
                else -> JarvisActionExecutor.Outcome(false, "نوع رسانه را متوجه نشدم.")
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "Media action failed", e)
            JarvisActionExecutor.Outcome(false, "نتوانستم رسانه را اجرا کنم.")
        }
    }

    private fun ask(q: String) = JarvisActionExecutor.Outcome(false, q)

    private fun playMusic(query: String): JarvisActionExecutor.Outcome {
        val play = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
            putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
            putExtra(SearchManager.QUERY, query)
        }
        if (start(play)) return JarvisActionExecutor.Outcome(true, "درخواست پخش «$query» را به برنامه‌ی موسیقی دادم.")
        return playVideo(query, noun = "آهنگ")
    }

    private fun playVideo(query: String, noun: String = "ویدیو"): JarvisActionExecutor.Outcome {
        val inApp = Intent(Intent.ACTION_SEARCH).apply {
            setPackage(YOUTUBE_PKG)
            putExtra(SearchManager.QUERY, query)
        }
        if (start(inApp)) return JarvisActionExecutor.Outcome(true, "نتیجه‌ی جستجوی $noun «$query» را در یوتیوب باز کردم.")
        val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(query)))
        if (start(web)) return JarvisActionExecutor.Outcome(true, "نتیجه‌ی جستجوی $noun «$query» را در یوتیوب باز کردم.")
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
        const val KIND_YOUTUBE = "youtube"
        private const val YOUTUBE_PKG = "com.google.android.youtube"
        private const val TAG = "MediaTool"
    }
}

/** Fast-path parser: "آهنگ X رو پخش کن", "ویدئوی X رو باز کن", "YouTube رو باز کن". Null = not a media command. */
object MediaCommandParser {

    private const val VERB_PLAY = "(?:پخش کن|پخش کنید|پلی کن|پلی کنید|بزن|بذار|بگذار|اجرا کن|play)"
    private const val VERB_OPEN = "(?:باز کن|باز کنید|پخش کن|پخش کنید|اجرا کن|نشون بده|نشان بده|ببینم|play|open)"
    private const val OBJ = "(?:\\s+(?:رو|را))?"

    private val musicA = Regex("^(?:لطفا\\s+)?(?:یه\\s+|یک\\s+)?(?:آهنگ|اهنگ|موزیک|music|song)ی?\\s+(.+?)$OBJ\\s+$VERB_PLAY$")
    private val musicB = Regex("^(?:لطفا\\s+)?(?:$VERB_PLAY)\\s+(?:آهنگ|اهنگ|موزیک|music|song)ی?\\s+(.+)$")
    private val videoA = Regex("^(?:لطفا\\s+)?(?:ویدیو|ویدئو|فیلم|کلیپ|video)ی?\\s+(.+?)$OBJ\\s+$VERB_OPEN$")
    private val videoB = Regex("^(?:لطفا\\s+)?(?:$VERB_OPEN)\\s+(?:ویدیو|ویدئو|فیلم|کلیپ|video)ی?\\s+(.+)$")
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
        return null
    }

    private fun query(kind: String, q: String): JarvisAction? {
        val clean = q.trim().removeSuffix(" رو").removeSuffix(" را").trim()
        return if (clean.isEmpty()) null else call(kind, clean)
    }

    private fun call(kind: String, q: String): JarvisAction =
        JarvisAction.ToolCall(MediaTool.NAME, mapOf("kind" to kind, "query" to q))
}
