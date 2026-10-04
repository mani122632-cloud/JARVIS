package com.jarvis.assistant.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import com.jarvis.assistant.activation.JarvisActivationController
import com.jarvis.assistant.core.JarvisCoreView
import com.jarvis.assistant.speech.AndroidTtsSpeechController
import com.jarvis.assistant.speech.JarvisSpeechController

/**
 * Hosts the JARVIS assistant UI as a system overlay, independent of any Activity.
 *
 *   trigger -> JarvisOverlayService.activate(...) -> JarvisActivationController.activate()
 *           -> showOverlay() (Arc Reactor fades in over the current app) -> LISTENING
 *           -> TTS "بله ارباب." (SPEAKING) -> LISTENING -> [future command listener]
 *           -> deactivate(): READY -> hideOverlay() (fade out, window removed, service stops)
 *
 * Runs as a foreground service (specialUse for now). When the wake-word engine arrives, switch the
 * manifest type to microphone (+ RECORD_AUDIO) and set [STOP_SERVICE_AFTER_HIDE] to false.
 * No microphone is used in this stage.
 */
class JarvisOverlayService : Service(), JarvisActivationController.OverlayPresenter {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var window: JarvisOverlayWindow
    private lateinit var speech: JarvisSpeechController
    private lateinit var activation: JarvisActivationController
    private var lastStartId = 0

