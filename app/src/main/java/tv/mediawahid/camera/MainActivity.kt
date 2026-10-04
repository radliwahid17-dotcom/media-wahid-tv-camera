package tv.mediawahid.camera

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.Gravity
import android.view.HapticFeedbackConstants
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

@UnstableApi
class MainActivity : ComponentActivity() {
    private lateinit var root: FrameLayout
    private lateinit var previewView: PreviewView
    private lateinit var recordButton: RecordButtonView
    private lateinit var actionText: TextView
    private lateinit var timerText: TextView
    private lateinit var processingPanel: View
    private lateinit var savedMessage: TextView

    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private var recordingStartedAt = 0L
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
            FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
                topMargin = dp(18)
                marginStart = dp(16)
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

    private fun bindCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val rotation = previewView.display?.rotation ?: Surface.ROTATION_0
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

            videoCapture = VideoCapture.withOutput(recorder).also {
                it.targetRotation = rotation
            }

            try {
                provider.unbindAll()
                provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    videoCapture
                )
            } catch (_: Throwable) {
                Toast.makeText(this, "Kamera tidak bisa dibuka.", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun startRecording() {
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
                        Toast.makeText(this, "Rekaman gagal. Coba lagi.", Toast.LENGTH_LONG).show()
                    } else processAndSave(rawFile)
                }
            }
        }
    }

    private fun processAndSave(rawFile: File) {
        processingPanel.visibility = View.VISIBLE
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
                        savedMessage.visibility = View.VISIBLE
                        savedMessage.postDelayed({ savedMessage.visibility = View.GONE }, 1800)
                    } catch (_: Throwable) {
                        processingPanel.visibility = View.GONE
                        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
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
