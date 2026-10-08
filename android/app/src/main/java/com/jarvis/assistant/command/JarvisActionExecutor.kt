package com.jarvis.assistant.command

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.net.wifi.WifiManager
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.provider.Settings
import android.util.Log
import com.jarvis.assistant.speech.JarvisPhrases
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Runs a [JarvisAction] with real Android APIs. Never throws; main thread.
 *
 *  - Apps: PackageManager.getLaunchIntentForPackage (needs <queries>, see AppRegistry)
 *  - Settings / Wi-Fi / Bluetooth: Settings.ACTION_* screens
 *  - Home: ACTION_MAIN + CATEGORY_HOME
 *  - Back: no public API for another app's Back; see [goBack]
 *  - Flashlight: CameraManager.setTorchMode (no CAMERA permission needed for torch-only use)
 *  - Volume: AudioManager, STREAM_MUSIC (the stream the offline TTS also plays on)
 *  - Alarm / Timer: [AlarmTool] / [TimerTool] (AlarmClock.ACTION_SET_ALARM / ACTION_SET_TIMER with SKIP_UI,
 *    permission SET_ALARM): a real alarm / a really started timer, confirmed in the [Outcome] message
 *
 * Tools (Stage 46.5): capabilities that need more than one Intent live in a [JarvisTool] held by a
 * [ToolRegistry]; add one with [register]. The small built-ins below (apps, settings, torch, volume, home)
 * stay here and can move into tools one by one without changing the public API.
 *
 * Activities are started while the JARVIS overlay window is still visible (the conversation hides it
 * afterwards): an app holding SYSTEM_ALERT_WINDOW with a visible overlay is allowed to start activities
 * from the background.
 */
