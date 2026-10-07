package com.jarvis.assistant.online

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * The smallest possible UI to give JARVIS its Gemini API key: one field, Save / Test / Delete. Built in code (no
 * layout or resource files) and separate from the main screen, so the JARVIS look is untouched.
 *
 * A second section configures Groq (the automatic fallback after Gemini): key, model, Save / Test / Delete.
 *
 * The key goes straight into [GeminiConfigStore] / [GroqConfigStore] (Android Keystore encryption). It is never shown again, never
 * logged, and the window is FLAG_SECURE (no screenshots / recents thumbnail of the field).
 */
class GeminiSetupActivity : Activity() {

    private lateinit var config: GeminiConfigStore
    private lateinit var status: TextView
    private lateinit var keyField: EditText
    private lateinit var modelField: EditText
    private lateinit var result: TextView
    private lateinit var testButton: Button
    private var testHandle: Cancellable? = null

    private lateinit var groqConfig: GroqConfigStore
    private lateinit var groqStatus: TextView
    private lateinit var groqKeyField: EditText
    private lateinit var groqModelField: EditText
    private lateinit var groqResult: TextView
    private lateinit var groqTestButton: Button
    private var groqTestHandle: Cancellable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        config = GeminiConfigStore.get(this)
        groqConfig = GroqConfigStore.get(this)

