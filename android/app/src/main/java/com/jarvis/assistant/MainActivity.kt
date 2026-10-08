package com.jarvis.assistant

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
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
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.jarvis.assistant.online.GeminiSetupActivity
import com.jarvis.assistant.overlay.JarvisOverlayService
import com.jarvis.assistant.speech.tts.OfflinePersianTts
import com.jarvis.assistant.speech.JarvisSpeechController
import com.jarvis.assistant.wakeword.TtsStatus
import com.jarvis.assistant.wakeword.VoskWakeWordEngine
import com.jarvis.assistant.wakeword.WakeStatus
import com.jarvis.assistant.wakeword.WakeWordState
import kotlin.math.cos
import kotlin.math.sin

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

    // Side drawer (Step 2): UI shell only, built in code like the rest of this screen.
    private lateinit var drawerPanel: View
    private lateinit var drawerScrim: View
    private var drawerOpen = false
    private var drawerWidthPx = 0

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
        closeDrawer(animate = false)
        WakeWordState.listener = null
        super.onStop()
    }

    override fun onDestroy() {
        devTts?.release()
        devTts = null
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (drawerOpen) closeDrawer() else super.onBackPressed()
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

    /** Screen = chat content + (hidden) scrim + (hidden) side drawer, stacked in one RTL container. */
    private fun buildUi(): View {
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        val container = FrameLayout(this).apply {
            setBackgroundColor(BG)
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        container.addView(buildChatContent(), FrameLayout.LayoutParams(match, match))

        drawerScrim = View(this).apply {
            setBackgroundColor(SCRIM)
            alpha = 0f
            visibility = View.GONE
            setOnClickListener { closeDrawer() }
        }
        container.addView(drawerScrim, FrameLayout.LayoutParams(match, match))

        drawerWidthPx = minOf(dp(300), (resources.displayMetrics.widthPixels * 0.84f).toInt())
        drawerPanel = buildDrawer()
        // Gravity.START in an RTL container = right edge.
        container.addView(drawerPanel, FrameLayout.LayoutParams(drawerWidthPx, match, Gravity.START))
        return container
    }

    @Suppress("DEPRECATION")
    private fun buildChatContent(): View {
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
        // Hamburger: start edge of the header (right side in RTL).
        header.addView(MenuIconView(this, TEXT_PRIMARY).apply {
            contentDescription = "منو"
            isClickable = true
            isFocusable = true
            background = RippleDrawable(
                ColorStateList.valueOf(RIPPLE), null,
                GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.BLACK) }
            )
            setOnClickListener { openDrawer() }
        }, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER_VERTICAL or Gravity.START).apply {
            marginStart = dp(8)
        })
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
        addMessage(GREETING, fromUser = false)

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

    // ---- Drawer (Step 2) --------------------------------------------------------------------------

    @Suppress("DEPRECATION")
    private fun buildDrawer(): View {
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(12), dp(16))
        }
        val panel = FrameLayout(this).apply {
            setBackgroundColor(DRAWER_BG)
            elevation = dp(16).toFloat()
            isClickable = true      // swallow touches so they never reach the chat underneath
            visibility = View.GONE
        }
        panel.addView(column, FrameLayout.LayoutParams(match, match))
        // Thin accent edge on the inner side (left in RTL).
        panel.addView(View(this).apply { setBackgroundColor(ACCENT_DIM) },
            FrameLayout.LayoutParams(dp(1), match, Gravity.END))
        panel.setOnApplyWindowInsetsListener { _, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val i = insets.getInsets(WindowInsets.Type.systemBars())
                column.setPadding(dp(12), i.top, dp(12), i.bottom + dp(16))
            } else {
                column.setPadding(dp(12), insets.systemWindowInsetTop, dp(12), insets.systemWindowInsetBottom + dp(16))
            }
            insets
        }

        // Brand row (same height as the chat header so the two line up)
        column.addView(TextView(this).apply {
            text = "JARVIS"
            setTextColor(TEXT_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            letterSpacing = 0.3f
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            textDirection = View.TEXT_DIRECTION_LTR
            setPadding(dp(14), 0, dp(14), 0)
        }, LinearLayout.LayoutParams(match, dp(56)))
        column.addView(divider(), dividerLp())

        column.addView(drawerItem(Glyph.NEW_CHAT, "گفتگوی جدید") { onNewChatClicked() }, itemLp())
        column.addView(drawerItem(Glyph.HISTORY, "تاریخچه گفتگوها") { onChatHistoryClicked() }, itemLp())

        column.addView(View(this), LinearLayout.LayoutParams(match, 0, 1f))   // flexible spacer

        column.addView(divider(), dividerLp())
        column.addView(drawerItem(Glyph.VOICE, "دستیار صوتی") { onVoiceAssistantClicked() }, itemLp())
        column.addView(drawerItem(Glyph.SETTINGS, "تنظیمات") { onSettingsClicked() }, itemLp())
        return panel
    }

    private fun divider(): View = View(this).apply { setBackgroundColor(ACCENT_DIM) }

    private fun dividerLp() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
        topMargin = dp(8); bottomMargin = dp(8)
    }

    private fun itemLp() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        topMargin = dp(2); bottomMargin = dp(2)
    }

    private fun drawerItem(glyph: Glyph, label: String, onClick: () -> Unit): View {
        val mask = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(Color.BLACK) }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(52)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = RippleDrawable(ColorStateList.valueOf(RIPPLE), null, mask)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            // RTL: first child sits on the right.
            addView(GlyphView(this@MainActivity, glyph, ACCENT), LinearLayout.LayoutParams(dp(24), dp(24)))
            addView(TextView(this@MainActivity).apply {
                text = label
                setTextColor(TEXT_PRIMARY)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                textDirection = View.TEXT_DIRECTION_RTL
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(16) })
        }
    }

    private fun openDrawer() {
        if (drawerOpen) return
        drawerOpen = true
        hideKeyboard()
        drawerScrim.animate().cancel()
        drawerPanel.animate().cancel()
        if (drawerPanel.visibility != View.VISIBLE) {
            drawerPanel.translationX = drawerWidthPx.toFloat()   // starts off-screen on the right (RTL start)
            drawerScrim.alpha = 0f
        }
        drawerScrim.visibility = View.VISIBLE
        drawerPanel.visibility = View.VISIBLE
        drawerScrim.animate().alpha(1f).setDuration(220).start()
        drawerPanel.animate().translationX(0f).setDuration(240).setInterpolator(DecelerateInterpolator()).start()
    }

    private fun closeDrawer(animate: Boolean = true) {
        if (!::drawerPanel.isInitialized) return
        if (!drawerOpen && drawerPanel.visibility != View.VISIBLE) return
        drawerOpen = false
        drawerScrim.animate().cancel()
        drawerPanel.animate().cancel()
        if (!animate) {
            drawerScrim.alpha = 0f
            drawerScrim.visibility = View.GONE
            drawerPanel.visibility = View.GONE
            return
        }
        drawerScrim.animate().alpha(0f).setDuration(200).start()
        drawerPanel.animate().translationX(drawerWidthPx.toFloat()).setDuration(200)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction {
                if (!drawerOpen) {
                    drawerScrim.visibility = View.GONE
                    drawerPanel.visibility = View.GONE
                }
            }.start()
    }

    private fun hideKeyboard() {
        val focus = currentFocus ?: return
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(focus.windowToken, 0)
    }

    /** Resets only the visible message list; no conversation/brain state is touched. */
    private fun onNewChatClicked() {
        closeDrawer()
        messages.removeAllViews()
        addMessage(GREETING, fromUser = false)
        input.text.clear()
    }

    /** Placeholder: history storage/UI comes in a later step. */
    private fun onChatHistoryClicked() {
        closeDrawer()
        Toast.makeText(this, "تاریخچه گفتگوها به‌زودی اضافه می‌شود", Toast.LENGTH_SHORT).show()
    }

    /** Entry point only: reuses the existing voice (wake word) toggle, no new logic. */
    private fun onVoiceAssistantClicked() {
        closeDrawer()
        onVoiceClicked()
    }

    /** Entry point only: opens the existing setup screen (online provider keys). */
    private fun onSettingsClicked() {
        closeDrawer()
        startActivity(android.content.Intent(this, GeminiSetupActivity::class.java))
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
        const val GREETING = "سلام، من جارویس هستم. چطور می‌توانم کمکتان کنم؟"
        val BG = Color.parseColor("#05080C")
        val TEXT_PRIMARY = Color.parseColor("#E6F6FF")
        val TEXT_SECONDARY = Color.parseColor("#8FA3B0")
        val ACCENT = Color.parseColor("#5FD8FF")
        val ACCENT_DIM = Color.parseColor("#335FD8FF")
        val RIPPLE = Color.parseColor("#225FD8FF")
        val SURFACE = Color.parseColor("#0E151C")
        val USER_BUBBLE = Color.parseColor("#14305A6B")
        val DRAWER_BG = Color.parseColor("#0A1118")
        val SCRIM = Color.parseColor("#99000000")
    }
}

