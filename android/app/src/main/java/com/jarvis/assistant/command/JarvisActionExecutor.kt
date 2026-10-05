package com.jarvis.assistant.command

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
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
 *  - Alarm / Timer: AlarmClock.ACTION_SET_ALARM / ACTION_SET_TIMER (permission SET_ALARM)
 *
 * Activities are started while the JARVIS overlay window is still visible (the conversation hides it
 * afterwards): an app holding SYSTEM_ALERT_WINDOW with a visible overlay is allowed to start activities
 * from the background.
 */
class JarvisActionExecutor(context: Context) {

    private val app = context.applicationContext

    /** @param success false means the action did not happen; [message] is what JARVIS should say. */
    data class Outcome(val success: Boolean, val message: String? = null)

    private val cameraManager: CameraManager? = app.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
    private val torchState = ConcurrentHashMap<String, Boolean>()
    private val torchCallback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) { torchState[cameraId] = enabled }
        override fun onTorchModeUnavailable(cameraId: String) { torchState.remove(cameraId) }
    }
    private var torchCallbackRegistered = false

    init {
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

    private fun dispatch(action: JarvisAction): Outcome = when (action) {
        is JarvisAction.OpenApp -> openApp(action)
        JarvisAction.OpenSettings -> startActivity(Intent(Settings.ACTION_SETTINGS), "نتوانستم تنظیمات را باز کنم.")
        JarvisAction.OpenWifiSettings -> startActivity(Intent(Settings.ACTION_WIFI_SETTINGS), "نتوانستم تنظیمات وای‌فای را باز کنم.")
        JarvisAction.OpenBluetoothSettings -> startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS), "نتوانستم تنظیمات بلوتوث را باز کنم.")
        is JarvisAction.ToggleFlashlight -> flashlight(action.mode)
        is JarvisAction.SetVolume -> setVolume(action.change)
        is JarvisAction.CreateAlarm -> createAlarm(action)
        is JarvisAction.CreateTimer -> createTimer(action)
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

    private fun startActivity(intent: Intent, failureMessage: String): Outcome {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            app.startActivity(intent)
            Outcome(true)
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "No activity for $intent", e)
            Outcome(false, failureMessage)
        }
    }

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

    /**
     * Android gives a normal app no way to press another app's Back button (it would need an
     * AccessibilityService with GLOBAL_ACTION_BACK, or INJECT_EVENTS / root / ADB — all out of scope).
     * The safest real behaviour: close the JARVIS overlay, which is not focusable or touchable, so the app
     * that was on screen is simply in front again. The conversation hides the overlay right after this.
     */
    private fun goBack(): Outcome {
        Log.i(TAG, "GoBack: closing the assistant overlay (Android offers no global Back to normal apps)")
        return Outcome(true)
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

    // ---- alarm / timer --------------------------------------------------------------------------

    private fun createAlarm(a: JarvisAction.CreateAlarm): Outcome {
        if (a.hour !in 0..23 || a.minute !in 0..59) return Outcome(false, NOT_UNDERSTOOD_TIME)
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, a.hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, a.minute)
            .putExtra(AlarmClock.EXTRA_MESSAGE, ALARM_LABEL)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        return startActivity(intent, "برنامه ساعت برای تنظیم آلارم پیدا نشد.")
    }

    private fun createTimer(t: JarvisAction.CreateTimer): Outcome {
        if (t.seconds !in 1..MAX_TIMER_SECONDS) return Outcome(false, NOT_UNDERSTOOD_TIME)
        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, t.seconds)
            .putExtra(AlarmClock.EXTRA_MESSAGE, ALARM_LABEL)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        return startActivity(intent, "برنامه ساعت برای تنظیم تایمر پیدا نشد.")
    }

    private companion object {
        const val TAG = "JarvisActions"
        const val GENERIC_FAILURE = "نتوانستم این کار را انجام بدهم."
        const val NOT_UNDERSTOOD_TIME = "زمان را درست متوجه نشدم."
        const val ALARM_LABEL = "JARVIS"
        const val MAX_TIMER_SECONDS = 86_400
        const val VOLUME_STEP_FRACTION = 0.2f
    }
}
