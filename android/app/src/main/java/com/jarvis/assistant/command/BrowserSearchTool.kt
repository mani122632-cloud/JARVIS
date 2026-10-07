package com.jarvis.assistant.command

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import java.net.URLEncoder

/**
 * Stage 3B-2 "Browser Search": opens Chrome (fallback: the default browser) on a real Google search page.
 * Separate from Web Answer (which downloads results in the background and never opens a browser).
 * Success is reported only when the system accepted the VIEW intent; the page itself is never claimed to have loaded.
 * Main thread. The query is never logged.
 */
class BrowserSearchTool(context: Context) : JarvisTool {

    private val appContext = context.applicationContext

    override val name: String = NAME

    override fun canHandle(action: JarvisAction): Boolean =
        action is JarvisAction.ToolCall && action.tool == NAME

    override fun execute(action: JarvisAction): JarvisActionExecutor.Outcome {
        val call = action as? JarvisAction.ToolCall
            ?: return JarvisActionExecutor.Outcome(false, "نتوانستم جست‌وجو را باز کنم.")
        val query = (call.args["query"] ?: "").filter { !it.isISOControl() }.trim().take(BrowserSearchCommandParser.MAX_QUERY)
        if (query.isEmpty()) return JarvisActionExecutor.Outcome(false, "چه چیزی را سرچ کنم؟")

        val uri = Uri.parse("https://www.google.com/search?q=" + URLEncoder.encode(query, "UTF-8"))
        return try {
            appContext.startActivity(viewIntent(uri).setPackage(CHROME))
            JarvisActionExecutor.Outcome(true, "جست‌وجوی «$query» را در کروم باز کردم.")
        } catch (e: ActivityNotFoundException) {
            try {
                appContext.startActivity(viewIntent(uri))
                JarvisActionExecutor.Outcome(true, "جست‌وجوی «$query» را در مرورگر باز کردم.")
            } catch (e2: RuntimeException) {
                Log.w(TAG, "No browser available")
                JarvisActionExecutor.Outcome(false, "مرورگری برای باز کردن جست‌وجو پیدا نکردم.")
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "Browser search failed")
            JarvisActionExecutor.Outcome(false, "نتوانستم جست‌وجو را باز کنم.")
        }
    }

    private fun viewIntent(uri: Uri) =
        Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    companion object {
        const val NAME = "browser_search"
        private const val TAG = "BrowserSearchTool"
        private const val CHROME = "com.android.chrome"
    }
}