// ---- Drawer helper views (drawn in code: no drawable resources, no dependencies) -----------------

private enum class Glyph { NEW_CHAT, HISTORY, VOICE, SETTINGS }

/** Hamburger: three rounded lines aligned to the start edge (right in RTL), middle one shorter. */
private class MenuIconView(ctx: Context, tint: Int) : View(ctx) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = tint
    }

    override fun onDraw(canvas: Canvas) {
        val d = resources.displayMetrics.density
        paint.strokeWidth = 2f * d
        val full = 20f * d
        val gap = 6f * d
        val left = (width - full) / 2f
        val right = left + full
        val cy = height / 2f
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        val lengths = floatArrayOf(full, full * 0.68f, full)
        for (i in 0..2) {
            val y = cy + (i - 1) * gap
            if (rtl) canvas.drawLine(right - lengths[i], y, right, y, paint)
            else canvas.drawLine(left, y, left + lengths[i], y, paint)
        }
    }
}

/** Simple line glyphs on a 24-unit grid. */
private class GlyphView(ctx: Context, private val glyph: Glyph, tint: Int) : View(ctx) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = tint
    }
    private val rect = RectF()
    private val path = Path()

    override fun onDraw(canvas: Canvas) {
        val side = minOf(width, height).toFloat()
        val u = side / 24f
        val ox = (width - side) / 2f
        val oy = (height - side) / 2f
        paint.strokeWidth = 1.7f * u
        fun x(v: Float) = ox + v * u
        fun y(v: Float) = oy + v * u

        when (glyph) {
            Glyph.NEW_CHAT -> {
                canvas.drawCircle(x(12f), y(12f), 9f * u, paint)
                canvas.drawLine(x(12f), y(8f), x(12f), y(16f), paint)
                canvas.drawLine(x(8f), y(12f), x(16f), y(12f), paint)
            }
            Glyph.HISTORY -> {
                canvas.drawCircle(x(12f), y(12f), 9f * u, paint)
                path.reset()
                path.moveTo(x(12f), y(7f))
                path.lineTo(x(12f), y(12f))
                path.lineTo(x(15.5f), y(14f))
                canvas.drawPath(path, paint)
            }
            Glyph.VOICE -> {
                rect.set(x(9f), y(3f), x(15f), y(14f))
                canvas.drawRoundRect(rect, 3f * u, 3f * u, paint)
                rect.set(x(6f), y(7f), x(18f), y(19f))
                canvas.drawArc(rect, 0f, 180f, false, paint)
                canvas.drawLine(x(12f), y(19f), x(12f), y(21.5f), paint)
                canvas.drawLine(x(9f), y(21.5f), x(15f), y(21.5f), paint)
            }
            Glyph.SETTINGS -> {
                canvas.drawCircle(x(12f), y(12f), 3f * u, paint)
                canvas.drawCircle(x(12f), y(12f), 7.2f * u, paint)
                for (i in 0 until 8) {
                    val a = Math.PI / 4.0 * i
                    val c = cos(a).toFloat()
                    val sn = sin(a).toFloat()
                    canvas.drawLine(x(12f + 7.2f * c), y(12f + 7.2f * sn), x(12f + 9.8f * c), y(12f + 9.8f * sn), paint)
                }
            }
        }
    }
}
