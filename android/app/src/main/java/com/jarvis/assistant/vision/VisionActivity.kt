package com.jarvis.assistant.vision

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ExifInterface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.text.InputType
import android.util.Base64
import android.util.Log
import android.util.Size
import android.util.TypedValue
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.jarvis.assistant.online.Cancellable
import com.jarvis.assistant.online.Failure
import com.jarvis.assistant.online.FailureKind
import com.jarvis.assistant.online.GeminiProvider
import com.jarvis.assistant.online.OnlineBrain
import com.jarvis.assistant.online.OnlineNetwork
import com.jarvis.assistant.speech.JarvisSpeechController
import com.jarvis.assistant.speech.tts.OfflinePersianTts
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

/**
 * Vision screen: pick a photo from the gallery or watch the real live camera preview (Camera2 + TextureView),
 * ask a question (typed) about the photo / the current live frame, and get a Persian answer from the existing
 * Gemini provider through the existing [OnlineBrain] (no tools). The answer is spoken with the existing offline
 * Persian TTS ([OfflinePersianTts]). Every question sends the CURRENT live frame, so the camera view is analysed.
 */
class VisionActivity : Activity() {

    private enum class Mode { NONE, GALLERY, CAMERA }

    private lateinit var previewBox: FrameLayout
    private lateinit var textureView: TextureView
    private lateinit var imageView: ImageView
    private lateinit var hint: TextView
    private lateinit var answerText: TextView
    private lateinit var answerScroll: ScrollView
    private lateinit var input: EditText
    private lateinit var sendButton: TextView
    private lateinit var cameraButton: TextView

    private var mode = Mode.NONE
    private var galleryJpeg: ByteArray? = null

