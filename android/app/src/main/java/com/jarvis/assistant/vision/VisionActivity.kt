package com.jarvis.assistant.vision

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.ExifInterface
import android.media.ImageReader
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.HandlerThread
import android.os.SystemClock
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
import com.jarvis.assistant.online.GroqVisionProvider
import com.jarvis.assistant.online.OnlineBrain
import com.jarvis.assistant.online.OnlineNetwork
import com.jarvis.assistant.speech.JarvisSpeechController
import com.jarvis.assistant.speech.tts.OfflinePersianTts
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

/**
 * Vision screen: pick a photo from the gallery or watch the real live camera preview (Camera2 + TextureView),
 * ask a question (typed) about the photo / the current live frame, and get a Persian answer from the existing
 * Groq Vision provider through the existing [OnlineBrain] (no tools). The answer is spoken with the existing offline
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
    private val mainHandler = Handler(Looper.getMainLooper())
    private val ttsWatchdog = Runnable {
        // speak() never reported back: unstick the queue instead of freezing the conversation
        speaking = false
        playNext()
    }

    // Camera2
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var cameraDevice: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var opening = false
    private var reader: ImageReader? = null
    private var previewBuilder: CaptureRequest.Builder? = null
    private var sensorOrientation = 90
    private var afMode = CaptureRequest.CONTROL_AF_MODE_OFF

    // still capture state machine (real full-resolution JPEG, after AF + AE converge)
    @Volatile private var stillState = STILL_IDLE
    @Volatile private var stillCallback: ((ByteArray?) -> Unit)? = null
    @Volatile private var stillStart = 0L
    private val stillTimeout = Runnable { finishStill(null) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        run { window.statusBarColor = BG; window.navigationBarColor = BG }
        setContentView(buildUi())
        brain = OnlineBrain(
            provider = GroqVisionProvider(applicationContext),
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
        mainHandler.removeCallbacks(ttsWatchdog)
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
        try {
            worker.execute {
                val bmp = decodeUri(uri)
                val jpeg = if (bmp == null) null else try { toJpeg(bmp) } catch (t: Throwable) { null }
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    if (bmp == null || jpeg == null || jpeg.isEmpty()) {
                        Toast.makeText(this, "عکس نامعتبر است یا خوانده نشد", Toast.LENGTH_SHORT).show()
                    } else {
                        stopCamera()
                        galleryJpeg = jpeg
                        imageView.setImageBitmap(bmp)
                        setMode(Mode.GALLERY)
                        answerText.text = "عکس انتخاب شد. سؤالت را بنویس و ارسال کن."
                    }
                }
            }
        } catch (e: RuntimeException) {
            Toast.makeText(this, "خواندن عکس ممکن نشد", Toast.LENGTH_SHORT).show()
        }
    }

    private fun decodeUri(uri: Uri): Bitmap? {
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
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
        val scaled = scaleDown(b)
        var bytes = ByteArray(0)
        for (q in intArrayOf(92, 80, 65, 45)) {
            val out = ByteArrayOutputStream()
            if (!scaled.compress(Bitmap.CompressFormat.JPEG, q, out)) return ByteArray(0)
            bytes = out.toByteArray()
            if (bytes.size <= MAX_JPEG_BYTES) break
        }
        return bytes
    }

    /** Decodes a captured camera JPEG, applies its EXIF orientation (aspect preserved), scales down if needed. */
    private fun decodeCapture(data: ByteArray): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
            val raw = BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: return null
            val rotation = try {
                when (ExifInterface(ByteArrayInputStream(data)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } catch (e: Exception) { 0f }
            scaleDown(rotate(raw, rotation))
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            null
        }
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
            // Still size: largest JPEG (capped); preview size: same aspect ratio so preview/photo match, no stretching.
            val jpegAll = map.getOutputSizes(ImageFormat.JPEG)
            val jpegSize = jpegAll?.filter { maxOf(it.width, it.height) <= 4096 }?.maxByOrNull { it.width.toLong() * it.height }
                ?: jpegAll?.minByOrNull { it.width.toLong() * it.height }
                ?: run { cameraError(); return }
            val ratio = jpegSize.width.toFloat() / jpegSize.height
            val previews = (map.getOutputSizes(SurfaceTexture::class.java) ?: emptyArray())
                .filter { maxOf(it.width, it.height) <= 1920 }
            val size = previews.filter { Math.abs(it.width.toFloat() / it.height - ratio) < 0.02f }
                .maxByOrNull { it.width.toLong() * it.height }
                ?: previews.minByOrNull { Math.abs(it.width.toFloat() / it.height - ratio) }
                ?: Size(1280, 720)
            val sensor = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            sensorOrientation = sensor
            val afModes = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: IntArray(0)
            afMode = when {
                afModes.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE) -> CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                afModes.contains(CaptureRequest.CONTROL_AF_MODE_AUTO) -> CaptureRequest.CONTROL_AF_MODE_AUTO
                else -> CaptureRequest.CONTROL_AF_MODE_OFF
            }
            fitPreview(size, sensor)
            opening = true
            mgr.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    opening = false
                    if (mode != Mode.CAMERA) { camera.close(); return }
                    cameraDevice = camera
                    createSession(camera, size, jpegSize)
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
        val rotated = (sensor - displayDegrees() + 360) % 180 == 90
        val dispW = if (rotated) size.height else size.width
        val dispH = if (rotated) size.width else size.height
        runOnUiThread {
            val bw = previewBox.width.takeIf { it > 0 } ?: run { previewBox.post { fitPreview(size, sensor) }; return@runOnUiThread }
            val bh = previewBox.height.takeIf { it > 0 } ?: run { previewBox.post { fitPreview(size, sensor) }; return@runOnUiThread }
            val scale = minOf(bw.toFloat() / dispW, bh.toFloat() / dispH)
            val lp = FrameLayout.LayoutParams((dispW * scale).toInt(), (dispH * scale).toInt(), Gravity.CENTER)
            textureView.layoutParams = lp
        }
    }

    @Suppress("DEPRECATION")
    private fun createSession(camera: CameraDevice, size: Size, jpegSize: Size) {
        try {
            val st = textureView.surfaceTexture ?: return
            st.setDefaultBufferSize(size.width, size.height)
            val surface = Surface(st)
            val ir = ImageReader.newInstance(jpegSize.width, jpegSize.height, ImageFormat.JPEG, 2)
            ir.setOnImageAvailableListener({ r ->
                val img = try { r.acquireLatestImage() } catch (e: Exception) { null } ?: return@setOnImageAvailableListener
                val bytes = try {
                    val buf = img.planes[0].buffer
                    ByteArray(buf.remaining()).also { buf.get(it) }
                } catch (e: Exception) { null } finally { img.close() }
                if (stillState == STILL_SHOOT) finishStill(bytes)
            }, cameraHandler)
            reader = ir
            val req = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AF_MODE, afMode)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
            }
            previewBuilder = req
            camera.createCaptureSession(listOf(surface, ir.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    if (cameraDevice == null) { s.close(); return }
                    session = s
                    try { s.setRepeatingRequest(req.build(), previewCallback, cameraHandler) } catch (e: Exception) { runOnUiThread { cameraError() } }
                }
                override fun onConfigureFailed(s: CameraCaptureSession) { runOnUiThread { cameraError() } }
            }, cameraHandler)
        } catch (e: Exception) {
            runOnUiThread { cameraError() }
        }
    }

    private val previewCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            if (stillState != STILL_WAIT) return
            val elapsed = SystemClock.uptimeMillis() - stillStart
            if (elapsed < 350) return
            val af = result.get(CaptureResult.CONTROL_AF_STATE)
            val ae = result.get(CaptureResult.CONTROL_AE_STATE)
            val afOk = afMode == CaptureRequest.CONTROL_AF_MODE_OFF || af == null ||
                af == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED || af == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED
            val aeOk = ae == null || ae == CaptureResult.CONTROL_AE_STATE_CONVERGED ||
                ae == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED || ae == CaptureResult.CONTROL_AE_STATE_LOCKED
            if ((afOk && aeOk) || elapsed > 2500) shoot()
        }
    }

    /** Runs AF + AE metering, then takes a real full-resolution JPEG. [cb] gets the JPEG bytes or null. */
    private fun captureStill(cb: (ByteArray?) -> Unit) {
        val s = session
        val b = previewBuilder
        val h = cameraHandler
        if (s == null || b == null || h == null) { cb(null); return }
        stillCallback = cb
        stillStart = SystemClock.uptimeMillis()
        stillState = STILL_WAIT
        mainHandler.removeCallbacks(stillTimeout)
        mainHandler.postDelayed(stillTimeout, STILL_TIMEOUT_MS)
        h.post {
            try {
                if (afMode != CaptureRequest.CONTROL_AF_MODE_OFF) b.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
                b.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START)
                s.capture(b.build(), previewCallback, h)
                b.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
                b.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE)
            } catch (e: Exception) {
                finishStill(null)
            }
        }
    }

    private fun shoot() {
        if (stillState != STILL_WAIT) return
        stillState = STILL_SHOOT
        val s = session
        val cam = cameraDevice
        val ir = reader
        val h = cameraHandler
        if (s == null || cam == null || ir == null || h == null) { finishStill(null); return }
        try {
            val still = cam.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(ir.surface)
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AF_MODE, afMode)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                set(CaptureRequest.JPEG_QUALITY, 95.toByte())
                set(CaptureRequest.JPEG_ORIENTATION, (sensorOrientation - displayDegrees() + 360) % 360)
            }
            s.capture(still.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(ss: CameraCaptureSession, r: CaptureRequest, res: TotalCaptureResult) = resumePreview()
                override fun onCaptureFailed(ss: CameraCaptureSession, r: CaptureRequest, f: android.hardware.camera2.CaptureFailure) {
                    finishStill(null)
                }
            }, h)
        } catch (e: Exception) {
            finishStill(null)
        }
    }

    /** Releases the AF lock after the shot and restarts the normal continuous preview. */
    private fun resumePreview() {
        val s = session ?: return
        val b = previewBuilder ?: return
        try {
            b.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL)
            s.capture(b.build(), null, cameraHandler)
            b.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
            s.setRepeatingRequest(b.build(), previewCallback, cameraHandler)
        } catch (e: Exception) { /* ignore */ }
    }

    private fun finishStill(bytes: ByteArray?) {
        val cb = stillCallback
        stillCallback = null
        stillState = STILL_IDLE
        mainHandler.removeCallbacks(stillTimeout)
        if (bytes == null) resumePreview()
        cb?.invoke(bytes)
    }

    @Suppress("DEPRECATION")
    private fun displayDegrees(): Int = when (windowManager.defaultDisplay.rotation) {
        Surface.ROTATION_90 -> 90
        Surface.ROTATION_180 -> 180
        Surface.ROTATION_270 -> 270
        else -> 0
    }

    private fun stopCamera() {
        if (stillCallback != null) finishStill(null)
        try { session?.close() } catch (e: Exception) { /* ignore */ }
        session = null
        try { cameraDevice?.close() } catch (e: Exception) { /* ignore */ }
        cameraDevice = null
        try { reader?.close() } catch (e: Exception) { /* ignore */ }
        reader = null
        previewBuilder = null
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
                setBusy(true)
                stopSpeaking()
                answerText.text = "در حال عکس گرفتن…"
                val serial = ++turnSerial
                captureStill { data ->
                    if (data == null) {
                        runOnUiThread { if (!isDestroyed && serial == turnSerial) showFailure(IMAGE_ERROR) }
                        return@captureStill
                    }
                    try {
                        worker.execute {
                            val bmp = decodeCapture(data)
                            val jpeg = if (bmp == null) ByteArray(0) else try { toJpeg(bmp) } catch (t: Throwable) { ByteArray(0) }
                            runOnUiThread {
                                if (isDestroyed || serial != turnSerial) return@runOnUiThread
                                if (jpeg.isEmpty()) showFailure(IMAGE_ERROR) else ask(question, jpeg)
                            }
                        }
                    } catch (e: RuntimeException) {
                        runOnUiThread { if (!isDestroyed && serial == turnSerial) showFailure(IMAGE_ERROR) }
                    }
                }
            }
        }
    }

    private fun ask(question: String, jpeg: ByteArray) {
        val b = brain ?: run { setBusy(false); return }
        if (jpeg.isEmpty()) { showFailure(IMAGE_ERROR); return }
        setBusy(true)
        stopSpeaking()
        val serial = ++turnSerial
        val shown = StringBuilder()
        answerText.text = "در حال تحلیل…"
        val b64 = try { Base64.encodeToString(jpeg, Base64.NO_WRAP) } catch (t: Throwable) { showFailure(IMAGE_ERROR); return }
        handle = try {
            b.ask(question, object : OnlineBrain.Listener {
                override fun onSentence(text: String) {
                    runOnUiThread {
                        if (isDestroyed || serial != turnSerial) return@runOnUiThread
                        if (shown.isNotEmpty()) shown.append(' ')
                        shown.append(text)
                        answerText.text = shown.toString()
                        answerScroll.post { answerScroll.fullScroll(View.FOCUS_DOWN) }
                        speakQueue.addLast(text)
                        playNext()
                    }
                }
                override fun onFinished(fullText: String) {
                    runOnUiThread {
                        if (isDestroyed || serial != turnSerial) return@runOnUiThread
                        if (fullText.isNotBlank()) answerText.text = fullText
                        setBusy(false)
                    }
                }
                override fun onFailed(failure: Failure) {
                    runOnUiThread {
                        if (isDestroyed || serial != turnSerial) return@runOnUiThread
                        setBusy(false)
                        if (shown.isEmpty()) {
                            val msg = when (failure.kind) {
                                FailureKind.UNAVAILABLE -> "هوش مصنوعی آنلاین در دسترس نیست. اینترنت و کلید Groq را بررسی کن."
                                FailureKind.TIMEOUT -> "پاسخ دیر رسید. دوباره امتحان کن."
                                FailureKind.NETWORK -> "اتصال اینترنت مشکل دارد."
                                FailureKind.LIMIT -> "محدودیت درخواست. کمی بعد دوباره امتحان کن."
                                else -> "تحلیل تصویر انجام نشد. دوباره امتحان کن."
                            }
                            answerText.text = msg
                            speakQueue.addLast(msg)
                            playNext()
                        }
                    }
                }
            }, b64)
        } catch (t: Throwable) {
            Log.w(TAG, "ask failed")
            showFailure(GENERIC_ERROR)
            null
        }
    }

    /** Terminal error before/outside the brain: show a short Persian message and release the busy state. */
    private fun showFailure(msg: String) {
        turnSerial++
        setBusy(false)
        answerText.text = msg
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
        if (speaking || isDestroyed) return
        val next = speakQueue.removeFirstOrNull() ?: return
        speaking = true
        val serial = turnSerial
        try {
            val engine = tts ?: OfflinePersianTts(this).also { tts = it; it.initialize() }
            mainHandler.removeCallbacks(ttsWatchdog)
            mainHandler.postDelayed(ttsWatchdog, TTS_WATCHDOG_MS)
            engine.speak(next, object : JarvisSpeechController.Callback {
                override fun onStart() = Unit
                override fun onDone(success: Boolean) {
                    runOnUiThread {
                        if (isDestroyed || serial != turnSerial) return@runOnUiThread
                        mainHandler.removeCallbacks(ttsWatchdog)
                        speaking = false
                        playNext()
                    }
                }
            })
        } catch (t: Throwable) {
            Log.w(TAG, "tts failed")
            mainHandler.removeCallbacks(ttsWatchdog)
            speaking = false
            speakQueue.clear()
        }
    }

    private fun stopSpeaking() {
        mainHandler.removeCallbacks(ttsWatchdog)
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
        const val MAX_SIDE = 1600
        const val STILL_IDLE = 0
        const val STILL_WAIT = 1
        const val STILL_SHOOT = 2
        const val STILL_TIMEOUT_MS = 8_000L
        /** Keeps the base64 request well below Groq's 4 MB image limit (provider cap is 3.8M chars ≈ 2.8 MB). */
        const val MAX_JPEG_BYTES = 2_500_000
        const val TTS_WATCHDOG_MS = 30_000L
        const val IMAGE_ERROR = "تصویر نامعتبر یا خیلی بزرگ است. تصویر دیگری امتحان کن."
        const val GENERIC_ERROR = "تحلیل تصویر انجام نشد. دوباره امتحان کن."
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
