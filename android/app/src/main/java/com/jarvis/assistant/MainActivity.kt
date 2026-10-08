package com.jarvis.assistant

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.text.TextWatcher
import android.text.Editable
import android.os.Bundle
import android.text.InputType
import android.util.Base64
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.MotionEvent
import android.view.ViewOutlineProvider
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.jarvis.assistant.chat.ChatMessage
import com.jarvis.assistant.chat.ChatSession
import com.jarvis.assistant.chat.ChatStore
import com.jarvis.assistant.online.Cancellable
import com.jarvis.assistant.online.Failure
import com.jarvis.assistant.online.FailureKind
import com.jarvis.assistant.online.GeminiSetupActivity
import com.jarvis.assistant.online.GroqVisionProvider
import com.jarvis.assistant.online.OnlineBrain
import com.jarvis.assistant.online.OnlineNetwork
import com.jarvis.assistant.online.OnlineProviderRegistry
import com.jarvis.assistant.online.OnlineProviderRouter
import com.jarvis.assistant.online.GeminiProvider
import com.jarvis.assistant.online.GroqProvider
import com.jarvis.assistant.overlay.JarvisOverlayService
import com.jarvis.assistant.speech.tts.OfflinePersianTts
import com.jarvis.assistant.speech.JarvisSpeechController
import com.jarvis.assistant.vision.VisionActivity
import com.jarvis.assistant.wakeword.TtsStatus
import com.jarvis.assistant.wakeword.VoskWakeWordEngine
import com.jarvis.assistant.wakeword.WakeStatus
import com.jarvis.assistant.wakeword.WakeWordState
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
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

    // Attach sheet ("+" menu) and pending image (Step 2): UI only, analysis goes through the existing Groq Vision path.
    private lateinit var sheetPanel: View
    private lateinit var sheetScrim: View
    private var sheetOpen = false
    private lateinit var attachPreview: LinearLayout
    private lateinit var attachThumb: ImageView
    private lateinit var sendButton: ImageGlyphButton
    private var pendingJpeg: ByteArray? = null
    private var brain: OnlineBrain? = null
    private var chatBrain: OnlineBrain? = null
    private var turnHandle: Cancellable? = null
    private var turnSerial = 0
    private var busy = false

    // Persistent Chat UI history. OnlineBrain keeps the live AI context separately.
    private val chatStore by lazy { ChatStore(applicationContext) }
    private var currentChat: ChatSession? = null
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "jarvis-chat-image").apply { isDaemon = true } }

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
        initializeChatSession()
        // Load the Vosk model now (background, no microphone, no service) so the first wake-word start is instant.
        if (!WakeWordState.isActive()) VoskWakeWordEngine.warmUp(this)
    }

    override fun onStart() {
        super.onStart()
        WakeWordState.listener = Runnable { refreshVoiceState() }
    }

    override fun onStop() {
        persistCurrentChat()
        closeDrawer(animate = false)
        closeSheet(animate = false)
        WakeWordState.listener = null
        super.onStop()
    }

    override fun onDestroy() {
        devTts?.release()
        devTts = null
        turnSerial++
        turnHandle?.cancel()
        brain?.release(); brain = null
        chatBrain?.release(); chatBrain = null
        worker.shutdownNow()
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            sheetOpen -> closeSheet()
            drawerOpen -> closeDrawer()
            else -> super.onBackPressed()
        }
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

        sheetScrim = View(this).apply {
            setBackgroundColor(SCRIM)
            alpha = 0f
            visibility = View.GONE
            setOnClickListener { closeSheet() }
        }
        container.addView(sheetScrim, FrameLayout.LayoutParams(match, match))
        sheetPanel = buildAttachSheet()
        container.addView(sheetPanel, FrameLayout.LayoutParams(match, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
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
        val plus = GlyphView(this, Glyph.PLUS, ACCENT).apply {
            contentDescription = "افزودن تصویر"
            background = RippleDrawable(ColorStateList.valueOf(RIPPLE), circle(Color.TRANSPARENT, ACCENT_DIM), null)
            isClickable = true
            isFocusable = true
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setOnClickListener { openSheet() }
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
            minHeight = dp(46)
            background = inputBackground(false)
            setOnFocusChangeListener { _, focused ->
                background = inputBackground(focused)
                if (focused) {
                    chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
                }
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    updateSendButtonState()
                }
            })
        }
        sendButton = ImageGlyphButton(this).apply {
            contentDescription = "ارسال"
            background = circle(ACCENT, Color.TRANSPARENT)
            isClickable = true
            isFocusable = true
            setOnClickListener { onSendClicked() }
        }
        val send = sendButton
        // Pending image preview (above the input bar)
        attachThumb = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) { outline.setRoundRect(0, 0, view.width, view.height, dp(12).toFloat()) }
            }
        }
        val removeBtn = GlyphView(this, Glyph.CLOSE, TEXT_PRIMARY).apply {
            contentDescription = "حذف تصویر"
            background = circle(SURFACE, ACCENT_DIM)
            isClickable = true
            isFocusable = true
            setPadding(dp(6), dp(6), dp(6), dp(6))
            setOnClickListener { clearPendingImage() }
        }
        attachPreview = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(8), dp(14), 0)
            visibility = View.GONE
            addView(attachThumb, LinearLayout.LayoutParams(dp(64), dp(64)))
            addView(TextView(this@MainActivity).apply {
                text = "تصویر پیوست شد. سؤال خود را بنویسید"
                setTextColor(TEXT_SECONDARY)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            }, LinearLayout.LayoutParams(0, wrap, 1f).apply { marginStart = dp(12) })
            addView(removeBtn, LinearLayout.LayoutParams(dp(28), dp(28)))
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            setPadding(dp(10), dp(8), dp(10), dp(10))
        }
        bar.addView(plus, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(8) })
        bar.addView(input, LinearLayout.LayoutParams(0, wrap, 1f).apply { marginEnd = dp(8) })
        bar.addView(send, LinearLayout.LayoutParams(dp(44), dp(44)))
        root.addView(attachPreview, LinearLayout.LayoutParams(match, wrap))
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

    private fun initializeChatSession() {
        val lastId = chatStore.lastId
        val restored = lastId?.let { chatStore.load(it) }

        currentChat = restored ?: ChatSession(
            id = java.util.UUID.randomUUID().toString(),
            title = "گفتگوی جدید",
            updatedAt = System.currentTimeMillis()
        )

        if (restored != null) {
            messages.removeAllViews()
            restored.messages.forEach { message ->
                addMessage(message.text, message.fromUser, saveToChat = false)
            }
        }
    }

    private fun persistCurrentChat() {
        val chat = currentChat ?: return
        if (chat.messages.isEmpty()) return
        chat.updatedAt = System.currentTimeMillis()
        chatStore.save(chat)
    }

    /** Resets only the visible message list (and a pending image); no wake-word/voice state is touched. */
    private fun onNewChatClicked() {
        closeDrawer()
        cancelTurn()
        clearPendingImage()

        brain?.resetSession()
        chatBrain?.resetSession()
        persistCurrentChat()

        currentChat = ChatSession(
            id = java.util.UUID.randomUUID().toString(),
            title = "گفتگوی جدید",
            updatedAt = System.currentTimeMillis()
        )

        messages.removeAllViews()
        addMessage(GREETING, fromUser = false)
        input.text.clear()
    }

    /** Placeholder: history storage/UI comes in a later step. */
    private fun onChatHistoryClicked() {
        closeDrawer()
        showChatHistory()
    }

    private fun showChatHistory() {
        hideKeyboard()

        val summaries = chatStore.list()
        val overlay = FrameLayout(this).apply {
            setBackgroundColor(Color.argb(235, 4, 9, 12))
            isClickable = true
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(DRAWER_BG)
            setPadding(dp(16), dp(18), dp(16), dp(18))
            elevation = dp(18).toFloat()
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        header.addView(TextView(this).apply {
            text = "تاریخچه گفتگوها"
            setTextColor(TEXT_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            gravity = Gravity.CENTER_VERTICAL
        }, LinearLayout.LayoutParams(0, dp(48), 1f))

        header.addView(GlyphView(this, Glyph.CLOSE, TEXT_PRIMARY).apply {
            contentDescription = "بستن تاریخچه"
            background = RippleDrawable(
                ColorStateList.valueOf(RIPPLE),
                circle(Color.TRANSPARENT, ACCENT_DIM),
                null
            )
            isClickable = true
            isFocusable = true
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setOnClickListener {
                (overlay.parent as? ViewGroup)?.removeView(overlay)
            }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))

        panel.addView(header)
        panel.addView(divider(), LinearLayout.LayoutParams(-1, dp(1)).apply {
            topMargin = dp(8)
            bottomMargin = dp(8)
        })

        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        if (summaries.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "هنوز گفتگویی ذخیره نشده است."
                setTextColor(TEXT_SECONDARY)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(40), dp(12), dp(40))
            }, LinearLayout.LayoutParams(-1, -2))
        } else {
            summaries.forEach { summary ->
                list.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_VERTICAL
                    minimumHeight = dp(64)
                    setPadding(dp(14), dp(9), dp(14), dp(9))
                    background = RippleDrawable(
                        ColorStateList.valueOf(RIPPLE),
                        null,
                        GradientDrawable().apply {
                            cornerRadius = dp(14).toFloat()
                            setColor(Color.BLACK)
                        }
                    )
                    isClickable = true
                    isFocusable = true

                    addView(TextView(this@MainActivity).apply {
                        text = summary.title.ifBlank { "گفتگوی جدید" }
                        setTextColor(TEXT_PRIMARY)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        textDirection = View.TEXT_DIRECTION_RTL
                    })

                    addView(TextView(this@MainActivity).apply {
                        text = "${summary.count} پیام"
                        setTextColor(TEXT_SECONDARY)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                        textDirection = View.TEXT_DIRECTION_RTL
                    })

                    setOnClickListener {
                        loadChatSession(summary.id)
                        (overlay.parent as? ViewGroup)?.removeView(overlay)
                    }
                }, LinearLayout.LayoutParams(-1, -2).apply {
                    bottomMargin = dp(6)
                })
            }
        }

        panel.addView(ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(list, FrameLayout.LayoutParams(-1, -2))
        }, LinearLayout.LayoutParams(-1, 0, 1f))

        overlay.addView(
            panel,
            FrameLayout.LayoutParams(
                -1,
                -1,
                Gravity.CENTER
            ).apply {
                leftMargin = dp(12)
                rightMargin = dp(12)
                topMargin = dp(24)
                bottomMargin = dp(24)
            }
        )

        (window.decorView as? ViewGroup)?.addView(
            overlay,
            ViewGroup.LayoutParams(-1, -1)
        )
    }

    private fun loadChatSession(id: String) {
        val session = chatStore.load(id) ?: return

        cancelTurn()
        clearPendingImage()
        chatBrain?.resetSession()

        currentChat = session
        messages.removeAllViews()

        session.messages.forEach { message ->
            addMessage(
                message.text,
                message.fromUser,
                saveToChat = false
            )
        }

        scrollToEnd()
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

    // ---- Attach sheet ("+") -----------------------------------------------------------------------

    private fun buildAttachSheet(): View {
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(16), dp(10), dp(16), dp(16))
            background = GradientDrawable().apply {
                setColor(DRAWER_BG)
                setStroke(dp(1), ACCENT_DIM)
                cornerRadii = floatArrayOf(dp(24).toFloat(), dp(24).toFloat(), dp(24).toFloat(), dp(24).toFloat(), 0f, 0f, 0f, 0f)
            }
            elevation = dp(16).toFloat()
            isClickable = true      // swallow touches
            visibility = View.GONE
        }
        column.setOnApplyWindowInsetsListener { v, insets ->
            @Suppress("DEPRECATION")
            val bottom = if (Build.VERSION.SDK_INT >= 30) insets.getInsets(WindowInsets.Type.systemBars()).bottom
            else insets.systemWindowInsetBottom
            v.setPadding(dp(16), dp(10), dp(16), bottom + dp(16))
            insets
        }
        // Grab handle
        column.addView(View(this).apply {
            background = GradientDrawable().apply { cornerRadius = dp(2).toFloat(); setColor(ACCENT_DIM) }
        }, LinearLayout.LayoutParams(dp(36), dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(10) })
        column.addView(TextView(this).apply {
            text = "افزودن به گفتگو"
            setTextColor(TEXT_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            textDirection = View.TEXT_DIRECTION_RTL
            gravity = Gravity.START
            setPadding(dp(6), dp(4), dp(6), dp(8))
        }, LinearLayout.LayoutParams(match, wrap))
        column.addView(divider(), dividerLp())
        column.addView(sheetItem(Glyph.CAMERA, "دوربین", "گرفتن عکس با دوربین حرفه‌ای") { onAttachCameraClicked() }, itemLp())
        column.addView(sheetItem(Glyph.GALLERY, "گالری", "انتخاب تصویر از گوشی") { onAttachGalleryClicked() }, itemLp())
        return column
    }

    private fun sheetItem(glyph: Glyph, title: String, subtitle: String, onClick: () -> Unit): View {
        val mask = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(Color.BLACK) }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@MainActivity).apply {
                text = title
                setTextColor(TEXT_PRIMARY)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                textDirection = View.TEXT_DIRECTION_RTL
                gravity = Gravity.START
            })
            addView(TextView(this@MainActivity).apply {
                text = subtitle
                setTextColor(TEXT_SECONDARY)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                textDirection = View.TEXT_DIRECTION_RTL
                gravity = Gravity.START
            })
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(60)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = RippleDrawable(ColorStateList.valueOf(RIPPLE), null, mask)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            addView(GlyphView(this@MainActivity, glyph, ACCENT).apply {
                background = circle(Color.TRANSPARENT, ACCENT_DIM)
                setPadding(dp(11), dp(11), dp(11), dp(11))
            }, LinearLayout.LayoutParams(dp(48), dp(48)))
            addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(14) })
        }
    }

    private fun openSheet() {
        if (sheetOpen) return
        sheetOpen = true
        hideKeyboard()
        sheetScrim.animate().cancel()
        sheetPanel.animate().cancel()
        if (sheetPanel.visibility != View.VISIBLE) {
            sheetPanel.translationY = dp(220).toFloat()
            sheetScrim.alpha = 0f
        }
        sheetScrim.visibility = View.VISIBLE
        sheetPanel.visibility = View.VISIBLE
        sheetScrim.animate().alpha(1f).setDuration(200).start()
        sheetPanel.animate().translationY(0f).setDuration(220).setInterpolator(DecelerateInterpolator()).start()
    }

    private fun closeSheet(animate: Boolean = true) {
        if (!::sheetPanel.isInitialized) return
        if (!sheetOpen && sheetPanel.visibility != View.VISIBLE) return
        sheetOpen = false
        sheetScrim.animate().cancel()
        sheetPanel.animate().cancel()
        if (!animate) {
            sheetScrim.alpha = 0f
            sheetScrim.visibility = View.GONE
            sheetPanel.visibility = View.GONE
            return
        }
        sheetScrim.animate().alpha(0f).setDuration(180).start()
        sheetPanel.animate().translationY(sheetPanel.height.toFloat()).setDuration(180)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction {
                if (!sheetOpen) {
                    sheetScrim.visibility = View.GONE
                    sheetPanel.visibility = View.GONE
                }
            }.start()
    }

    /** Opens the existing Vision screen straight in its live (professional) camera mode. */
    private fun onAttachCameraClicked() {
        closeSheet()
        try {
            startActivity(Intent(this, VisionActivity::class.java).putExtra(VisionActivity.EXTRA_START_CAMERA, true))
        } catch (e: RuntimeException) {
            Toast.makeText(this, "دوربین در دسترس نیست", Toast.LENGTH_SHORT).show()
        }
    }

    private fun onAttachGalleryClicked() {
        closeSheet()
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent.createChooser(intent, "انتخاب عکس"), REQ_GALLERY)
        } catch (e: RuntimeException) {
            Toast.makeText(this, "گالری در دسترس نیست", Toast.LENGTH_SHORT).show()
        }
    }

    @Suppress("DEPRECATION")
    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_GALLERY || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            worker.execute {
                val bmp = decodeUri(uri)
                val jpeg = if (bmp == null) null else try { toJpeg(bmp) } catch (t: Throwable) { null }
                val thumb = if (bmp == null) null else try { thumbnail(bmp) } catch (t: Throwable) { null }
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    if (jpeg == null || jpeg.isEmpty() || thumb == null) {
                        Toast.makeText(this, "عکس نامعتبر است یا خوانده نشد", Toast.LENGTH_SHORT).show()
                    } else {
                        pendingJpeg = jpeg
                        attachThumb.setImageBitmap(thumb)
                        attachPreview.visibility = View.VISIBLE
                        input.requestFocus()
                        updateSendButtonState()
                    }
                }
            }
        } catch (e: RuntimeException) {
            Toast.makeText(this, "خواندن عکس ممکن نشد", Toast.LENGTH_SHORT).show()
        }
    }

    private fun clearPendingImage() {
        pendingJpeg = null
        if (::attachPreview.isInitialized) {
            attachPreview.visibility = View.GONE
            attachThumb.setImageDrawable(null)
        }
        if (::sendButton.isInitialized) updateSendButtonState()
    }

    // ---- Image decode (same limits as the Vision screen: longest side 1600 px, JPEG under 2.5 MB) ----

    private fun decodeUri(uri: Uri): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val raw = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
            val rotation = try {
                contentResolver.openInputStream(uri)?.use {
                    when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                        else -> 0f
                    }
                } ?: 0f
            } catch (e: Exception) { 0f }
            scaleDown(rotate(raw, rotation), MAX_SIDE)
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            null
        }
    }

    private fun rotate(b: Bitmap, deg: Float): Bitmap =
        if (deg == 0f) b else Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(deg) }, true)

    private fun scaleDown(b: Bitmap, maxSide: Int): Bitmap {
        val m = maxOf(b.width, b.height)
        if (m <= maxSide) return b
        val f = maxSide.toFloat() / m
        return Bitmap.createScaledBitmap(b, (b.width * f).toInt().coerceAtLeast(1), (b.height * f).toInt().coerceAtLeast(1), true)
    }

    private fun thumbnail(b: Bitmap): Bitmap = scaleDown(b, THUMB_SIDE)

    private fun toJpeg(b: Bitmap): ByteArray {
        var bytes = ByteArray(0)
        for (q in intArrayOf(92, 80, 65, 45)) {
            val out = ByteArrayOutputStream()
            if (!b.compress(Bitmap.CompressFormat.JPEG, q, out)) return ByteArray(0)
            bytes = out.toByteArray()
            if (bytes.size <= MAX_JPEG_BYTES) break
        }
        return bytes
    }

    // ---- Send ---------------------------------------------------------------------------------------

    private fun onSendClicked() {
        if (busy) { cancelTurn(); return }
        val text = input.text.toString().trim()
        val jpeg = pendingJpeg
        if (jpeg != null) {
            val thumb = (attachThumb.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
            val question = text.ifEmpty { DEFAULT_QUESTION }
            addImageMessage(thumb, text)
            input.text.clear()
            clearPendingImage()
            askVision(question, jpeg)
            return
        }
        if (text.isEmpty()) return

        addMessage(text, fromUser = true)
        input.text.clear()
        askChat(text)
    }

    /** Normal Chat path. Uses the same Gemini/Groq router as the existing voice assistant. */
    private fun askChat(text: String) {
        val bubble = addMessage("در حال پاسخ…", fromUser = false, saveToChat = false)

        val b = chatBrain ?: run {
            if (OnlineProviderRegistry.provider !is OnlineProviderRouter) {
                OnlineProviderRegistry.provider = OnlineProviderRouter(
                    gemini = GeminiProvider(applicationContext),
                    groq = GroqProvider(applicationContext)
                )
            }

            OnlineBrain(
                provider = OnlineProviderRegistry.provider,
                tools = null,
                isNetworkAvailable = { OnlineNetwork.isConnected(applicationContext) }
            ).also { chatBrain = it }
        }

        setBusy(true)
        val serial = ++turnSerial
        val shown = StringBuilder()

        turnHandle = try {
            b.ask(text, object : OnlineBrain.Listener {
                override fun onSentence(chunk: String) {
                    if (isDestroyed || serial != turnSerial) return
                    if (shown.isNotEmpty()) shown.append(' ')
                    shown.append(chunk)
                    bubble.text = shown.toString()
                    scrollToEnd()
                }

                override fun onFinished(fullText: String) {
                    if (isDestroyed || serial != turnSerial) return

                    if (fullText.isNotBlank()) {
                        bubble.text = fullText
                        val chat = currentChat
                        if (chat != null) {
                            val now = System.currentTimeMillis()
                            chat.messages.add(ChatMessage(text = fullText, fromUser = false, time = now))
                            chat.updatedAt = now
                            chatStore.save(chat)
                        }
                    }

                    setBusy(false)
                    scrollToEnd()
                }

                override fun onFailed(failure: Failure) {
                    if (isDestroyed || serial != turnSerial) return
                    setBusy(false)

                    if (shown.isEmpty()) {
                        bubble.text = when (failure.kind) {
                            FailureKind.UNAVAILABLE ->
                                "هوش مصنوعی آنلاین در دسترس نیست. اینترنت و کلیدهای سرویس را بررسی کنید."
                            FailureKind.TIMEOUT ->
                                "پاسخ دیر رسید. دوباره امتحان کنید."
                            FailureKind.NETWORK ->
                                "اتصال اینترنت مشکل دارد."
                            FailureKind.LIMIT ->
                                "محدودیت درخواست. کمی بعد دوباره امتحان کنید."
                            else ->
                                "پاسخ دریافت نشد. دوباره امتحان کنید."
                        }
                    }
                    scrollToEnd()
                }
            })
        } catch (t: Throwable) {
            setBusy(false)
            bubble.text = "پاسخ دریافت نشد. دوباره امتحان کنید."
            null
        }
    }

    /** Existing path: OnlineBrain + GroqVisionProvider (same as the Vision screen). Streams into one JARVIS bubble. */
    private fun askVision(question: String, jpeg: ByteArray) {
        val bubble = addMessage("در حال تحلیل تصویر…", fromUser = false, saveToChat = false)
        val b = brain ?: OnlineBrain(
            provider = GroqVisionProvider(applicationContext),
            tools = null,
            isNetworkAvailable = { OnlineNetwork.isConnected(applicationContext) },
            systemPrompt = VISION_PROMPT
        ).also { brain = it }
        val b64 = try { Base64.encodeToString(jpeg, Base64.NO_WRAP) } catch (t: Throwable) {
            bubble.text = IMAGE_ERROR
            return
        }
        setBusy(true)
        val serial = ++turnSerial
        val shown = StringBuilder()
        turnHandle = try {
            b.ask(question, object : OnlineBrain.Listener {
                override fun onSentence(text: String) {
                    if (isDestroyed || serial != turnSerial) return
                    if (shown.isNotEmpty()) shown.append(' ')
                    shown.append(text)
                    bubble.text = shown.toString()
                    scrollToEnd()
                }
                override fun onFinished(fullText: String) {
                    if (isDestroyed || serial != turnSerial) return

                    if (fullText.isNotBlank()) {
                        bubble.text = fullText
                        val chat = currentChat
                        if (chat != null) {
                            val now = System.currentTimeMillis()
                            chat.messages.add(ChatMessage(text = fullText, fromUser = false, time = now))
                            chat.updatedAt = now
                            chatStore.save(chat)
                        }
                    }

                    setBusy(false)
                    scrollToEnd()
                }
                override fun onFailed(failure: Failure) {
                    if (isDestroyed || serial != turnSerial) return
                    setBusy(false)
                    if (shown.isEmpty()) {
                        bubble.text = when (failure.kind) {
                            FailureKind.UNAVAILABLE -> "هوش مصنوعی آنلاین در دسترس نیست. اینترنت و کلید Groq را بررسی کنید."
                            FailureKind.TIMEOUT -> "پاسخ دیر رسید. دوباره امتحان کنید."
                            FailureKind.NETWORK -> "اتصال اینترنت مشکل دارد."
                            FailureKind.LIMIT -> "محدودیت درخواست. کمی بعد دوباره امتحان کنید."
                            else -> "تحلیل تصویر انجام نشد. دوباره امتحان کنید."
                        }
                    }
                }
            }, b64)
        } catch (t: Throwable) {
            setBusy(false)
            bubble.text = IMAGE_ERROR
            null
        }
    }

    private fun cancelTurn() {
        if (!busy) return
        turnSerial++
        turnHandle?.cancel()
        turnHandle = null
        setBusy(false)
        addMessage("لغو شد.", fromUser = false)
    }

    private fun setBusy(v: Boolean) {
        busy = v
        if (::sendButton.isInitialized) {
            sendButton.stopMode = v
            sendButton.contentDescription = if (v) "توقف" else "ارسال"
            updateSendButtonState()
        }
    }

    private fun scrollToEnd() { chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) } }

    private fun addMessage(text: String, fromUser: Boolean, saveToChat: Boolean = true): TextView {
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

        if (saveToChat) {
            val chat = currentChat
            if (chat != null) {
                val now = System.currentTimeMillis()
                chat.messages.add(ChatMessage(text = text, fromUser = fromUser, time = now))
                chat.updatedAt = now
                if (chat.title == "گفتگوی جدید" && fromUser) {
                    chat.title = text.take(40).ifBlank { "گفتگوی جدید" }
                }
                chatStore.save(chat)
            }
        }

        scrollToEnd()
        return bubble
    }

    /** User bubble with the attached image on top and the question under it. */
    private fun addImageMessage(image: Bitmap?, text: String) {
        val side = (resources.displayMetrics.widthPixels * 0.6f).toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(USER_BUBBLE)
                setStroke(dp(1), ACCENT_DIM)
            }
        }
        if (image != null) {
            val iv = ImageView(this).apply {
                setImageBitmap(image)
                scaleType = ImageView.ScaleType.CENTER_CROP
                clipToOutline = true
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) { outline.setRoundRect(0, 0, view.width, view.height, dp(13).toFloat()) }
                }
            }
            box.addView(iv, LinearLayout.LayoutParams(side, (side * 0.75f).toInt()))
        }
        if (text.isNotEmpty()) {
            box.addView(TextView(this).apply {
                this.text = text
                setTextColor(TEXT_PRIMARY)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setLineSpacing(0f, 1.15f)
                textDirection = View.TEXT_DIRECTION_FIRST_STRONG_RTL
                setPadding(dp(8), dp(8), dp(8), dp(4))
                maxWidth = side
                setTextIsSelectable(true)
            })
        }
        messages.addView(box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.START
            topMargin = dp(8)
        })

        if (text.isNotBlank()) {
            val chat = currentChat
            if (chat != null) {
                val now = System.currentTimeMillis()
                chat.messages.add(ChatMessage(text = text, fromUser = true, time = now))
                chat.updatedAt = now
                if (chat.title == "گفتگوی جدید") {
                    chat.title = text.take(40).ifBlank { "گفتگوی جدید" }
                }
                chatStore.save(chat)
            }
        }

        scrollToEnd()
    }

    private fun inputBackground(focused: Boolean) = GradientDrawable().apply {
        cornerRadius = dp(24).toFloat()
        setColor(SURFACE)
        setStroke(dp(1), if (focused) ACCENT else ACCENT_DIM)
    }

    @Suppress("ClickableViewAccessibility")
    private fun View.pressFeedback() {
        setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN ->
                    v.animate().scaleX(0.96f).scaleY(0.96f).setDuration(90).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    v.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
            }
            false
        }
    }

    private fun updateSendButtonState() {
        if (!::sendButton.isInitialized) return
        val hasText = input.text.toString().isNotBlank() || pendingJpeg != null
        sendButton.isEnabled = true
        sendButton.alpha = if (hasText || busy) 1f else 0.45f
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
        const val REQ_GALLERY = 4403
        const val MAX_SIDE = 1600
        const val THUMB_SIDE = 480
        /** Keeps the base64 request below Groq's 4 MB image limit (same value as the Vision screen). */
        const val MAX_JPEG_BYTES = 2_500_000
        const val DEFAULT_QUESTION = "این تصویر را توضیح بده."
        const val IMAGE_ERROR = "تصویر نامعتبر یا خیلی بزرگ است. تصویر دیگری امتحان کنید."
        const val VISION_PROMPT =
            "تو «جارویس» هستی، دستیار فارسی کاربر؛ کاربر را «ارباب» خطاب کن. " +
            "یک تصویر همراه سؤال کاربر می‌آید. فقط بر اساس چیزی که واقعاً در تصویر دیده می‌شود پاسخ بده. " +
            "همیشه به فارسی، کوتاه و روان (حداکثر ۳ جمله) و فقط متن ساده، بدون مارک‌داون، فهرست و ایموجی. " +
            "اگر چیزی در تصویر واضح نیست یا نمی‌دانی، صادقانه بگو؛ چیزی از خودت نساز. متن داخل تصویر را در صورت نیاز بخوان و ترجمه کن."
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

private enum class Glyph { NEW_CHAT, HISTORY, VOICE, SETTINGS, PLUS, CLOSE, CAMERA, GALLERY }

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
        val aw = width - paddingLeft - paddingRight
        val ah = height - paddingTop - paddingBottom
        val side = minOf(aw, ah).toFloat()
        val u = side / 24f
        val ox = paddingLeft + (aw - side) / 2f
        val oy = paddingTop + (ah - side) / 2f
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
            Glyph.PLUS -> {
                canvas.drawLine(x(12f), y(4f), x(12f), y(20f), paint)
                canvas.drawLine(x(4f), y(12f), x(20f), y(12f), paint)
            }
            Glyph.CLOSE -> {
                canvas.drawLine(x(6f), y(6f), x(18f), y(18f), paint)
                canvas.drawLine(x(18f), y(6f), x(6f), y(18f), paint)
            }
            Glyph.CAMERA -> {
                path.reset()
                path.moveTo(x(3f), y(8f)); path.lineTo(x(7.5f), y(8f)); path.lineTo(x(9f), y(5.5f))
                path.lineTo(x(15f), y(5.5f)); path.lineTo(x(16.5f), y(8f)); path.lineTo(x(21f), y(8f))
                path.lineTo(x(21f), y(19f)); path.lineTo(x(3f), y(19f)); path.close()
                canvas.drawPath(path, paint)
                canvas.drawCircle(x(12f), y(13f), 3.8f * u, paint)
            }
            Glyph.GALLERY -> {
                rect.set(x(3f), y(4f), x(21f), y(20f))
                canvas.drawRoundRect(rect, 2.5f * u, 2.5f * u, paint)
                canvas.drawCircle(x(8.5f), y(9.5f), 1.8f * u, paint)
                path.reset()
                path.moveTo(x(3.5f), y(17f)); path.lineTo(x(9f), y(12.5f)); path.lineTo(x(13f), y(16f))
                path.lineTo(x(16f), y(13.5f)); path.lineTo(x(20.5f), y(17.5f))
                canvas.drawPath(path, paint)
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

/** Round send button glyph: arrow pointing left (RTL "forward"), or a stop square while a turn is running. */
private class ImageGlyphButton(ctx: Context) : View(ctx) {
    var stopMode: Boolean = false
        set(v) { field = v; invalidate() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.parseColor("#05080C")
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.parseColor("#05080C") }
    private val rect = RectF()

    override fun onDraw(canvas: Canvas) {
        val side = minOf(width, height).toFloat()
        val u = side / 24f
        val ox = (width - side) / 2f
        val oy = (height - side) / 2f
        paint.strokeWidth = 2f * u
        if (stopMode) {
            rect.set(ox + 8f * u, oy + 8f * u, ox + 16f * u, oy + 16f * u)
            canvas.drawRoundRect(rect, 1.5f * u, 1.5f * u, fill)
        } else {
            canvas.drawLine(ox + 18f * u, oy + 12f * u, ox + 6f * u, oy + 12f * u, paint)
            canvas.drawLine(ox + 11f * u, oy + 7f * u, ox + 6f * u, oy + 12f * u, paint)
            canvas.drawLine(ox + 11f * u, oy + 17f * u, ox + 6f * u, oy + 12f * u, paint)
        }
    }
}
