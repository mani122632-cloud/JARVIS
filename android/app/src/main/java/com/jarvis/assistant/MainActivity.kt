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
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.jarvis.assistant.overlay.JarvisOverlayService
import com.jarvis.assistant.speech.tts.OfflinePersianTts
import com.jarvis.assistant.speech.JarvisSpeechController
import com.jarvis.assistant.wakeword.TtsStatus
import com.jarvis.assistant.wakeword.VoskWakeWordEngine
import com.jarvis.assistant.wakeword.WakeStatus
import com.jarvis.assistant.wakeword.WakeWordState

/**
 * JARVIS main screen: a simple RTL chat base (header, message list, text input, send, "+" placeholder).
 * The assistant itself still lives in JarvisOverlayService / JarvisOverlayWindow / JarvisActivationController.
 *
 * The existing setup controls (overlay permission, wake word, Vision, DEV TTS) are kept unchanged,
 * only moved into a compact strip under the header.
 * Built in code (no layout XML, no extra dependencies) so it cannot clash with existing resources.
 */
class MainActivity : Activity() {

    private lateinit var statusRow: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var grantButton: TextView
    private lateinit var voiceButton: TextView
    private lateinit var voiceStatus: TextView
    private lateinit var ttsStatus: TextView
    private lateinit var chatScroll: ScrollView
    private lateinit var messages: LinearLayout
    private lateinit var input: EditText

