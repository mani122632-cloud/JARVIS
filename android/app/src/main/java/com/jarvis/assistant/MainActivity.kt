package com.jarvis.assistant

import android.Manifest
import android.app.Activity
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.jarvis.assistant.overlay.JarvisOverlayService
import com.jarvis.assistant.wakeword.WakeStatus
import com.jarvis.assistant.wakeword.WakeWordState

/**
 * Minimal JARVIS setup screen. It is NOT the assistant interface: the assistant lives in
 * JarvisOverlayService / JarvisOverlayWindow / JarvisActivationController.
 *
 * This screen only shows the "display over other apps" status and offers a user action to grant it.
 * It never activates JARVIS, owns no TTS, and does not host the Arc Reactor.
 * Built in code (no layout XML, no extra dependencies) so it cannot clash with existing resources.
 */
class MainActivity : Activity() {

    private lateinit var statusRow: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var grantButton: TextView
    private lateinit var voiceButton: TextView
    private lateinit var voiceStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        run {
            window.statusBarColor = BG
            window.navigationBarColor = BG
        }
        setContentView(buildUi())
    }

    override fun onStart() {
        super.onStart()
        WakeWordState.listener = Runnable { refreshVoiceState() }
    }

    override fun onStop() {
        WakeWordState.listener = null
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionState()   // also runs when returning from the system settings screen
        refreshVoiceState()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_MIC && !hasMic()) {
            Toast.makeText(this, "اجازه میکروفون داده نشد. از تنظیمات برنامه فعال کنید", Toast.LENGTH_LONG).show()
        }
        refreshVoiceState()        // never starts anything by itself: the user taps again to enable
    }

    private fun hasMic(): Boolean =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private val isDebuggable: Boolean
        get() = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    private fun refreshVoiceState() {
        if (!::voiceButton.isInitialized) return
        val status = WakeWordState.status
        val active = WakeWordState.isActive(status)
        voiceButton.text = when {
            !hasMic() -> "فعال‌سازی فرمان صوتی"
            active -> "خاموش کردن فرمان صوتی"
            else -> "روشن کردن فرمان صوتی"
        }
        val msg = when (status) {
            WakeStatus.LOADING_MODEL -> "در حال آماده‌سازی مدل صوتی…"
            WakeStatus.LISTENING -> "منتظر «هی جارویس» هستم"
            WakeStatus.SUSPENDED -> "در حال پاسخ‌دهی"
            WakeStatus.MODEL_MISSING -> "مدل صوتی فارسی داخل برنامه نیست"
            WakeStatus.NO_PERMISSION -> "اجازه میکروفون لازم است"
            WakeStatus.ERROR -> "میکروفون یا مدل صوتی در دسترس نیست"
            WakeStatus.OFF -> ""
        }
        voiceStatus.text = msg
        voiceStatus.visibility = if (msg.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun onVoiceClicked() {
        when {
            !hasMic() -> requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)   // user tap only
            WakeWordState.isActive() -> JarvisOverlayService.stopWakeWord(this)
            !JarvisOverlayService.hasOverlayPermission(this) ->
                Toast.makeText(this, "ابتدا نمایش روی برنامه‌ها را فعال کنید", Toast.LENGTH_SHORT).show()
            else -> if (!JarvisOverlayService.startWakeWord(this)) {
                Toast.makeText(this, "شروع فرمان صوتی ممکن نشد", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun refreshPermissionState() {
        val granted = JarvisOverlayService.hasOverlayPermission(this)
        statusRow.visibility = if (granted) View.VISIBLE else View.GONE
        grantButton.visibility = if (granted) View.GONE else View.VISIBLE
        statusText.text = "نمایش روی برنامه‌ها فعال است"
    }

    private fun onGrantClicked() {
        // User-initiated only; the result is picked up in onResume().
        if (!JarvisOverlayService.openOverlayPermissionSettings(this)) {
            Toast.makeText(this, "صفحه تنظیمات در دسترس نیست", Toast.LENGTH_SHORT).show()
        }
    }

    // ---- UI (code-built) ------------------------------------------------------------------------

    private fun buildUi(): View {
        val root = FrameLayout(this).apply {
            setBackgroundColor(BG)
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(32), dp(32), dp(32), dp(32))
        }

        val title = TextView(this).apply {
            text = "JARVIS"
            setTextColor(TEXT_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 34f)
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            letterSpacing = 0.32f
            gravity = Gravity.CENTER
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            textDirection = View.TEXT_DIRECTION_LTR
        }

        val rule = View(this).apply {
            setBackgroundColor(ACCENT_DIM)
        }

        val subtitle = TextView(this).apply {
            text = "دستیار شخصی شما"
            setTextColor(TEXT_SECONDARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            gravity = Gravity.CENTER
        }

        grantButton = outlineButton("فعال‌سازی نمایش روی برنامه‌ها") { onGrantClicked() }

        val dot = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(ACCENT)
            }
        }
        statusText = TextView(this).apply {
            setTextColor(TEXT_SECONDARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
        }
        statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(dot, LinearLayout.LayoutParams(dp(6), dp(6)).apply { marginEnd = dp(10) })
            addView(statusText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        fun lp(topDp: Int, w: Int = ViewGroup.LayoutParams.WRAP_CONTENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT) =
            LinearLayout.LayoutParams(w, h).apply { topMargin = dp(topDp); gravity = Gravity.CENTER_HORIZONTAL }

        column.addView(title, lp(0))
        column.addView(rule, lp(18, dp(36), dp(1)))
        column.addView(subtitle, lp(18))
        column.addView(grantButton, lp(56))
        column.addView(statusRow, lp(56))

        voiceButton = outlineButton("فعال‌سازی فرمان صوتی") { onVoiceClicked() }
        voiceStatus = TextView(this).apply {
            setTextColor(TEXT_SECONDARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        column.addView(voiceButton, lp(20))
        column.addView(voiceStatus, lp(14))


        root.addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        return root
    }

    private fun outlineButton(label: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        setTextColor(ACCENT)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        gravity = Gravity.CENTER
        setPadding(dp(28), dp(14), dp(28), dp(14))
        val shape = GradientDrawable().apply {
            cornerRadius = dp(26).toFloat()
            setColor(Color.TRANSPARENT)
            setStroke(dp(1), ACCENT_DIM)
        }
        background = RippleDrawable(ColorStateList.valueOf(RIPPLE), shape, null)
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    private companion object {
        const val REQ_MIC = 4401
        val BG = Color.parseColor("#05080C")
        val TEXT_PRIMARY = Color.parseColor("#E6F6FF")
        val TEXT_SECONDARY = Color.parseColor("#8FA3B0")
        val ACCENT = Color.parseColor("#5FD8FF")
        val ACCENT_DIM = Color.parseColor("#335FD8FF")
        val RIPPLE = Color.parseColor("#225FD8FF")
    }
}