        val pad = dp(20)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }

        column.addView(label("اتصال به Gemini", 22f, bold = true))
        status = label("", 15f).apply { setPadding(0, dp(8), 0, dp(16)) }
        column.addView(status)

        keyField = EditText(this).apply {
            hint = "کلید API جدید (AI Studio)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setSingleLine(true)
            setTextColor(TEXT)
            setHintTextColor(HINT)
            importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO
            gravity = Gravity.START
        }
        column.addView(keyField)

        column.addView(label("مدل", 13f).apply { setPadding(0, dp(16), 0, 0) })
        modelField = EditText(this).apply {
            hint = GeminiConfigStore.DEFAULT_MODEL
            setText(config.getModel())
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setSingleLine(true)
            setTextColor(TEXT)
            setHintTextColor(HINT)
            importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO
            gravity = Gravity.START
        }
        column.addView(modelField)

        val saveButton = Button(this).apply { text = "ذخیره"; setOnClickListener { onSave() } }
        testButton = Button(this).apply { text = "تست اتصال"; setOnClickListener { onTest() } }
        val deleteButton = Button(this).apply { text = "حذف کلید"; setOnClickListener { onDelete() } }
        column.addView(saveButton)
        column.addView(testButton)
        column.addView(deleteButton)

        result = label("", 14f).apply { setPadding(0, dp(12), 0, 0) }
        column.addView(result)

        // ---- Groq (fallback after Gemini) ----
        column.addView(label("اتصال به Groq (پشتیبان خودکار)", 22f, bold = true).apply { setPadding(0, dp(32), 0, 0) })
        groqStatus = label("", 15f).apply { setPadding(0, dp(8), 0, dp(16)) }
        column.addView(groqStatus)

        groqKeyField = EditText(this).apply {
            hint = "کلید API جدید (Groq Console)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setSingleLine(true)
            setTextColor(TEXT)
            setHintTextColor(HINT)
            importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO
            gravity = Gravity.START
        }
        column.addView(groqKeyField)

        column.addView(label("مدل Groq", 13f).apply { setPadding(0, dp(16), 0, 0) })
        groqModelField = EditText(this).apply {
            hint = GroqConfigStore.DEFAULT_MODEL
            setText(groqConfig.getModel())
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setSingleLine(true)
            setTextColor(TEXT)
            setHintTextColor(HINT)
            importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO
            gravity = Gravity.START
        }
        column.addView(groqModelField)

        val groqSave = Button(this).apply { text = "ذخیره Groq"; setOnClickListener { onGroqSave() } }
        groqTestButton = Button(this).apply { text = "تست اتصال Groq"; setOnClickListener { onGroqTest() } }
        val groqDelete = Button(this).apply { text = "حذف کلید Groq"; setOnClickListener { onGroqDelete() } }
        column.addView(groqSave)
        column.addView(groqTestButton)
        column.addView(groqDelete)

        groqResult = label("", 14f).apply { setPadding(0, dp(12), 0, 0) }
        column.addView(groqResult)

        setContentView(ScrollView(this).apply {
            setBackgroundColor(BACKGROUND)
            isFillViewport = true
            addView(column)
        })
        refreshStatus()
        refreshGroqStatus()
    }

    override fun onDestroy() {
        testHandle?.cancel()
        testHandle = null
        groqTestHandle?.cancel()
        groqTestHandle = null
        super.onDestroy()
    }

    private fun onSave() {
        val newKey = keyField.text.toString().trim()
        if (!config.setModel(modelField.text.toString())) {
            result.text = "نام مدل نامعتبر است."
            return
        }
        if (newKey.isNotEmpty()) {
            if (!config.saveApiKey(newKey)) {
                result.text = "کلید ذخیره نشد؛ کلید نامعتبر است یا ذخیره‌ی امن در این دستگاه ممکن نیست."
                return
            }
            keyField.setText("")
        }
        modelField.setText(config.getModel())
        result.text = "ذخیره شد."
        refreshStatus()
    }

    private fun onDelete() {
        testHandle?.cancel()
        config.clearApiKey()
        keyField.setText("")
        result.text = "کلید حذف شد؛ جارویس فقط آفلاین کار می‌کند."
        refreshStatus()
    }

    private fun onTest() {
        if (!config.hasApiKey()) {
            result.text = "ابتدا یک کلید ذخیره کن."
            return
        }
        testHandle?.cancel()
        // Test Gemini ALONE (not the router), so a fallback to Groq can never hide a Gemini problem.
        val provider = GeminiProvider(applicationContext)
        testButton.isEnabled = false
        result.text = "در حال تست…"
        var gotText = false
        val request = ChatRequest(
            systemPrompt = "Reply with the single word OK.",
            messages = listOf(ChatMessage(ChatRole.USER, "Say OK.")),
            tools = emptyList()
        )
        testHandle = provider.stream(request, StreamListener { event ->
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                when (event) {
                    is StreamEvent.TextDelta -> { gotText = true }
                    is StreamEvent.ToolCallRequest -> Unit
                    is StreamEvent.Finished -> {
                        result.text = if (gotText) "اتصال موفق بود." else "پاسخی دریافت نشد."
                        testButton.isEnabled = true
                    }
                    is StreamEvent.Error -> {
                        result.text = "خطا: ${event.kind}${event.detail?.let { " ($it)" } ?: ""}"
                        testButton.isEnabled = true
                    }
                }
            }
        })
    }

    private fun refreshStatus() {
        status.text = if (config.hasApiKey()) "کلید ذخیره شده است. مدل: ${config.getModel()}" else "کلیدی ذخیره نشده است."
    }

    // ---- Groq ------------------------------------------------------------------------------------

    private fun onGroqSave() {
        val newKey = groqKeyField.text.toString().trim()
        if (!groqConfig.setModel(groqModelField.text.toString())) {
            groqResult.text = "نام مدل نامعتبر است."
            return
        }
        if (newKey.isNotEmpty()) {
            if (!groqConfig.saveApiKey(newKey)) {
                groqResult.text = "کلید ذخیره نشد؛ کلید نامعتبر است یا ذخیره‌ی امن در این دستگاه ممکن نیست."
                return
            }
            groqKeyField.setText("")
        }
        groqModelField.setText(groqConfig.getModel())
        groqResult.text = "ذخیره شد."
        refreshGroqStatus()
    }

    private fun onGroqDelete() {
        groqTestHandle?.cancel()
        groqConfig.clearApiKey()
        groqKeyField.setText("")
        groqResult.text = "کلید Groq حذف شد."
        refreshGroqStatus()
    }

    private fun onGroqTest() {
        if (!groqConfig.hasApiKey()) {
            groqResult.text = "ابتدا یک کلید Groq ذخیره کن."
            return
        }
        groqTestHandle?.cancel()
        // Groq ALONE (not the router): the test must show Groq's own state.
        val provider = GroqProvider(applicationContext)
        groqTestButton.isEnabled = false
        groqResult.text = "در حال تست…"
        var gotText = false
        val request = ChatRequest(
            systemPrompt = "Reply with the single word OK.",
            messages = listOf(ChatMessage(ChatRole.USER, "Say OK.")),
            tools = emptyList()
        )
        groqTestHandle = provider.stream(request, StreamListener { event ->
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                when (event) {
                    is StreamEvent.TextDelta -> { gotText = true }
                    is StreamEvent.ToolCallRequest -> Unit
                    is StreamEvent.Finished -> {
                        groqResult.text = if (gotText) "اتصال Groq موفق بود." else "پاسخی دریافت نشد."
                        groqTestButton.isEnabled = true
                    }
                    is StreamEvent.Error -> {
                        groqResult.text = "خطا: ${event.kind}${event.detail?.let { " ($it)" } ?: ""}"
                        groqTestButton.isEnabled = true
                    }
                }
            }
        })
    }

    private fun refreshGroqStatus() {
        groqStatus.text =
            if (groqConfig.hasApiKey()) "کلید ذخیره شده است. مدل: ${groqConfig.getModel()}" else "کلیدی ذخیره نشده است."
    }

    private fun label(text: String, sizeSp: Float, bold: Boolean = false) = TextView(this).apply {
        this.text = text
        textSize = sizeSp
        setTextColor(TEXT)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        val BACKGROUND: Int = Color.parseColor("#0B1620")
        val TEXT: Int = Color.parseColor("#E6F1F5")
        val HINT: Int = Color.parseColor("#7C909A")
    }
}
