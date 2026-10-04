package tv.mediawahid.camera

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.util.UnstableApi
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

@UnstableApi
class MainActivity : ComponentActivity() {
    private lateinit var root: FrameLayout
    private lateinit var previewView: PreviewView
    private lateinit var recordButton: RecordButtonView
    private lateinit var actionText: TextView
    private lateinit var timerText: TextView
    private lateinit var switchCameraButton: TextView
    private lateinit var zoomOutButton: TextView
    private lateinit var zoomInButton: TextView
    private lateinit var zoomText: TextView
    private lateinit var processingPanel: View
    private lateinit var savedMessage: TextView

    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private var recordingStartedAt = 0L
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var cameraSwitchEnabled = true
    private var activeCamera: Camera? = null
    private var currentZoomRatio = 1f
    private var minZoomRatio = 1f
    private var maxZoomRatio = 1f
    private lateinit var scaleGestureDetector: ScaleGestureDetector
    private val timerHandler = Handler(Looper.getMainLooper())

    private val permissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.CAMERA] == true &&
            permissions[Manifest.permission.RECORD_AUDIO] == true
        ) bindCamera()
        else Toast.makeText(
            this,
            "Pilih IZINKAN untuk Kamera dan Mikrofon.",
            Toast.LENGTH_LONG
        ).show()
    }

    private val timerRunnable = object : Runnable {
        override fun run() {
            if (activeRecording == null) return
            val totalSeconds = TimeUnit.MILLISECONDS.toSeconds(
                System.currentTimeMillis() - recordingStartedAt
            )
            timerText.text = String.format(
                Locale.US,
                "● REC  %02d:%02d",
                totalSeconds / 60,
                totalSeconds % 60
            )
            timerHandler.postDelayed(this, 250)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildSimpleUi()
        hideSystemBars()

        recordButton.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            if (activeRecording == null) startRecording() else activeRecording?.stop()
        }

        switchCameraButton.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            switchCamera()
        }

        zoomOutButton.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            changeZoom(1f / 1.25f)
        }

        zoomInButton.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            changeZoom(1.25f)
        }

        scaleGestureDetector = ScaleGestureDetector(
            this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    setZoom(currentZoomRatio * detector.scaleFactor)
                    return true
                }
            }
        )
        previewView.setOnTouchListener { _, event ->
            scaleGestureDetector.onTouchEvent(event)
            true
        }

        requestPermissionsOrStart()
    }

    override fun onResume() {
        super.onResume()
        if (::root.isInitialized) hideSystemBars()
    }

    private fun buildSimpleUi() {
        root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            keepScreenOn = true
        }
        setContentView(root)

        previewView = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
        root.addView(previewView, FrameLayout.LayoutParams(-1, -1))

        val masjidWatermark = ImageView(this).apply {
            setImageResource(R.drawable.masjid_raya_logo)
            scaleType = ImageView.ScaleType.FIT_CENTER
            alpha = 0.96f
        }
        root.addView(
            masjidWatermark,
            FrameLayout.LayoutParams(dp(165), dp(95), Gravity.TOP or Gravity.START).apply {
                topMargin = dp(14)
                marginStart = dp(12)
            }
        )

        val watermark = ImageView(this).apply {
            setImageResource(R.drawable.media_wahid_logo_original)
            scaleType = ImageView.ScaleType.FIT_CENTER
            alpha = 0.96f
        }
        root.addView(
            watermark,
            FrameLayout.LayoutParams(dp(165), dp(95), Gravity.TOP or Gravity.END).apply {
                topMargin = dp(14)
                marginEnd = dp(12)
            }
        )

        timerText = TextView(this).apply {
            text = "● REC  00:00"
            setTextColor(Color.WHITE)
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(10), dp(7), dp(10), dp(7))
            setBackgroundColor(Color.argb(165, 0, 0, 0))
            visibility = View.GONE
        }
        root.addView(
            timerText,
            FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
                topMargin = dp(18)
            }
        )

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        actionText = TextView(this).apply {
            text = "REKAM"
            setTextColor(Color.WHITE)
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(16), dp(6), dp(16), dp(6))
            setBackgroundColor(Color.argb(150, 0, 0, 0))
            gravity = Gravity.CENTER
        }
        recordButton = RecordButtonView(this)
        bottom.addView(actionText, LinearLayout.LayoutParams(-2, -2).apply {
            bottomMargin = dp(10)
        })
        bottom.addView(recordButton, LinearLayout.LayoutParams(dp(106), dp(106)))
        root.addView(
            bottom,
            FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                bottomMargin = dp(24)
            }
        )

        switchCameraButton = TextView(this).apply {
            text = "DEPAN"
            setTextColor(Color.WHITE)
            textSize = 11f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(24).toFloat()
                setColor(Color.argb(175, 0, 0, 0))
                setStroke(dp(1), Color.argb(190, 255, 255, 255))
            }
        }
        root.addView(
            switchCameraButton,
            FrameLayout.LayoutParams(dp(92), dp(48), Gravity.BOTTOM or Gravity.END).apply {
                bottomMargin = dp(52)
                marginEnd = dp(20)
            }
        )

        val zoomControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        zoomOutButton = createSmallControlButton("−")
        zoomText = TextView(this).apply {
            text = "1.0×"
            setTextColor(Color.WHITE)
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setBackgroundColor(Color.argb(150, 0, 0, 0))
        }
        zoomInButton = createSmallControlButton("+")
        zoomControls.addView(zoomOutButton, LinearLayout.LayoutParams(dp(48), dp(48)))
        zoomControls.addView(zoomText, LinearLayout.LayoutParams(dp(62), dp(48)).apply {
            marginStart = dp(6)
            marginEnd = dp(6)
        })
        zoomControls.addView(zoomInButton, LinearLayout.LayoutParams(dp(48), dp(48)))
        root.addView(
            zoomControls,
            FrameLayout.LayoutParams(-2, dp(48), Gravity.BOTTOM or Gravity.START).apply {
                bottomMargin = dp(52)
                marginStart = dp(20)
            }
        )

        val processing = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.argb(235, 0, 0, 0))
            visibility = View.GONE
            addView(ProgressBar(this@MainActivity), LinearLayout.LayoutParams(dp(68), dp(68)))
            addView(TextView(this@MainActivity).apply {
                text = "MENYIMPAN VIDEO..."
                setTextColor(Color.WHITE)
                textSize = 20f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(0, dp(18), 0, 0)
            })
            addView(TextView(this@MainActivity).apply {
                text = "Jangan tutup aplikasi"
                setTextColor(Color.LTGRAY)
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(0, dp(6), 0, 0)
            })
        }
        processingPanel = processing
        root.addView(processing, FrameLayout.LayoutParams(-1, -1))

        savedMessage = TextView(this).apply {
            text = "VIDEO TERSIMPAN ✓"
            setTextColor(Color.WHITE)
            textSize = 20f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(16), dp(24), dp(16))
            setBackgroundColor(Color.rgb(19, 138, 85))
            visibility = View.GONE
        }
        root.addView(savedMessage, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
    }

    private fun requestPermissionsOrStart() {
        val camera = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        val mic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (camera && mic) bindCamera()
        else permissionsLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
    }

    private fun createSmallControlButton(label: String): TextView =
        TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 24f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(175, 0, 0, 0))
                setStroke(dp(1), Color.argb(190, 255, 255, 255))
            }
        }

    private fun changeZoom(multiplier: Float) {
        setZoom(currentZoomRatio * multiplier)
    }

    private fun setZoom(ratio: Float) {
        val camera = activeCamera ?: return
        val target = ratio.coerceIn(minZoomRatio, maxZoomRatio)
        currentZoomRatio = target
        zoomText.text = String.format(Locale.US, "%.1f×", target)
        camera.cameraControl.setZoomRatio(target)
    }

    private fun updateZoomBounds(camera: Camera) {
        val state = camera.cameraInfo.zoomState.value
        minZoomRatio = state?.minZoomRatio ?: 1f
        maxZoomRatio = state?.maxZoomRatio ?: 1f
        currentZoomRatio = 1f.coerceIn(minZoomRatio, maxZoomRatio)
        zoomText.text = String.format(Locale.US, "%.1f×", currentZoomRatio)
        zoomOutButton.isEnabled = maxZoomRatio > minZoomRatio
        zoomInButton.isEnabled = maxZoomRatio > minZoomRatio
        val alpha = if (maxZoomRatio > minZoomRatio) 1f else 0.45f
        zoomOutButton.alpha = alpha
        zoomInButton.alpha = alpha
        camera.cameraControl.setZoomRatio(currentZoomRatio)
    }

    private fun switchCamera() {
        if (!cameraSwitchEnabled || activeRecording != null) return

        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }

        updateSwitchCameraLabel()
        bindCamera()
    }

    private fun updateSwitchCameraLabel() {
        switchCameraButton.text = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
            "DEPAN"
        } else {
            "BELAKANG"
        }
    }

    private fun setCameraSwitchAvailable(available: Boolean) {
        cameraSwitchEnabled = available
        switchCameraButton.isEnabled = available
        switchCameraButton.alpha = if (available) 1f else 0.45f
    }

    private fun bindCamera() {
        setCameraSwitchAvailable(false)

        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val rotation = previewView.display?.rotation ?: Surface.ROTATION_0
            val selector = CameraSelector.Builder()
                .requireLensFacing(lensFacing)
                .build()

            if (!provider.hasCamera(selector)) {
                lensFacing = CameraSelector.LENS_FACING_BACK
                updateSwitchCameraLabel()
                setCameraSwitchAvailable(true)
                Toast.makeText(this, "Kamera tersebut tidak tersedia.", Toast.LENGTH_LONG).show()
                return@addListener
            }

            val preview = Preview.Builder()
                .setTargetRotation(rotation)
                .build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }

            val recorder = Recorder.Builder()
                .setQualitySelector(
                    QualitySelector.fromOrderedList(
                        listOf(Quality.FHD, Quality.HD),
                        FallbackStrategy.lowerQualityOrHigherThan(Quality.HD)
                    )
                )
                .build()

            val newVideoCapture = VideoCapture.withOutput(recorder).also {
                it.targetRotation = rotation
            }

            try {
                provider.unbindAll()
                val camera = provider.bindToLifecycle(
                    this,
                    selector,
                    preview,
                    newVideoCapture
                )

                videoCapture = newVideoCapture
                activeCamera = camera
                updateZoomBounds(camera)
                applyBrighterExposure(camera)
                setCameraSwitchAvailable(true)
            } catch (_: Throwable) {
                setCameraSwitchAvailable(true)
                Toast.makeText(this, "Kamera tidak bisa dibuka.", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun applyBrighterExposure(camera: Camera) {
        val exposureState = camera.cameraInfo.exposureState
        if (!exposureState.isExposureCompensationSupported) return

        val range = exposureState.exposureCompensationRange
        val step = exposureState.exposureCompensationStep.toFloat()
        if (step <= 0f) return

        // v1.5: dinaikkan lagi dari v1.4 agar kamera terasa jelas lebih terang.
        val targetIndex = (1.2f / step)
            .roundToInt()
            .coerceIn(range.lower, range.upper)

        camera.cameraControl.setExposureCompensationIndex(targetIndex)
    }

    private fun startRecording() {
        if (!cameraSwitchEnabled) return
        val capture = videoCapture ?: return
        val rawFile = File(cacheDir, "raw_${System.currentTimeMillis()}.mp4")
        var pending = capture.output.prepareRecording(
            this,
            FileOutputOptions.Builder(rawFile).build()
        )

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) pending = pending.withAudioEnabled()

        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
        setCameraSwitchAvailable(false)
        recordButton.isRecording = true
        actionText.text = "STOP"
        timerText.visibility = View.VISIBLE
        recordingStartedAt = System.currentTimeMillis()

        activeRecording = pending.start(ContextCompat.getMainExecutor(this)) { event ->
            when (event) {
                is VideoRecordEvent.Start -> timerHandler.post(timerRunnable)
                is VideoRecordEvent.Finalize -> {
                    activeRecording = null
                    timerHandler.removeCallbacks(timerRunnable)
                    timerText.visibility = View.GONE
                    recordButton.isRecording = false
                    actionText.text = "REKAM"

                    if (event.error != VideoRecordEvent.Finalize.ERROR_NONE) {
                        rawFile.delete()
                        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
                        setCameraSwitchAvailable(true)
                        Toast.makeText(this, "Rekaman gagal. Coba lagi.", Toast.LENGTH_LONG).show()
                    } else processAndSave(rawFile)
                }
            }
        }
    }

    private fun processAndSave(rawFile: File) {
        processingPanel.visibility = View.VISIBLE
        setCameraSwitchAvailable(false)
        val watermarked = File(cacheDir, "wahid_${System.currentTimeMillis()}.mp4")

        WatermarkExporter(this).export(
            input = rawFile,
            output = watermarked,
            onCompleted = {
                runOnUiThread {
                    rawFile.delete()
                    try {
                        saveToGallery(watermarked)
                        watermarked.delete()
                        processingPanel.visibility = View.GONE
                        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
                        setCameraSwitchAvailable(true)
                        savedMessage.visibility = View.VISIBLE
                        savedMessage.postDelayed({ savedMessage.visibility = View.GONE }, 1800)
                    } catch (_: Throwable) {
                        processingPanel.visibility = View.GONE
                        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
                        setCameraSwitchAvailable(true)
                        Toast.makeText(this, "Video belum berhasil disimpan.", Toast.LENGTH_LONG).show()
                    }
                }
            },
            onError = {
                runOnUiThread {
                    rawFile.delete()
                    watermarked.delete()
                    processingPanel.visibility = View.GONE
                    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
                    setCameraSwitchAvailable(true)
                    Toast.makeText(this, "Video belum berhasil disimpan.", Toast.LENGTH_LONG).show()
                }
            }
        )
    }

    private fun saveToGallery(file: File): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "MEDIA_WAHID_TV_${System.currentTimeMillis()}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/MEDIA WAHID TV")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }

        val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("Tidak dapat membuat file video")

        contentResolver.openOutputStream(uri)?.use { output ->
            file.inputStream().use { input -> input.copyTo(output) }
        } ?: error("Tidak dapat menulis video")

        values.clear()
        values.put(MediaStore.Video.Media.IS_PENDING, 0)
        contentResolver.update(uri, values, null, null)
        return uri
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, root).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        timerHandler.removeCallbacksAndMessages(null)
        activeRecording?.close()
        activeRecording = null
        super.onDestroy()
    }
}
private class RecordButtonView(context: Context) : View(context) {
    var isRecording: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 7f * resources.displayMetrics.density
        color = Color.WHITE
    }
    private val red = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(239, 47, 54)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(width, height) * 0.43f
        ring.color = if (isRecording) Color.rgb(239, 47, 54) else Color.WHITE
        canvas.drawCircle(cx, cy, r, ring)

        if (isRecording) {
            val s = minOf(width, height) * 0.27f
            canvas.drawRoundRect(cx - s, cy - s, cx + s, cy + s, s * 0.18f, s * 0.18f, red)
        } else {
            canvas.drawCircle(cx, cy, minOf(width, height) * 0.32f, red)
        }
    }
}