class JarvisActionExecutor(
    context: Context,
    tools: List<JarvisTool>? = null
) {

    private val app = context.applicationContext

    /**
     * @param success false means the action did not happen.
     * @param message what JARVIS says afterwards: the reason for a failure, or (for tools such as alarm and timer)
     * the spoken confirmation of a success. Null = nothing to say.
     */
    data class Outcome(val success: Boolean, val message: String? = null)

    private val appResolver = InstalledAppResolver(app)

    private val registry = ToolRegistry(tools ?: listOf(AlarmTool(app), TimerTool(app), CallTool(app), SmsTool(app), MediaTool(app), BrowserSearchTool(app)))

    /** Adds (or replaces, by name) a tool, e.g. a future call / SMS / contacts / search tool. */
    fun register(tool: JarvisTool) = registry.register(tool)

    private val cameraManager: CameraManager? = app.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
    private val torchState = ConcurrentHashMap<String, Boolean>()
    private val torchCallback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) { torchState[cameraId] = enabled }
        override fun onTorchModeUnavailable(cameraId: String) { torchState.remove(cameraId) }
    }
    private var torchCallbackRegistered = false

    init {
        appResolver.warmUp()
        // Lets TOGGLE know the real torch state ("چراغ قوه" without روشن/خاموش).
        try {
            cameraManager?.registerTorchCallback(torchCallback, Handler(Looper.getMainLooper()))
            torchCallbackRegistered = cameraManager != null
        } catch (e: RuntimeException) {
            Log.w(TAG, "Torch callback not registered", e)
        }
    }

    /** Call when the owner (the overlay service) is destroyed. */
    fun release() {
        if (!torchCallbackRegistered) return
        torchCallbackRegistered = false
        try { cameraManager?.unregisterTorchCallback(torchCallback) } catch (e: RuntimeException) { /* ignore */ }
    }

    fun execute(action: JarvisAction): Outcome = try {
        dispatch(action)
    } catch (e: RuntimeException) {            // SecurityException, IllegalState, background start refused, ...
        Log.e(TAG, "Action failed: $action", e)
        Outcome(false, GENERIC_FAILURE)
    }

    /** True when [action] must run through [executeAsync] (its tool does blocking work, e.g. a contact lookup). */
    fun runsAsync(action: JarvisAction): Boolean = registry.find(action).let { it is CallTool || it is SmsTool || it is MediaTool }

    /**
     * Runs [action] without blocking the main thread; [onResult] is invoked on the main thread, once. Tools that
     * block (call: contact lookup) work on a background thread, every other action runs like [execute].
     */
    fun executeAsync(action: JarvisAction, onResult: (Outcome) -> Unit) {
        val tool = try { registry.find(action) } catch (e: RuntimeException) { null }
        if (tool is CallTool || tool is SmsTool || tool is MediaTool) {
            try {
                when (tool) {
                    is SmsTool -> tool.executeAsync(action, onResult)
                    is MediaTool -> tool.executeAsync(action, onResult)
                    else -> (tool as CallTool).executeAsync(action, onResult)
                }
            } catch (e: RuntimeException) {
                Log.e(TAG, "Async action failed: $action", e)
                onResult(Outcome(false, GENERIC_FAILURE))
            }
        } else {
            onResult(execute(action))
        }
    }

    private fun dispatch(action: JarvisAction): Outcome = when (action) {
        is JarvisAction.OpenApp -> openApp(action)
        is JarvisAction.OpenAppByName ->
            RadioCommand.parse(action.query)?.let { (wifi, on) -> if (wifi) setWifi(on) else setBluetooth(on) }
                ?: openAppByName(action)
        JarvisAction.OpenSettings -> startActivity(Intent(Settings.ACTION_SETTINGS), "نتوانستم تنظیمات را باز کنم.")
        JarvisAction.OpenWifiSettings -> startActivity(Intent(Settings.ACTION_WIFI_SETTINGS), "نتوانستم تنظیمات وای‌فای را باز کنم.")
        JarvisAction.OpenBluetoothSettings -> startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS), "نتوانستم تنظیمات بلوتوث را باز کنم.")
        is JarvisAction.ToggleFlashlight -> flashlight(action.mode)
        is JarvisAction.SetVolume -> setVolume(action.change)
        is JarvisAction.CreateAlarm, is JarvisAction.ManageAlarm, is JarvisAction.CreateTimer, is JarvisAction.ToolCall ->
            registry.execute(action) ?: Outcome(false, "این کار هنوز پشتیبانی نمی‌شود.")
        // Never executed: the Brain turns it into a question first. Safe fallback if it ever arrives here.
        is JarvisAction.NeedsInfo -> Outcome(
            false,
            if (action.target == SlotTarget.ALARM) JarvisPhrases.ASK_ALARM_TIME else JarvisPhrases.ASK_TIMER_DURATION
        )
        JarvisAction.OpenTimerScreen -> startActivity(Intent(AlarmClock.ACTION_SHOW_TIMERS), "برنامه ساعت برای نمایش تایمر پیدا نشد.")
        JarvisAction.OpenAlarmScreen -> startActivity(Intent(AlarmClock.ACTION_SHOW_ALARMS), "برنامه ساعت برای نمایش آلارم پیدا نشد.")
        JarvisAction.GoHome -> startActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
            "نتوانستم به صفحه اصلی بروم."
        )
        JarvisAction.GoBack -> goBack()
        JarvisAction.DismissAssistant -> Outcome(true)
        is JarvisAction.Unknown -> Outcome(false, JarvisPhrases.NOT_UNDERSTOOD)
    }

    // ---- activities -----------------------------------------------------------------------------

    private fun startActivity(intent: Intent, failureMessage: String): Outcome =
        launchActivity(app, intent, failureMessage)

    private fun openApp(action: JarvisAction.OpenApp): Outcome {
        val pm = app.packageManager
        for (pkg in action.packageNames) {
            val intent = pm.getLaunchIntentForPackage(pkg) ?: continue
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            try {
                app.startActivity(intent)
                return Outcome(true)
            } catch (e: ActivityNotFoundException) {
                Log.w(TAG, "No launchable activity in $pkg", e)
            }
        }
        // Not installed (or not visible: add it to <queries>): report, never crash.
        return Outcome(false, "برنامه ${action.label} پیدا نشد.")
    }

    /** Any installed app by spoken name: one match opens, several are reported (never guessed), none is said honestly. */
    private fun openAppByName(action: JarvisAction.OpenAppByName): Outcome =
        when (val m = appResolver.resolve(action.query)) {
            is InstalledAppResolver.Match.Found -> try {
                app.startActivity(m.app.launchIntent())
                Outcome(true)
            } catch (e: ActivityNotFoundException) {
                Log.w(TAG, "No launchable activity in ${m.app.packageName}", e)
                Outcome(false, "نتوانستم برنامه ${m.app.label} را باز کنم.")
            }
            is InstalledAppResolver.Match.Ambiguous ->
                Outcome(false, "چند برنامه مشابه پیدا کردم: ${m.apps.joinToString("، ") { it.label }}. کدام را باز کنم؟ نام دقیق‌تر را بگو.")
            InstalledAppResolver.Match.NotFound ->
                Outcome(false, "برنامه‌ای با نام ${action.query} روی گوشی پیدا نکردم.")
        }

    /**
     * Android gives a normal app no way to press another app's Back button (it would need an
     * AccessibilityService with GLOBAL_ACTION_BACK, or INJECT_EVENTS / root / ADB — all out of scope).
     * The safest real behaviour: close the JARVIS overlay, which is not focusable or touchable, so the app
     * that was on screen is simply in front again. The conversation hides the overlay right after this.
     */
    private fun goBack(): Outcome {
        Log.i(TAG, "GoBack: closing the assistant overlay (Android offers no global Back to normal apps)")
        return Outcome(false, "اندروید به برنامه‌ها اجازه‌ی زدن دکمه‌ی بازگشت نمی‌دهد؛ فقط پنجره‌ی جارویس بسته شد.")
    }

    // ---- flashlight -----------------------------------------------------------------------------

    private fun findTorchCameraId(): String? {
        val cm = cameraManager ?: return null
        return try {
            val withFlash = cm.cameraIdList.filter {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
            withFlash.firstOrNull {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) ==
                    CameraCharacteristics.LENS_FACING_BACK
            } ?: withFlash.firstOrNull()
        } catch (e: CameraAccessException) {
            Log.w(TAG, "Cannot enumerate cameras", e)
            null
        }
    }

    private fun flashlight(mode: TorchMode): Outcome {
        val cm = cameraManager ?: return Outcome(false, "چراغ قوه روی این گوشی در دسترس نیست.")
        val id = findTorchCameraId() ?: return Outcome(false, "این گوشی چراغ قوه ندارد.")
        val target = when (mode) {
            TorchMode.ON -> true
            TorchMode.OFF -> false
            TorchMode.TOGGLE -> !(torchState[id] ?: false)
        }
        return try {
            cm.setTorchMode(id, target)
            Outcome(true)
        } catch (e: CameraAccessException) {
            Log.w(TAG, "setTorchMode failed", e)
            Outcome(false, "نتوانستم چراغ قوه را تغییر بدهم، شاید دوربین در حال استفاده است.")
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "setTorchMode: bad camera id", e)
            Outcome(false, "نتوانستم چراغ قوه را تغییر بدهم.")
        }
    }

    // ---- wifi / bluetooth (real toggle; honest fallback when Android forbids it) ----------------

    @Suppress("DEPRECATION")
    private fun setWifi(enable: Boolean): Outcome {
        val wm = app.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return Outcome(false, "این دستگاه وای‌فای ندارد.")
        val word = if (enable) "روشن" else "خاموش"
        if (wm.isWifiEnabled == enable) return Outcome(true, "وای‌فای از قبل $word است.")
        // Android 10+ forbids apps from toggling Wi-Fi (setWifiEnabled always returns false).
        val ok = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            try { wm.setWifiEnabled(enable) } catch (e: SecurityException) { Log.w(TAG, "setWifiEnabled denied", e); false }
        if (ok) return Outcome(true, "وای‌فای $word شد.")
        val intent = Intent(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Settings.Panel.ACTION_INTERNET_CONNECTIVITY
            else Settings.ACTION_WIFI_SETTINGS
        )
        startActivity(intent, "نتوانستم تنظیمات وای‌فای را باز کنم.")
        return Outcome(false, "اندروید اجازه تغییر مستقیم وای‌فای را نمی‌دهد. پنل را باز کردم، خودتان $word کنید.")
    }

    @Suppress("DEPRECATION", "MissingPermission")
    private fun setBluetooth(enable: Boolean): Outcome {
        val adapter = (app.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            ?: return Outcome(false, "این دستگاه بلوتوث ندارد.")
        val word = if (enable) "روشن" else "خاموش"
        if (adapter.isEnabled == enable) return Outcome(true, "بلوتوث از قبل $word است.")
        val hasPerm = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            app.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        // enable()/disable() work only up to Android 12 (and need BLUETOOTH_CONNECT on 12); newer returns false.
        val ok = hasPerm && try {
            if (enable) adapter.enable() else adapter.disable()
        } catch (e: SecurityException) { Log.w(TAG, "Bluetooth toggle denied", e); false }
        if (ok) return Outcome(true, "بلوتوث $word شد.")
        if (enable && hasPerm) {
            // System confirmation dialog: a real, user-approved switch-on.
            val r = startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), "نتوانستم بلوتوث را روشن کنم.")
            if (r.success) return Outcome(true, "برای روشن شدن بلوتوث، پنجرهٔ تأیید را بپذیرید.")
        }
        startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS), "نتوانستم تنظیمات بلوتوث را باز کنم.")
        return Outcome(false, "اندروید اجازه تغییر مستقیم بلوتوث را نمی‌دهد. تنظیمات را باز کردم، خودتان $word کنید.")
    }

    // ---- volume ---------------------------------------------------------------------------------

    private fun setVolume(change: VolumeChange): Outcome {
        val am = app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return Outcome(false, "نتوانستم صدا را تغییر بدهم.")
        val stream = AudioManager.STREAM_MUSIC
        val maxIdx = am.getStreamMaxVolume(stream)
        val minIdx = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) am.getStreamMinVolume(stream) else 0
        val current = am.getStreamVolume(stream)
        val step = max(1, (maxIdx * VOLUME_STEP_FRACTION).roundToInt())      // "زیاد/کم کن" = about 20%
        val target = when (change) {
            VolumeChange.Up -> current + step
            VolumeChange.Down -> current - step
            is VolumeChange.Percent -> minIdx + ((maxIdx - minIdx) * change.percent / 100f).roundToInt()
        }.coerceIn(minIdx, maxIdx)
        am.setStreamVolume(stream, target, AudioManager.FLAG_SHOW_UI)
        return Outcome(true)
    }

    private companion object {
        const val TAG = "JarvisActions"
        const val GENERIC_FAILURE = "نتوانستم این کار را انجام بدهم."
        const val VOLUME_STEP_FRACTION = 0.2f
    }
}

/** Reserved [JarvisAction.OpenAppByName] queries that mean "switch Wi-Fi / Bluetooth on / off" (parser -> executor). */
object RadioCommand {
    private const val PREFIX = "__jarvis_radio_"
    fun query(wifi: Boolean, on: Boolean) = PREFIX + (if (wifi) "wifi" else "bt") + (if (on) "_on__" else "_off__")
    /** (isWifi, enable) or null when [q] is an ordinary app name. */
    fun parse(q: String): Pair<Boolean, Boolean>? = when (q) {
        query(true, true) -> true to true
        query(true, false) -> true to false
        query(false, true) -> false to true
        query(false, false) -> false to false
        else -> null
    }
}