    private val autoDismiss = Runnable { activation.deactivate() }
    private var pendingActivate: Runnable? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        window = JarvisOverlayWindow(this)
        speech = AndroidTtsSpeechController(this)
        activation = JarvisActivationController(speech, presenter = this)
        activation.listener = object : JarvisActivationController.Listener {
            override fun onReadyForCommand() {
                // Placeholder until the command listener exists: it should take over here and call
                // endInteraction() when finished. Without it the overlay would never leave.
                main.removeCallbacks(autoDismiss)
                main.postDelayed(autoDismiss, AUTO_DISMISS_MS)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        if (!enterForeground()) { stopSelf(startId); return START_NOT_STICKY }

        when (intent?.action) {
            ACTION_ACTIVATE -> {
                if (!hasOverlayPermission(this)) {
                    Log.w(TAG, "Overlay permission missing; ignoring activation")
                    finishIfIdle()
                } else {
                    val source = parseSource(intent.getStringExtra(EXTRA_SOURCE))
                    val delay = intent.getLongExtra(EXTRA_DELAY_MS, 0L)
                    cancelPendingActivate()
                    if (delay > 0L) {
                        val r = Runnable { pendingActivate = null; runActivation(source) }
                        pendingActivate = r
                        main.postDelayed(r, delay)
                    } else {
                        runActivation(source)
                    }
                }
            }
            ACTION_HIDE -> endInteraction()
            else -> finishIfIdle()
        }
        return START_NOT_STICKY
    }

    // ---- OverlayPresenter (called by JarvisActivationController) --------------------------------

    /** Idempotent: returns the existing overlay's core if already shown. */
    override fun showOverlay(): JarvisCoreView? {
        main.removeCallbacks(autoDismiss)
        val core = window.show()
        isOverlayVisible = window.isAttached
        return core
    }

    /** Idempotent and safe when nothing is shown. Fades out, removes the window, then stops the service. */
    override fun hideOverlay() {
        main.removeCallbacks(autoDismiss)
        window.hide {
            isOverlayVisible = false
            finishIfIdle()
        }
    }

    // ---------------------------------------------------------------------------------------------

    /** End the current interaction (READY, then fade out). For the future command listener. */
    fun endInteraction() {
        cancelPendingActivate()
        activation.deactivate()
    }

    private fun runActivation(source: JarvisActivationController.Source) {
        activation.activate(source)
        if (!window.isAttached) finishIfIdle()   // window could not be created: don't linger
    }

    private fun cancelPendingActivate() {
        pendingActivate?.let { main.removeCallbacks(it) }
        pendingActivate = null
    }

    private fun finishIfIdle() {
        if (STOP_SERVICE_AFTER_HIDE && !window.isAttached && pendingActivate == null) {
            stopSelf(lastStartId)    // ignored if a newer start command arrived meanwhile
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        window.onConfigurationChanged()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        cancelPendingActivate()
        activation.unbind()          // cancels speech callbacks/timeouts
        window.remove()              // never leak the WindowManager view
        speech.shutdown()
        isOverlayVisible = false
        instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---- foreground notification ----------------------------------------------------------------

    private fun enterForeground(): Boolean {
        return try {
            startForeground(NOTIFICATION_ID, buildNotification())
            true
        } catch (e: RuntimeException) {   // e.g. foreground start not allowed from background (API 31+)
            Log.e(TAG, "startForeground failed", e)
            false
        }
    }

    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "JARVIS", NotificationManager.IMPORTANCE_LOW)
            )
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        val icon = if (applicationInfo.icon != 0) applicationInfo.icon else android.R.drawable.ic_dialog_info
        return builder
            .setSmallIcon(icon)
            .setContentTitle("JARVIS")
            .setContentText("دستیار فعال است")
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        private const val TAG = "JarvisOverlayService"
        private const val CHANNEL_ID = "jarvis_overlay"
        private const val NOTIFICATION_ID = 4301

        const val ACTION_ACTIVATE = "com.jarvis.assistant.overlay.ACTION_ACTIVATE"
        const val ACTION_HIDE = "com.jarvis.assistant.overlay.ACTION_HIDE"
        const val EXTRA_SOURCE = "source"
        const val EXTRA_DELAY_MS = "delay_ms"

        /** Temporary: how long the overlay stays after "بله ارباب." until a command listener exists. */
        private const val AUTO_DISMISS_MS = 8000L

        /** Set to false once a wake-word engine keeps this service alive permanently. */
        private const val STOP_SERVICE_AFTER_HIDE = true

        @Volatile private var instance: JarvisOverlayService? = null

        /** True while the overlay window is on screen (including its fade-out). */
        @Volatile var isOverlayVisible: Boolean = false
            private set

        // ---- permission ----

        fun hasOverlayPermission(context: Context): Boolean = Settings.canDrawOverlays(context)

        fun createOverlayPermissionIntent(context: Context): Intent =
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        /** Opens the system "Display over other apps" screen for this app. Call only on a user action. */
        fun openOverlayPermissionSettings(context: Context): Boolean = try {
            context.startActivity(createOverlayPermissionIntent(context))
            true
        } catch (e: ActivityNotFoundException) {
            Log.e(TAG, "Overlay permission screen not available", e)
            false
        }

        // ---- public triggers ----

        /**
         * Future entry point for the wake-word engine / any trigger.
         * @return false (and logs) if overlay permission is missing or the service could not be started.
         */
        fun activate(
            context: Context,
            source: JarvisActivationController.Source = JarvisActivationController.Source.MANUAL,
            delayMs: Long = 0L
        ): Boolean {
            val app = context.applicationContext
            if (!hasOverlayPermission(app)) {
                Log.w(TAG, "Overlay permission missing — call openOverlayPermissionSettings() from a user action")
                return false
            }
            val intent = Intent(app, JarvisOverlayService::class.java)
                .setAction(ACTION_ACTIVATE)
                .putExtra(EXTRA_SOURCE, source.name)
                .putExtra(EXTRA_DELAY_MS, delayMs)
            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) app.startForegroundService(intent)
                else app.startService(intent)
                true
            } catch (e: RuntimeException) {
                Log.e(TAG, "Could not start overlay service", e)
                false
            }
        }

        /** TEMPORARY developer trigger: full flow (overlay -> "بله ارباب." -> listening -> auto hide). */
        fun show(context: Context, delayMs: Long = 0L): Boolean =
            activate(context, JarvisActivationController.Source.MANUAL, delayMs)

        /** Ends the interaction and fades the overlay out. Safe when nothing is shown. */
        fun hide(@Suppress("UNUSED_PARAMETER") context: Context) {
            val svc = instance ?: return
            svc.main.post { svc.endInteraction() }
        }

        private fun parseSource(name: String?): JarvisActivationController.Source =
            try {
                if (name == null) JarvisActivationController.Source.MANUAL
                else JarvisActivationController.Source.valueOf(name)
            } catch (e: IllegalArgumentException) {
                JarvisActivationController.Source.MANUAL
            }
    }
}