    private var brain: OnlineBrain? = null
    private var handle: Cancellable? = null
    private var busy = false
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "jarvis-vision-prep").apply { isDaemon = true } }

    // TTS (sentence queue, never two speak() at once)
    private var tts: OfflinePersianTts? = null
    private val speakQueue = ArrayDeque<String>()
    private var speaking = false
    private var turnSerial = 0

    // Camera2
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var cameraDevice: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var opening = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        run { window.statusBarColor = BG; window.navigationBarColor = BG }
        setContentView(buildUi())
        brain = OnlineBrain(
            provider = GeminiProvider(applicationContext),
            tools = null,
            isNetworkAvailable = { OnlineNetwork.isConnected(applicationContext) },
            systemPrompt = VISION_PROMPT
        )
    }

    override fun onResume() {
        super.onResume()
        if (mode == Mode.CAMERA) startCamera()
    }

    override fun onPause() {
        stopCamera()
        super.onPause()
    }

    override fun onDestroy() {
        handle?.cancel()
        brain?.release(); brain = null
        stopSpeaking()
        tts?.release(); tts = null
        worker.shutdownNow()
        super.onDestroy()
    }

    // ---- gallery ---------------------------------------------------------------------------------

    private fun onGalleryClicked() {
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

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_GALLERY || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        worker.execute {
            val bmp = decodeUri(uri)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (bmp == null) {
                    Toast.makeText(this, "خواندن عکس ممکن نشد", Toast.LENGTH_SHORT).show()
                } else {
                    stopCamera()
                    galleryJpeg = toJpeg(bmp)
                    imageView.setImageBitmap(bmp)
                    setMode(Mode.GALLERY)
                    answerText.text = "عکس انتخاب شد. سؤالت را بنویس و ارسال کن."
                }
            }
        }
    }

    private fun decodeUri(uri: Uri): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val raw = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
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
        if (raw == null) null else scaleDown(rotate(raw, rotation))
    } catch (e: Exception) {
        Log.w(TAG, "decode failed")
        null
    } catch (e: OutOfMemoryError) {
        null
    }

    private fun rotate(b: Bitmap, deg: Float): Bitmap =
        if (deg == 0f) b else Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(deg) }, true)

    private fun scaleDown(b: Bitmap): Bitmap {
        val m = maxOf(b.width, b.height)
        if (m <= MAX_SIDE) return b
        val f = MAX_SIDE.toFloat() / m
        return Bitmap.createScaledBitmap(b, (b.width * f).toInt().coerceAtLeast(1), (b.height * f).toInt().coerceAtLeast(1), true)
    }

    private fun toJpeg(b: Bitmap): ByteArray {
        val out = ByteArrayOutputStream()
        scaleDown(b).compress(Bitmap.CompressFormat.JPEG, 85, out)
        return out.toByteArray()
    }

    // ---- live camera (Camera2) -------------------------------------------------------------------

    private fun onCameraClicked() {
        if (mode == Mode.CAMERA) {
            stopCamera()
            setMode(if (galleryJpeg != null) Mode.GALLERY else Mode.NONE)
            return
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
            return
        }
        setMode(Mode.CAMERA)
        startCamera()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_CAMERA) return
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            setMode(Mode.CAMERA)
            startCamera()
        } else {
            Toast.makeText(this, "اجازه دوربین داده نشد. از تنظیمات برنامه فعال کنید", Toast.LENGTH_LONG).show()
        }
    }

    private fun startCamera() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        if (cameraDevice != null || opening) return
        if (cameraThread == null) {
            cameraThread = HandlerThread("jarvis-camera").also { it.start() }
            cameraHandler = Handler(cameraThread!!.looper)
        }
        if (textureView.isAvailable) openCamera()
        else textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) { if (mode == Mode.CAMERA) openCamera() }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = Unit
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean = true
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
        }
    }

    @Suppress("MissingPermission", "DEPRECATION")
    private fun openCamera() {
        if (opening || cameraDevice != null) return
        try {
            val mgr = getSystemService(CAMERA_SERVICE) as CameraManager
            val id = mgr.cameraIdList.firstOrNull {
                mgr.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: mgr.cameraIdList.firstOrNull() ?: run { cameraError(); return }
            val chars = mgr.getCameraCharacteristics(id)
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: run { cameraError(); return }
            val size = map.getOutputSizes(SurfaceTexture::class.java)
                ?.filter { maxOf(it.width, it.height) <= 1600 }
                ?.maxByOrNull { it.width.toLong() * it.height }
                ?: Size(1280, 720)
            val sensor = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            fitPreview(size, sensor)
            opening = true
            mgr.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    opening = false
                    if (mode != Mode.CAMERA) { camera.close(); return }
                    cameraDevice = camera
                    createSession(camera, size)
                }
                override fun onDisconnected(camera: CameraDevice) { opening = false; camera.close(); cameraDevice = null }
                override fun onError(camera: CameraDevice, error: Int) {
                    opening = false; camera.close(); cameraDevice = null
                    runOnUiThread { cameraError() }
                }
            }, cameraHandler)
        } catch (e: Exception) {
            opening = false
            cameraError()
        }
    }

    /** Portrait only: the buffer is landscape, the displayed picture is rotated by the sensor orientation. */
    private fun fitPreview(size: Size, sensor: Int) {
        val rotated = sensor == 90 || sensor == 270
        val dispW = if (rotated) size.height else size.width
        val dispH = if (rotated) size.width else size.height
        runOnUiThread {
            val bw = previewBox.width.takeIf { it > 0 } ?: return@runOnUiThread
            val bh = previewBox.height.takeIf { it > 0 } ?: return@runOnUiThread
            val scale = minOf(bw.toFloat() / dispW, bh.toFloat() / dispH)
            val lp = FrameLayout.LayoutParams((dispW * scale).toInt(), (dispH * scale).toInt(), Gravity.CENTER)
            textureView.layoutParams = lp
        }
    }

    @Suppress("DEPRECATION")
    private fun createSession(camera: CameraDevice, size: Size) {
        try {
            val st = textureView.surfaceTexture ?: return
            st.setDefaultBufferSize(size.width, size.height)
            val surface = Surface(st)
            val req = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            }
            camera.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    if (cameraDevice == null) { s.close(); return }
                    session = s
                    try { s.setRepeatingRequest(req.build(), null, cameraHandler) } catch (e: Exception) { runOnUiThread { cameraError() } }
                }
                override fun onConfigureFailed(s: CameraCaptureSession) { runOnUiThread { cameraError() } }
            }, cameraHandler)
        } catch (e: Exception) {
            runOnUiThread { cameraError() }
        }
    }

    private fun stopCamera() {
        try { session?.close() } catch (e: Exception) { /* ignore */ }
        session = null
        try { cameraDevice?.close() } catch (e: Exception) { /* ignore */ }
        cameraDevice = null
        opening = false
        cameraThread?.quitSafely()
        cameraThread = null
        cameraHandler = null
    }

    private fun cameraError() {
        stopCamera()
        setMode(if (galleryJpeg != null) Mode.GALLERY else Mode.NONE)
        Toast.makeText(this, "دوربین در دسترس نیست", Toast.LENGTH_LONG).show()
    }

    // ---- ask ---------------------------------------------------------------------------------------

    private fun onSendClicked() {
        if (busy) { cancelTurn(); return }
        val question = input.text.toString().trim().ifEmpty { DEFAULT_QUESTION }
        when (mode) {
            Mode.NONE -> { Toast.makeText(this, "اول یک عکس انتخاب کن یا دوربین را روشن کن", Toast.LENGTH_SHORT).show(); return }
            Mode.GALLERY -> { val j = galleryJpeg ?: return; ask(question, j) }
            Mode.CAMERA -> {
                val frame = textureView.bitmap
                if (frame == null) { Toast.makeText(this, "تصویر دوربین هنوز آماده نیست", Toast.LENGTH_SHORT).show(); return }
                setBusy(true)
                worker.execute {
                    val jpeg = toJpeg(frame)
                    runOnUiThread { if (!isDestroyed) ask(question, jpeg) }
                }
            }
        }
    }

    private fun ask(question: String, jpeg: ByteArray) {
        val b = brain ?: return
        setBusy(true)
        stopSpeaking()
        val serial = ++turnSerial
        val shown = StringBuilder()
        answerText.text = "در حال تحلیل…"
        val b64 = Base64.encodeToString(jpeg, Base64.NO_WRAP)
        handle = b.ask(question, object : OnlineBrain.Listener {
            override fun onSentence(text: String) {
                if (serial != turnSerial) return
                if (shown.isNotEmpty()) shown.append(' ')
                shown.append(text)
                answerText.text = shown.toString()
                answerScroll.post { answerScroll.fullScroll(View.FOCUS_DOWN) }
                speakQueue.addLast(text)
                playNext()
            }
            override fun onFinished(fullText: String) {
                if (serial != turnSerial) return
                answerText.text = fullText
                setBusy(false)
            }
            override fun onFailed(failure: Failure) {
                if (serial != turnSerial) return
                setBusy(false)
                if (shown.isEmpty()) {
                    val msg = when (failure.kind) {
                        FailureKind.UNAVAILABLE -> "هوش مصنوعی آنلاین در دسترس نیست. اینترنت و کلید Gemini را بررسی کن."
                        FailureKind.TIMEOUT -> "پاسخ دیر رسید. دوباره امتحان کن."
                        FailureKind.NETWORK -> "اتصال اینترنت مشکل دارد."
                        FailureKind.LIMIT -> "محدودیت درخواست. کمی بعد دوباره امتحان کن."
                        else -> "تحلیل تصویر انجام نشد. دوباره امتحان کن."
                    }
                    answerText.text = msg
                }
            }
        }, b64)
    }

    private fun cancelTurn() {
        turnSerial++
        handle?.cancel()
        stopSpeaking()
        setBusy(false)
        answerText.text = "لغو شد."
    }

    private fun setBusy(v: Boolean) {
        busy = v
        sendButton.text = if (v) "توقف" else "ارسال"
    }

    // ---- TTS ---------------------------------------------------------------------------------------

    private fun playNext() {
        if (speaking) return
        val next = speakQueue.removeFirstOrNull() ?: return
        val engine = tts ?: OfflinePersianTts(this).also { tts = it; it.initialize() }
        speaking = true
        val serial = turnSerial
        try {
            engine.speak(next, object : JarvisSpeechController.Callback {
                override fun onStart() = Unit
                override fun onDone(success: Boolean) {
                    runOnUiThread {
                        if (serial != turnSerial) return@runOnUiThread
                        speaking = false
                        playNext()
                    }
                }
            })
        } catch (t: Throwable) {
            Log.w(TAG, "tts failed")
            speaking = false
            speakQueue.clear()
        }
    }

    private fun stopSpeaking() {
        speakQueue.clear()
        speaking = false
        try { tts?.stop() } catch (t: Throwable) { /* ignore */ }
    }

    // ---- UI ----------------------------------------------------------------------------------------

    private fun setMode(m: Mode) {
        mode = m
        textureView.visibility = if (m == Mode.CAMERA) View.VISIBLE else View.GONE
        imageView.visibility = if (m == Mode.GALLERY) View.VISIBLE else View.GONE
        hint.visibility = if (m == Mode.NONE) View.VISIBLE else View.GONE
        cameraButton.text = if (m == Mode.CAMERA) "⏹ بستن دوربین" else "📷 دوربین زنده"
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        previewBox = FrameLayout(this).apply { setBackgroundColor(Color.parseColor("#0B1219")) }
        textureView = TextureView(this).apply { visibility = View.GONE }
        imageView = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER; visibility = View.GONE }
        hint = TextView(this).apply {
            text = "یک عکس از گالری انتخاب کن یا دوربین زنده را روشن کن"
            setTextColor(TEXT_SECONDARY); gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        }
        previewBox.addView(textureView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        previewBox.addView(imageView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        previewBox.addView(hint, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(previewBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 3f))

        answerText = TextView(this).apply {
            setTextColor(TEXT_PRIMARY); setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(dp(4), dp(8), dp(4), dp(8)); setTextIsSelectable(true)
        }
        answerScroll = ScrollView(this).apply { addView(answerText) }
        root.addView(answerScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 2f))

        input = EditText(this).apply {
            hint = "درباره تصویر بپرس…"
            setHintTextColor(TEXT_SECONDARY); setTextColor(TEXT_PRIMARY)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            maxLines = 3
        }
        root.addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val gallery = button("🖼️ گالری") { onGalleryClicked() }
        cameraButton = button("📷 دوربین زنده") { onCameraClicked() }
        sendButton = button("ارسال") { onSendClicked() }
        for (b in listOf(gallery, cameraButton, sendButton)) {
            row.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(4), dp(8), dp(4), 0) })
        }
        root.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return root
    }

    private fun button(label: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        setTextColor(ACCENT); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        gravity = Gravity.CENTER
        setPadding(dp(8), dp(14), dp(8), dp(14))
        background = GradientDrawable().apply { cornerRadius = dp(24).toFloat(); setColor(Color.TRANSPARENT); setStroke(dp(1), ACCENT_DIM) }
        isClickable = true; isFocusable = true
        setOnClickListener { onClick() }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    private companion object {
        const val TAG = "VisionActivity"
        const val REQ_GALLERY = 4501
        const val REQ_CAMERA = 4502
        const val MAX_SIDE = 1280
        const val DEFAULT_QUESTION = "این تصویر را توضیح بده."
        val BG = Color.parseColor("#05080C")
        val TEXT_PRIMARY = Color.parseColor("#E6F6FF")
        val TEXT_SECONDARY = Color.parseColor("#8FA3B0")
        val ACCENT = Color.parseColor("#5FD8FF")
        val ACCENT_DIM = Color.parseColor("#335FD8FF")

        const val VISION_PROMPT =
            "تو «جارویس» هستی، دستیار فارسی کاربر؛ کاربر را «ارباب» خطاب کن. " +
            "یک تصویر (عکس یا فریم زنده‌ی دوربین) همراه سؤال کاربر می‌آید. فقط بر اساس چیزی که واقعاً در تصویر دیده می‌شود پاسخ بده. " +
            "همیشه به فارسی، کوتاه و روان (حداکثر ۳ جمله) و فقط متن ساده، بدون مارک‌داون، فهرست و ایموجی، چون با صدا خوانده می‌شود. " +
            "اگر چیزی در تصویر واضح نیست یا نمی‌دانی، صادقانه بگو؛ چیزی از خودت نساز. متن داخل تصویر را در صورت نیاز بخوان و ترجمه کن."
    }
}
