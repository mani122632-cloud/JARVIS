package com.jarvis.assistant.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import com.jarvis.assistant.activation.JarvisActivationController
import com.jarvis.assistant.brain.DefaultJarvisBrain
import com.jarvis.assistant.command.JarvisActionExecutor
import com.jarvis.assistant.command.JarvisCommandProcessor
import com.jarvis.assistant.conversation.JarvisConversationController
import com.jarvis.assistant.memory.SharedPreferencesJarvisMemory
import com.jarvis.assistant.speech.SpeechInputFactory
import com.jarvis.assistant.core.JarvisCoreView
import com.jarvis.assistant.speech.JarvisSpeechController
import com.jarvis.assistant.speech.tts.OfflinePersianTts
import com.jarvis.assistant.wakeword.AssistantFlow
import com.jarvis.assistant.wakeword.VoskWakeWordEngine
import com.jarvis.assistant.wakeword.WakeStatus
import com.jarvis.assistant.wakeword.WakeWordState

/**
 * Hosts the JARVIS assistant UI as a system overlay, independent of any Activity.
 *
 *   trigger -> JarvisOverlayService.activate(...) -> JarvisActivationController.activate()
 *           -> showOverlay() (Arc Reactor fades in over the current app) -> LISTENING
 *           -> TTS "بله ارباب." (SPEAKING) -> LISTENING -> [future command listener]
 *           -> deactivate(): READY -> hideOverlay() (fade out, window removed, service stops)
 *
 * Stage 44: while the wake word is enabled this service also owns the offline Vosk detector
 * («هی جارویس»), so it stays alive after the overlay hides and runs as a microphone foreground service:
 *
 *   IDLE -> WAKE_WORD_LISTENING -> (wake word) -> ACTIVATING -> "بله ارباب." -> LISTENING
 *        -> overlay hides -> WAKE_WORD_LISTENING
 *
 * The microphone is released while the overlay / TTS / command stage is active and re-acquired after.
 * Without the wake word the service behaves exactly as in Stage 43 (specialUse, stops after hide).
 */
class JarvisOverlayService : Service(), JarvisActivationController.OverlayPresenter {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var window: JarvisOverlayWindow
    private lateinit var speech: JarvisSpeechController
    private lateinit var activation: JarvisActivationController
    private lateinit var conversation: JarvisConversationController
    private lateinit var executor: JarvisActionExecutor
    private var lastStartId = 0

    private lateinit var wake: VoskWakeWordEngine
    private var wakeEnabled = false
    private val resumeWakeRunnable = Runnable { resumeWake() }

    private var pendingActivate: Runnable? = null

    /**
     * True from the moment a wake word is accepted until the interaction has fully ended (overlay hidden).
     * While set, no wake word is accepted and Vosk is never started, so there can be neither a second
     * activation nor a second microphone session.
     */
    private var interactionActive = false

