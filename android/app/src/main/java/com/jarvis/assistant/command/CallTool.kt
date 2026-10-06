package com.jarvis.assistant.command

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Places a real phone call to a contact (`ToolCall("call_contact", {"name": ...})`). Fully offline: the lookup is
 * [ContactLookup] (ContactsContract) on a background thread, the call itself ACTION_CALL (CALL_PHONE granted) or,
 * without that permission, the dialer opened with the number (ACTION_DIAL). A command is executed once: while one
 * is in flight, a duplicate is ignored.
 */
class CallTool(context: Context) : JarvisTool {

    private val app = context.applicationContext
    private val lookup = ContactLookup(app)
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "jarvis-contact-lookup").apply { isDaemon = true } }
    private val busy = AtomicBoolean(false)

    override val name: String = NAME

    override fun canHandle(action: JarvisAction): Boolean =
        action is JarvisAction.ToolCall && action.tool == NAME

    /** Synchronous fallback (registry path). Prefer [executeAsync]; this blocks for the lookup. */
    override fun execute(action: JarvisAction): JarvisActionExecutor.Outcome {
        val query = queryOf(action) ?: return JarvisActionExecutor.Outcome(false, NO_NAME)
        if (!busy.compareAndSet(false, true)) return JarvisActionExecutor.Outcome(true)
        return try { place(lookup.find(query)) } finally { busy.set(false) }
    }

    /** Lookup on a background thread; [onResult] is invoked on the main thread, exactly once. */
    fun executeAsync(action: JarvisAction, onResult: (JarvisActionExecutor.Outcome) -> Unit) {
        val query = queryOf(action)
        if (query == null) { onResult(JarvisActionExecutor.Outcome(false, NO_NAME)); return }
        if (!busy.compareAndSet(false, true)) {
            Log.w(TAG, "Call already in progress; duplicate ignored")
            return
        }
        try {
            worker.execute {
                val found = try { lookup.find(query) } catch (e: RuntimeException) {
                    Log.w(TAG, "Lookup failed", e); ContactLookup.Result.Failed
                }
                main.post {
                    val outcome = try { place(found) } catch (e: RuntimeException) {
                        Log.e(TAG, "Call failed", e)
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

    private fun queryOf(action: JarvisAction): String? {
        val args = (action as? JarvisAction.ToolCall)?.args ?: return null
        return KEYS.firstNotNullOfOrNull { args[it]?.trim()?.takeIf { v -> v.isNotEmpty() } }
    }

    /** Main thread. */
    private fun place(r: ContactLookup.Result): JarvisActionExecutor.Outcome = when (r) {
        is ContactLookup.Result.NotFound -> JarvisActionExecutor.Outcome(false, "مخاطبی با این نام پیدا نکردم.")
        is ContactLookup.Result.NoPermission -> JarvisActionExecutor.Outcome(false, "اجازه دسترسی به مخاطبین به من داده نشده است.")
        is ContactLookup.Result.Failed -> JarvisActionExecutor.Outcome(false, FAILURE)
        is ContactLookup.Result.Ambiguous ->
            JarvisActionExecutor.Outcome(false, "چند مخاطب با این نام دارم: ${r.names.joinToString("، ")}. نام کامل را بگویید.")
        is ContactLookup.Result.Found -> dial(r)
    }

    private fun dial(c: ContactLookup.Result.Found): JarvisActionExecutor.Outcome {
        val canCall = app.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        val intent = Intent(if (canCall) Intent.ACTION_CALL else Intent.ACTION_DIAL, Uri.fromParts("tel", c.number, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            app.startActivity(intent)
            JarvisActionExecutor.Outcome(true, if (canCall) "در حال تماس با ${c.displayName}." else "شماره ${c.displayName} را باز کردم.")
        } catch (e: SecurityException) {
            Log.w(TAG, "CALL_PHONE refused", e)
            JarvisActionExecutor.Outcome(false, "اجازه تماس به من داده نشده است.")
        } catch (e: RuntimeException) {
            Log.w(TAG, "Cannot start call", e)
            JarvisActionExecutor.Outcome(false, FAILURE)
        }
    }

    companion object {
        const val NAME = "call_contact"
        private const val TAG = "CallTool"
        private val KEYS = listOf("name", "contact", "contact_name", "query", "target")
        private const val NO_NAME = "نام مخاطب را متوجه نشدم."
        private const val FAILURE = "نتوانستم تماس را برقرار کنم."
    }
}
