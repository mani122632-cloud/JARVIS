package com.jarvis.assistant.command

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.telephony.SmsManager
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Sends a real SMS (`ToolCall("send_sms", {"name": ..., "text": ...})`). Contact via [ContactLookup] (background
 * thread), sending via [SmsManager] (SEND_SMS). Nothing is sent without the permission, without exactly one
 * matching contact, or without a text. Success is reported only after the radio layer confirms every part
 * (RESULT_OK); an error or a timeout is reported honestly. A command runs once: duplicates in flight are ignored.
 */
class SmsTool(context: Context) : JarvisTool {

    private val app = context.applicationContext
    private val lookup = ContactLookup(app)
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "jarvis-sms").apply { isDaemon = true } }
    private val busy = AtomicBoolean(false)
    private val seq = AtomicInteger(0)

    override val name: String = NAME

    override fun canHandle(action: JarvisAction): Boolean =
        action is JarvisAction.ToolCall && action.tool == NAME

    /** Registry path is never used for SMS (the executor routes it through [executeAsync]). */
    override fun execute(action: JarvisAction): JarvisActionExecutor.Outcome =
        JarvisActionExecutor.Outcome(false, FAILURE)

    /** [onResult] is invoked on the main thread, exactly once (except for an ignored duplicate). */
    fun executeAsync(action: JarvisAction, onResult: (JarvisActionExecutor.Outcome) -> Unit) {
        val args = (action as? JarvisAction.ToolCall)?.args.orEmpty()
        val query = args["name"]?.trim().orEmpty()
        val text = args["text"]?.trim().orEmpty()
        if (query.isEmpty()) { onResult(JarvisActionExecutor.Outcome(false, NO_NAME)); return }
        if (text.isEmpty()) {
            onResult(JarvisActionExecutor.Outcome(false, "متن پیام برای $query را نگفتید. دوباره بگویید: به $query بگو، و متن پیام."))
            return
        }
        if (app.checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            requestPermission()
            onResult(JarvisActionExecutor.Outcome(false, "اجازه ارسال پیامک به من داده نشده است. پیامی ارسال نشد؛ اجازه را بدهید و دوباره بگویید."))
            return
        }
        if (!busy.compareAndSet(false, true)) { Log.w(TAG, "SMS already in progress; duplicate ignored"); return }
        try {
            worker.execute {
                val found = try { lookup.find(query) } catch (e: RuntimeException) {
                    Log.w(TAG, "Lookup failed", e); ContactLookup.Result.Failed
                }
                main.post { afterLookup(found, text, onResult) }
            }
        } catch (e: RuntimeException) {
            busy.set(false)
            onResult(JarvisActionExecutor.Outcome(false, FAILURE))
        }
    }

    /** Main thread. */
    private fun afterLookup(r: ContactLookup.Result, text: String, onResult: (JarvisActionExecutor.Outcome) -> Unit) {
        when (r) {
            is ContactLookup.Result.Found -> send(r, text, onResult)
            is ContactLookup.Result.NotFound -> finish(onResult, false, "مخاطبی با این نام پیدا نکردم. پیامی ارسال نشد.")
            is ContactLookup.Result.NoPermission -> finish(onResult, false, "اجازه دسترسی به مخاطبین به من داده نشده است.")
            is ContactLookup.Result.Failed -> finish(onResult, false, FAILURE)
            is ContactLookup.Result.Ambiguous ->
                finish(onResult, false, "چند مخاطب با این نام دارم: ${r.names.joinToString("، ")}. پیامی ارسال نشد؛ نام کامل را بگویید.")
        }
    }

    private fun finish(onResult: (JarvisActionExecutor.Outcome) -> Unit, ok: Boolean, msg: String) {
        busy.set(false)
        onResult(JarvisActionExecutor.Outcome(ok, msg))
    }

    private fun send(c: ContactLookup.Result.Found, text: String, onResult: (JarvisActionExecutor.Outcome) -> Unit) {
        val sms: SmsManager? = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) app.getSystemService(SmsManager::class.java)
            else @Suppress("DEPRECATION") SmsManager.getDefault()
        } catch (e: RuntimeException) { null }
        if (sms == null) { finish(onResult, false, FAILURE); return }

        val parts: ArrayList<String> = try { sms.divideMessage(text) } catch (e: RuntimeException) { arrayListOf(text) }
        val action = "${app.packageName}.JARVIS_SMS_SENT_${seq.incrementAndGet()}"
        val total = parts.size
        val done = AtomicInteger(0)
        val settled = AtomicBoolean(false)
        var receiver: BroadcastReceiver? = null
        var timeout: Runnable? = null

        fun settle(ok: Boolean, msg: String) {
            if (!settled.compareAndSet(false, true)) return
            timeout?.let { main.removeCallbacks(it) }
            receiver?.let { try { app.unregisterReceiver(it) } catch (e: RuntimeException) { /* ignore */ } }
            finish(onResult, ok, msg)
        }

        receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (resultCode != Activity.RESULT_OK) {
                    Log.w(TAG, "SMS part failed, code=$resultCode")
                    settle(false, "نتوانستم پیامک را برای ${c.displayName} ارسال کنم.")
                } else if (done.incrementAndGet() >= total) {
                    settle(true, "پیامک برای ${c.displayName} ارسال شد.")
                }
            }
        }
        try {
            val filter = IntentFilter(action)
            if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            else app.registerReceiver(receiver, filter)
            val pi = PendingIntent.getBroadcast(
                app, 0, Intent(action).setPackage(app.packageName), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val t = Runnable { settle(false, "نتیجه ارسال پیامک مشخص نشد؛ نمی‌دانم ارسال شد یا نه.") }
            timeout = t
            main.postDelayed(t, RESULT_TIMEOUT_MS)
            if (total == 1) {
                sms.sendTextMessage(c.number, null, parts[0], pi, null)
            } else {
                sms.sendMultipartTextMessage(c.number, null, parts, ArrayList(parts.map { pi }), null)
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "SEND_SMS refused", e)
            settle(false, "اجازه ارسال پیامک به من داده نشده است.")
        } catch (e: RuntimeException) {
            Log.w(TAG, "SmsManager failed", e)
            settle(false, "نتوانستم پیامک را برای ${c.displayName} ارسال کنم.")
        }
    }

    private fun requestPermission() {
        try {
            app.startActivity(Intent(app, SmsPermissionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: RuntimeException) {
            Log.w(TAG, "Cannot open permission screen", e)
        }
    }

    companion object {
        const val NAME = "send_sms"
        const val RESULT_TIMEOUT_MS = 15_000L
        private const val TAG = "SmsTool"
        private const val NO_NAME = "نام مخاطب را متوجه نشدم."
        private const val FAILURE = "نتوانستم پیامک را ارسال کنم."
    }
}

/** Transparent screen that only asks for the SEND_SMS runtime permission, then closes. */
class SmsPermissionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED) { finish(); return }
        requestPermissions(arrayOf(Manifest.permission.SEND_SMS), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        finish()
    }
}