    /** DEBUG builds only: a separate engine instance used by the [DEV] voice test button. */
    private var devTts: OfflinePersianTts? = null
    private var devPhraseIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        run {
            window.statusBarColor = BG
            window.navigationBarColor = BG
        }
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        setContentView(buildUi())
        // Load the Vosk model now (background, no microphone, no service) so the first wake-word start is instant.
        if (!WakeWordState.isActive()) VoskWakeWordEngine.warmUp(this)
    }

    override fun onStart() {
        super.onStart()
        WakeWordState.listener = Runnable { refreshVoiceState() }
    }

    override fun onStop() {
        WakeWordState.listener = null
        super.onStop()
    }

    override fun onDestroy() {
        devTts?.release()
        devTts = null
        super.onDestroy()
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
            WakeStatus.PHRASE_UNSUPPORTED -> "مدل صوتی عبارت «هی جارویس» را نمی‌شناسد"
            WakeStatus.NO_PERMISSION -> "اجازه میکروفون لازم است"
            WakeStatus.ERROR -> "میکروفون یا مدل صوتی در دسترس نیست"
            WakeStatus.OFF -> ""
        }
        voiceStatus.text = msg
        voiceStatus.visibility = if (msg.isEmpty()) View.GONE else View.VISIBLE

        val tmsg = when (WakeWordState.tts) {
            TtsStatus.LOADING -> "در حال آماده‌سازی صدای فارسی…"
            TtsStatus.MODEL_MISSING -> "Offline Persian TTS model is missing"
            TtsStatus.ENGINE_MISSING -> "Offline Persian TTS engine is not in this build"
            TtsStatus.ERROR -> "Offline Persian TTS failed to load"
            TtsStatus.READY, TtsStatus.OFF -> ""
        }
        ttsStatus.text = tmsg
        ttsStatus.visibility = if (tmsg.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun isDebuggable(): Boolean = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    /** [DEV] Speaks the next sample sentence through the offline engine; no overlay, no microphone. */
    private fun onDevTtsClicked() {
        val tts = devTts ?: OfflinePersianTts(this).also { devTts = it; it.initialize() }
        val phrases = com.jarvis.assistant.speech.JarvisPhrases.PREWARM
        val text = phrases[devPhraseIndex % phrases.size]
        devPhraseIndex++
        tts.speak(text, object : JarvisSpeechController.Callback {
            override fun onStart() = Unit
            override fun onDone(success: Boolean) {
                if (!success) Toast.makeText(this@MainActivity, tts.lastError ?: "TTS failed", Toast.LENGTH_LONG).show()
            }
        })
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

    @Suppress("DEPRECATION")
    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        // Keep content clear of system bars / keyboard (works edge-to-edge or not).
        root.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                v.setPadding(i.left, i.top, i.right, i.bottom)
            } else {
                v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }

        val match = ViewGroup.LayoutParams.MATCH_PARENT
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT

        // Header
        val header = FrameLayout(this)
        header.addView(TextView(this).apply {
            text = "JARVIS"
            setTextColor(TEXT_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            letterSpacing = 0.3f
            gravity = Gravity.CENTER
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            textDirection = View.TEXT_DIRECTION_LTR
        }, FrameLayout.LayoutParams(wrap, wrap, Gravity.CENTER))
        root.addView(header, LinearLayout.LayoutParams(match, dp(56)))
        root.addView(View(this).apply { setBackgroundColor(ACCENT_DIM) }, LinearLayout.LayoutParams(match, dp(1)))

        // Existing setup controls (unchanged behavior), compact strip
        grantButton = chip("فعال‌سازی نمایش روی برنامه‌ها") { onGrantClicked() }
        voiceButton = chip("فعال‌سازی فرمان صوتی") { onVoiceClicked() }
        val chips = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(8), dp(12), dp(4))
        }
        fun chipLp() = LinearLayout.LayoutParams(wrap, wrap).apply { marginEnd = dp(8) }
        chips.addView(grantButton, chipLp())
        chips.addView(voiceButton, chipLp())
        chips.addView(chip("Vision") {
            startActivity(android.content.Intent(this, com.jarvis.assistant.vision.VisionActivity::class.java))
        }, chipLp())
        if (isDebuggable()) chips.addView(chip("[DEV] TTS") { onDevTtsClicked() }, chipLp())
        root.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(chips)
        }, LinearLayout.LayoutParams(match, wrap))

        val dot = View(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(ACCENT) }
        }
        statusText = TextView(this).apply {
            setTextColor(TEXT_SECONDARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        }
        statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, dp(16), dp(2))
            addView(dot, LinearLayout.LayoutParams(dp(6), dp(6)).apply { marginEnd = dp(8) })
            addView(statusText, LinearLayout.LayoutParams(wrap, wrap))
        }
        voiceStatus = TextView(this).apply {
            setTextColor(TEXT_SECONDARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(dp(16), 0, dp(16), dp(2))
            visibility = View.GONE
        }
        ttsStatus = TextView(this).apply {
            setTextColor(TEXT_SECONDARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(dp(16), 0, dp(16), dp(2))
            visibility = View.GONE
        }
        root.addView(statusRow, LinearLayout.LayoutParams(match, wrap))
        root.addView(voiceStatus, LinearLayout.LayoutParams(match, wrap))
        root.addView(ttsStatus, LinearLayout.LayoutParams(match, wrap))

        // Messages
        messages = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        chatScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(messages, FrameLayout.LayoutParams(match, wrap))
        }
        root.addView(chatScroll, LinearLayout.LayoutParams(match, 0, 1f))
        addMessage("سلام، من جارویس هستم. چطور می‌توانم کمکتان کنم؟", fromUser = false)

        // Input bar: "+" (no action yet) | text field | send
        val plus = TextView(this).apply {
            text = "+"
            setTextColor(ACCENT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            gravity = Gravity.CENTER
            background = circle(Color.TRANSPARENT, ACCENT_DIM)
            isClickable = true   // placeholder only: intentionally no click action in this step
        }
        input = EditText(this).apply {
            hint = "پیام خود را بنویسید…"
            setHintTextColor(TEXT_SECONDARY)
            setTextColor(TEXT_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            maxLines = 4
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            textDirection = View.TEXT_DIRECTION_FIRST_STRONG_RTL
            setPadding(dp(18), dp(10), dp(18), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                setColor(SURFACE)
                setStroke(dp(1), ACCENT_DIM)
            }
        }
        val send = TextView(this).apply {
            text = "➤"
            setTextColor(BG)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            gravity = Gravity.CENTER
            scaleX = -1f   // arrow points left (RTL "forward")
            background = circle(ACCENT, Color.TRANSPARENT)
            isClickable = true
            isFocusable = true
            setOnClickListener { onSendClicked() }
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            setPadding(dp(10), dp(8), dp(10), dp(10))
        }
        bar.addView(plus, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(8) })
        bar.addView(input, LinearLayout.LayoutParams(0, wrap, 1f).apply { marginEnd = dp(8) })
        bar.addView(send, LinearLayout.LayoutParams(dp(44), dp(44)))
        root.addView(bar, LinearLayout.LayoutParams(match, wrap))
        return root
    }

    private fun onSendClicked() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        addMessage(text, fromUser = true)
        input.text.clear()
    }

    private fun addMessage(text: String, fromUser: Boolean) {
        val bubble = TextView(this).apply {
            this.text = text
            setTextColor(TEXT_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setLineSpacing(0f, 1.15f)
            textDirection = View.TEXT_DIRECTION_FIRST_STRONG_RTL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            maxWidth = (resources.displayMetrics.widthPixels * 0.8f).toInt()
            setTextIsSelectable(true)
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                if (fromUser) { setColor(USER_BUBBLE); setStroke(dp(1), ACCENT_DIM) }
                else { setColor(SURFACE) }
            }
        }
        // RTL: START = right (user), END = left (JARVIS)
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = if (fromUser) Gravity.START else Gravity.END
            topMargin = dp(8)
        }
        messages.addView(bubble, lp)
        chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun circle(fill: Int, stroke: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(fill)
        if (stroke != Color.TRANSPARENT) setStroke(dp(1), stroke)
    }

    private fun chip(label: String, onClick: () -> Unit): TextView = outlineButton(label, onClick).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setPadding(dp(14), dp(7), dp(14), dp(7))
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
        val SURFACE = Color.parseColor("#0E151C")
        val USER_BUBBLE = Color.parseColor("#14305A6B")
    }
}