    override fun onCreate() {
        super.onCreate()
        instance = this
        window = JarvisOverlayWindow(this)
        speech = OfflinePersianTts(this).also { it.initialize() }   // offline Persian voice, loaded once
        activation = JarvisActivationController(speech, presenter = this)
        wake = VoskWakeWordEngine(this, object : VoskWakeWordEngine.Callback {
            override fun onWakeWord() = onWakeWordDetected()
            override fun onStatus(status: WakeStatus) = onWakeStatus(status)
        })
        wake.preload()      // load the Vosk model in the background now (no microphone); start() reuses it
        executor = JarvisActionExecutor(this)
        conversation = JarvisConversationController(
            tts = speech,
            commandSpeech = SpeechInputFactory.create(this),
            brain = DefaultJarvisBrain(JarvisCommandProcessor(), SharedPreferencesJarvisMemory(this)),
            executor = executor,
            core = { window.currentCore },
            callback = object : JarvisConversationController.Callback {
                override fun onStateChanged(state: JarvisConversationController.State) {
                    if (!wakeEnabled) return
                    WakeWordState.update(flow = when (state) {
                        JarvisConversationController.State.COMMAND_LISTENING -> AssistantFlow.COMMAND_LISTENING
                        JarvisConversationController.State.COMMAND_PROCESSING -> AssistantFlow.COMMAND_PROCESSING
                        JarvisConversationController.State.RESPONDING -> AssistantFlow.RESPONDING
                        JarvisConversationController.State.IDLE -> return
                    })
                }
                override fun onConversationFinished() = endInteraction()   // hides overlay, then Vosk resumes
            }
        )
        activation.listener = object : JarvisActivationController.Listener {
            override fun onReadyForCommand() {
                // "بله ارباب." is done. Vosk is suspended (runActivation), so the command recognizer
                // is the only microphone user from here until the overlay hides.
                try {
                    conversation.begin()
                } catch (t: Throwable) {
                    Log.e(TAG, "Conversation could not start; returning to wake word", t)
                    endInteraction()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        when (intent?.action) {
            ACTION_WAKE_START -> wakeEnabled = true
            ACTION_WAKE_STOP -> wakeEnabled = false
        }
        if (wakeEnabled && !hasMicPermission()) {
            wakeEnabled = false
            WakeWordState.update(status = WakeStatus.NO_PERMISSION, flow = AssistantFlow.IDLE)
        }
        if (!enterForeground()) {
            wakeEnabled = false
            wake.stop()
            WakeWordState.update(status = WakeStatus.ERROR, flow = AssistantFlow.IDLE)
            stopSelf(startId)
            return START_NOT_STICKY
        }

        when (intent?.action) {
            ACTION_WAKE_START -> {
                if (wakeEnabled) {
                    if (!interactionActive) WakeWordState.update(status = WakeStatus.LOADING_MODEL, flow = AssistantFlow.WAKE_WORD_LISTENING)
                    resumeWake()
                } else {
                    finishIfIdle()
                }
            }
            ACTION_WAKE_STOP -> {
                suspendWake()
                if (!window.isAttached) WakeWordState.update(status = WakeStatus.OFF, flow = AssistantFlow.IDLE)
                finishIfIdle()
            }
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
        val core = window.show()
        isOverlayVisible = window.isAttached
        return core
    }

    /** Idempotent and safe when nothing is shown. Fades out, removes the window, then stops the service. */
    override fun hideOverlay() {
        window.hide {
            // Runs once the window is really gone. (If a new show() interrupts the fade-out instead, the
            // interaction that re-showed it owns the state and resumes Vosk when IT ends.)
            isOverlayVisible = false
            interactionActive = false
            if (wakeEnabled) WakeWordState.update(flow = AssistantFlow.WAKE_WORD_LISTENING)
            else if (WakeWordState.isActive()) WakeWordState.update(status = WakeStatus.OFF, flow = AssistantFlow.IDLE)
            finishIfIdle()
            scheduleWakeResume()    // interaction is over: listen for the wake word again
        }
    }

    // ---------------------------------------------------------------------------------------------

    /** End the current interaction (READY, then fade out). For the future command listener. */
    fun endInteraction() {
        cancelPendingActivate()
        conversation.cancel()
        activation.deactivate()
    }

    private fun runActivation(source: JarvisActivationController.Source) {
        if (activation.phase != JarvisActivationController.Phase.IDLE || conversation.state != JarvisConversationController.State.IDLE) {
            Log.w(TAG, "Activation ignored: an interaction is already running")   // never restart a live session
            return
        }
        interactionActive = true
        suspendWake()                            // free the mic while the overlay / TTS / commands run
        if (wakeEnabled) WakeWordState.update(flow = AssistantFlow.ACTIVATING)
        activation.activate(source)
        if (!window.isAttached) {                // window could not be created: don't linger
            interactionActive = false
            if (wakeEnabled) WakeWordState.update(flow = AssistantFlow.WAKE_WORD_LISTENING)
            finishIfIdle()
            scheduleWakeResume()
        }
    }

    // ---- wake word ------------------------------------------------------------------------------

    private fun hasMicPermission(): Boolean =
        checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Engine already stopped itself (mic released) before this is called. Main thread. */
    private fun onWakeWordDetected() {
        if (!wakeEnabled) return
        val busy = interactionActive || activation.phase != JarvisActivationController.Phase.IDLE ||
            window.isAttached || pendingActivate != null
        if (busy) {
            Log.w(TAG, "Wake word ignored: interaction already in progress")
            return                                   // the running interaction resumes Vosk when it ends
        }
        if (!hasOverlayPermission(this)) {
            Log.w(TAG, "Wake word heard but overlay permission is missing")
            scheduleWakeResume()
            return
        }
        Log.i(TAG, "Wake word detected")
        interactionActive = true
        runActivation(JarvisActivationController.Source.WAKE_WORD)
    }

    private fun onWakeStatus(status: WakeStatus) {
        when (status) {
            WakeStatus.LOADING_MODEL, WakeStatus.LISTENING ->
                if (wakeEnabled) WakeWordState.update(status = status)
            WakeStatus.NO_PERMISSION, WakeStatus.MODEL_MISSING, WakeStatus.PHRASE_UNSUPPORTED, WakeStatus.ERROR -> {
                // The detector cannot run: leave wake word mode instead of holding a useless mic service.
                wakeEnabled = false
                wake.stop()
                WakeWordState.update(status = status, flow = AssistantFlow.IDLE)
                finishIfIdle()
            }
            else -> Unit
        }
    }

    private fun suspendWake() {
        main.removeCallbacks(resumeWakeRunnable)
        wake.stop()
        if (wakeEnabled) WakeWordState.update(status = WakeStatus.SUSPENDED)
    }

    private fun scheduleWakeResume() {
        main.removeCallbacks(resumeWakeRunnable)
        if (wakeEnabled) main.postDelayed(resumeWakeRunnable, RESUME_WAKE_DELAY_MS)
    }

    private fun resumeWake() {
        if (!wakeEnabled || interactionActive || window.isAttached || pendingActivate != null) return
        if (activation.phase != JarvisActivationController.Phase.IDLE) return
        if (!hasMicPermission()) { onWakeStatus(WakeStatus.NO_PERMISSION); return }
        WakeWordState.update(flow = AssistantFlow.WAKE_WORD_LISTENING)
        wake.start()                              // idempotent: never a second listener
    }

    private fun cancelPendingActivate() {
        pendingActivate?.let { main.removeCallbacks(it) }
        pendingActivate = null
    }

    private fun finishIfIdle() {
        if (!wakeEnabled && !window.isAttached && pendingActivate == null) {
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
        wakeEnabled = false
        interactionActive = false
        wake.release()               // releases the microphone and the Vosk model
        if (WakeWordState.isActive()) WakeWordState.update(status = WakeStatus.OFF)
        WakeWordState.update(flow = AssistantFlow.IDLE)
        conversation.release()       // stops the command recognizer, frees the microphone
        executor.release()           // unregisters the torch-state callback
        activation.unbind()          // cancels speech callbacks/timeouts
        window.remove()              // never leak the WindowManager view
        speech.shutdown()
        isOverlayVisible = false
        instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---- foreground notification ----------------------------------------------------------------

    /**
     * Android 14+ requires the type passed here to match what the service really does, and a
     * microphone type needs RECORD_AUDIO. So: microphone while the wake word is on, specialUse otherwise.
     * If the microphone type is refused we fall back to specialUse and leave wake word mode.
     */
    private fun enterForeground(): Boolean {
        if (tryForeground(microphone = wakeEnabled)) return true
        if (!wakeEnabled) return false
        Log.w(TAG, "Microphone foreground type refused; wake word disabled")
        wakeEnabled = false
        WakeWordState.update(status = WakeStatus.ERROR, flow = AssistantFlow.IDLE)
        return tryForeground(microphone = false)
    }

    private fun tryForeground(microphone: Boolean): Boolean {
        return try {
            val notification = buildNotification()
            when {
                Build.VERSION.SDK_INT >= 34 -> {
                    val type = if (microphone) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                    else ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                    startForeground(NOTIFICATION_ID, notification, type)
                }
                Build.VERSION.SDK_INT >= 29 ->
                    startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST)
                else -> startForeground(NOTIFICATION_ID, notification)
            }
            true
        } catch (e: RuntimeException) {   // e.g. foreground start not allowed from background (API 31+)
            Log.e(TAG, "startForeground failed (microphone=$microphone)", e)
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
            .setContentText(if (wakeEnabled) "منتظر «هی جارویس»" else "دستیار فعال است")
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
        const val ACTION_WAKE_START = "com.jarvis.assistant.overlay.ACTION_WAKE_START"
        const val ACTION_WAKE_STOP = "com.jarvis.assistant.overlay.ACTION_WAKE_STOP"
        const val EXTRA_SOURCE = "source"
        const val EXTRA_DELAY_MS = "delay_ms"


        /** Pause before the microphone is reopened after an interaction (avoids catching TTS tail audio). */
        private const val RESUME_WAKE_DELAY_MS = 600L

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

        /**
         * Starts the wake word service. Call only from a user action while the app is visible
         * (Android 14+ does not allow starting a microphone foreground service from the background).
         * @return false if RECORD_AUDIO / overlay permission is missing or the service could not start.
         */
        fun startWakeWord(context: Context): Boolean {
            val app = context.applicationContext
            if (app.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return false
            if (!hasOverlayPermission(app)) return false
            return sendAction(app, ACTION_WAKE_START)
        }

        /** Turns the wake word off and releases the microphone. Safe when the service is not running. */
        fun stopWakeWord(context: Context) {
            val app = context.applicationContext
            if (instance == null) { WakeWordState.update(status = WakeStatus.OFF, flow = AssistantFlow.IDLE); return }
            sendAction(app, ACTION_WAKE_STOP)
        }

        private fun sendAction(app: Context, action: String): Boolean {
            val intent = Intent(app, JarvisOverlayService::class.java).setAction(action)
            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) app.startForegroundService(intent)
                else app.startService(intent)
                true
            } catch (e: RuntimeException) {
                Log.e(TAG, "Could not start overlay service ($action)", e)
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
