package tv.mediawahid.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.core.Camera
import androidx.camera.core.CameraEffect.IMAGE_CAPTURE
import androidx.camera.core.CameraEffect.PREVIEW
import androidx.camera.core.CameraEffect.VIDEO_CAPTURE
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.effects.OverlayEffect
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.ExperimentalPersistentRecording
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
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
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    companion object {
        private const val PREFS_NAME = "media_wahid_camera"
        private const val KEY_TEMPLATE = "selected_template"
        private const val TARGET_VIDEO_BITRATE = 8_000_000
        // 75% of the camera's supported positive exposure compensation range.
        private const val BRIGHT_EXPOSURE_PERCENT = 0.75f
        private const val MIN_START_FREE_BYTES = 8L * 1024L * 1024L * 1024L
        private const val STOP_FREE_BYTES = 1L * 1024L * 1024L * 1024L
    }

    private lateinit var root: FrameLayout
    private lateinit var previewView: PreviewView
    private lateinit var recordButton: TextView
    private lateinit var photoButton: TextView
    private lateinit var switchButton: TextView
    private lateinit var statusText: TextView
    private lateinit var timerText: TextView
    private lateinit var dualButton: TextView
    private lateinit var mediaOnlyButton: TextView

    private var cameraProvider: ProcessCameraProvider? = null
    private var boundCamera: Camera? = null
    private var preview: Preview? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var overlayEffect: OverlayEffect? = null
    private var watermarkRenderer: LiveWatermarkRenderer? = null
    private var recording: Recording? = null

    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var selectedTemplate = WatermarkTemplate.DUAL
    private var recordingTemplate = WatermarkTemplate.DUAL
    private var cameraReady = false
    private var lowStorageStopRequested = false
    private var overlayFailed = false
    private var photoCaptureInProgress = false
    private var exposureInfo = "Exposure 75% diproses"

    private val preferences by lazy {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (hasCameraPermission()) {
            previewView.post { startCamera() }
        } else {
            setStatus("Izin kamera wajib untuk menggunakan aplikasi")
            Toast.makeText(
                this,
                "Izin kamera belum diberikan.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        selectedTemplate = WatermarkTemplate.fromStorage(
            preferences.getString(KEY_TEMPLATE, WatermarkTemplate.DUAL.storageValue)
        )
        recordingTemplate = selectedTemplate

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        forceMaxScreenBrightness()
        buildUi()
        applyTemplateUi()
        hideSystemBars()

        recordButton.setOnClickListener { toggleRecording() }
        photoButton.setOnClickListener { takePhoto() }
        switchButton.setOnClickListener { switchCamera() }
        dualButton.setOnClickListener { selectTemplate(WatermarkTemplate.DUAL) }
        mediaOnlyButton.setOnClickListener { selectTemplate(WatermarkTemplate.MEDIA_ONLY) }

        configurePreviewGestures()

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (recording != null) {
                        Toast.makeText(
                            this@MainActivity,
                            "Stop rekaman dulu sebelum keluar.",
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        )

        if (hasCameraPermission()) {
            previewView.post { startCamera() }
        } else {
            permissionLauncher.launch(
                arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            )
        }
    }

    override fun onResume() {
        super.onResume()
        forceMaxScreenBrightness()
        hideSystemBars()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        if (recording == null) {
            val rotation = previewView.display?.rotation ?: return
            preview?.targetRotation = rotation
            imageCapture?.targetRotation = rotation
            videoCapture?.targetRotation = rotation
        }
    }

    override fun onDestroy() {
        recording?.close()
        recording = null

        cameraProvider?.unbindAll()
        overlayEffect?.clearOnDrawListener()
        overlayEffect?.close()
        overlayEffect = null
        watermarkRenderer?.close()
        watermarkRenderer = null

        super.onDestroy()
    }

    private fun buildUi() {
        root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            keepScreenOn = true
        }
        setContentView(root)

        previewView = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            setBackgroundColor(Color.BLACK)
        }
        root.addView(previewView, FrameLayout.LayoutParams(-1, -1))

        val topPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(14), dp(12), dp(14), dp(10))
            background = roundedBackground(
                Color.argb(185, 5, 7, 10),
                Color.argb(180, 70, 80, 92),
                18
            )
        }

        val brandRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        brandRow.addView(ImageView(this).apply {
            setImageResource(R.drawable.media_wahid_logo_original)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }, LinearLayout.LayoutParams(dp(80), dp(44)))

        brandRow.addView(TextView(this).apply {
            text = "MEDIA WAHID TV CAMERA"
            setTextColor(Color.WHITE)
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(10), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, -2, 1f))

        timerText = TextView(this).apply {
            text = "00:00:00"
            setTextColor(Color.WHITE)
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.END
        }
        brandRow.addView(timerText, LinearLayout.LayoutParams(dp(90), -2))

        topPanel.addView(brandRow, LinearLayout.LayoutParams(-1, -2))

        statusText = TextView(this).apply {
            text = "Menyiapkan kamera..."
            setTextColor(Color.rgb(205, 213, 224))
            textSize = 11f
            gravity = Gravity.CENTER
            setPadding(0, dp(7), 0, dp(7))
        }
        topPanel.addView(statusText, LinearLayout.LayoutParams(-1, -2))

        val templateRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        dualButton = templateButton("MASJID + MEDIA")
        mediaOnlyButton = templateButton("MEDIA ONLY")

        templateRow.addView(dualButton, LinearLayout.LayoutParams(0, dp(42), 1f).apply {
            marginEnd = dp(5)
        })
        templateRow.addView(mediaOnlyButton, LinearLayout.LayoutParams(0, dp(42), 1f).apply {
            marginStart = dp(5)
        })
        topPanel.addView(templateRow, LinearLayout.LayoutParams(-1, -2))

        root.addView(topPanel, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply {
            leftMargin = dp(12)
            rightMargin = dp(12)
            topMargin = dp(14)
        })

        val bottomPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(12), dp(14), dp(14))
            background = roundedBackground(
                Color.argb(205, 5, 7, 10),
                Color.argb(180, 70, 80, 92),
                22
            )
        }

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        photoButton = controlButton("FOTO", Color.rgb(40, 47, 57))
        recordButton = controlButton("REKAM", Color.rgb(220, 35, 45))
        switchButton = controlButton("BALIK", Color.rgb(40, 47, 57))

        controls.addView(photoButton, LinearLayout.LayoutParams(0, dp(58), 1f).apply {
            marginEnd = dp(5)
        })
        controls.addView(recordButton, LinearLayout.LayoutParams(0, dp(64), 1.15f).apply {
            marginStart = dp(5)
            marginEnd = dp(5)
        })
        controls.addView(switchButton, LinearLayout.LayoutParams(0, dp(58), 1f).apply {
            marginStart = dp(5)
        })

        bottomPanel.addView(controls, LinearLayout.LayoutParams(-1, -2))

        bottomPanel.addView(TextView(this).apply {
            text = "FHD • watermark langsung tertanam • tanpa render setelah rekam"
            setTextColor(Color.rgb(166, 176, 189))
            textSize = 10f
            gravity = Gravity.CENTER
            setPadding(0, dp(9), 0, 0)
        }, LinearLayout.LayoutParams(-1, -2))

        root.addView(bottomPanel, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
            leftMargin = dp(12)
            rightMargin = dp(12)
            bottomMargin = dp(18)
        })
    }

    private fun templateButton(label: String): TextView =
        TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 11f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            isClickable = true
            isFocusable = true
        }

    private fun controlButton(label: String, fill: Int): TextView =
        TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 13f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            background = roundedBackground(fill, fill, 24)
            isClickable = true
            isFocusable = true
        }

    private fun selectTemplate(template: WatermarkTemplate) {
        if (recording != null || photoCaptureInProgress) {
            Toast.makeText(
                this,
                if (recording != null) "Template dikunci selama rekaman." else "Tunggu foto selesai.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        val saved = preferences.edit()
            .putString(KEY_TEMPLATE, template.storageValue)
            .commit()

        if (!saved) {
            Toast.makeText(this, "Template gagal disimpan.", Toast.LENGTH_LONG).show()
            return
        }

        selectedTemplate = template
        recordingTemplate = template
        watermarkRenderer?.template = template
        applyTemplateUi()
        setStatus("Template aktif: " + template.displayName)
    }

    private fun applyTemplateUi() {
        val activeFill = Color.rgb(22, 58, 50)
        val activeStroke = Color.rgb(72, 210, 159)
        val idleFill = Color.rgb(28, 34, 42)
        val idleStroke = Color.rgb(70, 80, 94)

        val dual = selectedTemplate == WatermarkTemplate.DUAL
        dualButton.background = roundedBackground(
            if (dual) activeFill else idleFill,
            if (dual) activeStroke else idleStroke,
            18
        )
        mediaOnlyButton.background = roundedBackground(
            if (!dual) activeFill else idleFill,
            if (!dual) activeStroke else idleStroke,
            18
        )
        dualButton.text = if (dual) "✓ MASJID + MEDIA" else "MASJID + MEDIA"
        mediaOnlyButton.text = if (!dual) "✓ MEDIA ONLY" else "MEDIA ONLY"
    }

    private fun startCamera() {
        cameraReady = false
        setControlsEnabled(false)
        setStatus("Menyiapkan kamera FHD...")

        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                cameraProvider = future.get()
                buildCameraUseCases()
                bindCamera()
                waitForPreviewReady(
                    onReady = {
                        setControlsEnabled(true)
                        setStatus("Siap • " + selectedTemplate.displayName + " • " + exposureInfo)
                        applyExposureAfterPreviewReady()
                    },
                    onTimeout = {
                        setControlsEnabled(false)
                        setStatus("Preview gagal siap • buka ulang aplikasi")
                    }
                )
            } catch (error: Throwable) {
                cameraReady = false
                setControlsEnabled(false)
                setStatus("Kamera gagal dibuka")
                Toast.makeText(
                    this,
                    error.message ?: "Kamera gagal dibuka.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @Suppress("DEPRECATION")
    private fun buildCameraUseCases() {
        val rotation = previewView.display?.rotation ?: windowManager.defaultDisplay.rotation

        preview = Preview.Builder()
            .setTargetRotation(rotation)
            .build()
            .also {
                it.surfaceProvider = previewView.surfaceProvider
            }

        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setTargetRotation(rotation)
            .build()

        val recorder = Recorder.Builder()
            .setQualitySelector(
                QualitySelector.from(
                    Quality.FHD,
                    FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)
                )
            )
            .setTargetVideoEncodingBitRate(TARGET_VIDEO_BITRATE)
            .build()

        videoCapture = VideoCapture.withOutput(recorder).also {
            it.targetRotation = rotation
        }

        watermarkRenderer?.close()
        watermarkRenderer = LiveWatermarkRenderer(this, previewView).apply {
            template = selectedTemplate
        }

        overlayEffect?.clearOnDrawListener()
        overlayEffect?.close()

        overlayEffect = OverlayEffect(
            PREVIEW or VIDEO_CAPTURE or IMAGE_CAPTURE,
            0,
            Handler(Looper.getMainLooper())
        ) { error ->
            runOnUiThread {
                overlayFailed = true
                cameraReady = false
                runCatching { recording?.stop() }
                setControlsEnabled(false)
                setStatus("Watermark engine error • capture dihentikan")
                Toast.makeText(
                    this,
                    error.message ?: "Watermark engine gagal.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }.also { effect ->
            effect.setOnDrawListener { frame ->
                watermarkRenderer?.draw(frame) ?: false
            }
        }
    }

    private fun bindCamera() {
        val provider = cameraProvider ?: return
        val previewUseCase = preview ?: return
        val photoUseCase = imageCapture ?: return
        val videoUseCase = videoCapture ?: return
        val effect = overlayEffect ?: return

        val selector = CameraSelector.Builder()
            .requireLensFacing(lensFacing)
            .build()

        provider.unbindAll()

        val groupBuilder = UseCaseGroup.Builder()
            .addUseCase(previewUseCase)
            .addUseCase(photoUseCase)
            .addUseCase(videoUseCase)
            .addEffect(effect)

        previewView.viewPort?.let { groupBuilder.setViewPort(it) }

        boundCamera = provider.bindToLifecycle(this, selector, groupBuilder.build())
    }

    /**
     * Non-crashing CameraX-only brightness attempt.
     * Wait until preview is ready, then request 75% of supported positive
     * camera exposure. Do not access Camera2 interop from this build.
     */
    private fun applyExposureAfterPreviewReady() {
        val camera = boundCamera ?: return
        val current = camera
        previewView.postDelayed({
            if (boundCamera === current && cameraReady && !overlayFailed) {
                try {
                    applyBrightCameraExposure(current)
                } catch (error: Exception) {
                    exposureInfo = "Exposure: " + error.javaClass.simpleName
                    if (recording == null) {
                        setStatus("Siap • " + selectedTemplate.displayName + " • " + exposureInfo)
                    }
                }
            }
        }, 450L)
    }

    private fun applyBrightCameraExposure(camera: Camera) {
        val state = camera.cameraInfo.exposureState
        val range = state.exposureCompensationRange
        if (!state.isExposureCompensationSupported || range.upper <= 0) {
            exposureInfo = "Exposure positif tidak didukung"
            if (recording == null) {
                setStatus("Siap • " + selectedTemplate.displayName + " • " + exposureInfo)
            }
            return
        }

        val targetIndex = (range.upper * BRIGHT_EXPOSURE_PERCENT)
            .roundToInt().coerceIn(1, range.upper)
        val stepEv = state.exposureCompensationStep.toFloat()
        exposureInfo = "Exposure 75% diproses"
        val request = camera.cameraControl.setExposureCompensationIndex(targetIndex)
        request.addListener({
            if (boundCamera !== camera) return@addListener
            exposureInfo = try {
                request.get()
                val appliedIndex = camera.cameraInfo.exposureState.exposureCompensationIndex
                if (appliedIndex == targetIndex) {
                    "Exposure 75% (+%.2f EV)".format(Locale.US, appliedIndex * stepEv)
                } else {
                    "Exposure belum terkonfirmasi ($appliedIndex/$targetIndex)"
                }
            } catch (error: Exception) {
                val cause = (error as? java.util.concurrent.ExecutionException)?.cause ?: error
                "Exposure gagal: " + cause.javaClass.simpleName
            }
            if (cameraReady && !overlayFailed && recording == null && !photoCaptureInProgress) {
                setStatus("Siap • " + selectedTemplate.displayName + " • " + exposureInfo)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @OptIn(ExperimentalPersistentRecording::class)
    @SuppressLint("MissingPermission")
    private fun toggleRecording() {
        if (photoCaptureInProgress) return

        val active = recording
        if (active != null) {
            recordButton.isEnabled = false
            setStatus("Menyelesaikan file video...")
            active.stop()
            return
        }

        if (!cameraReady || overlayFailed || previewView.sensorToViewTransform == null) {
            Toast.makeText(this, "Kamera belum siap.", Toast.LENGTH_SHORT).show()
            return
        }

        val freeBytes = availableStorageBytes()
        if (freeBytes in 0 until MIN_START_FREE_BYTES) {
            Toast.makeText(
                this,
                "Untuk target 1,5 jam FHD, sisakan minimal 8 GB storage kosong.",
                Toast.LENGTH_LONG
            ).show()
            setStatus("Storage belum aman untuk target 90 menit")
            return
        }

        val videoUseCase = videoCapture ?: return
        recordingTemplate = selectedTemplate
        watermarkRenderer?.template = recordingTemplate

        val values = ContentValues().apply {
            put(
                MediaStore.Video.Media.DISPLAY_NAME,
                "MEDIA_WAHID_TV_" + templateSuffix(recordingTemplate) + "_" + timestamp() + ".mp4"
            )
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(
                MediaStore.Video.Media.RELATIVE_PATH,
                Environment.DIRECTORY_MOVIES + "/MEDIA WAHID TV"
            )
        }

        val outputOptions = MediaStoreOutputOptions.Builder(
            contentResolver,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).setContentValues(values).build()

        try {
            var pending = videoUseCase.output.prepareRecording(this, outputOptions)
            if (hasAudioPermission()) {
                pending = pending.withAudioEnabled()
            }

            lowStorageStopRequested = false
            recording = pending
                .asPersistentRecording()
                .start(ContextCompat.getMainExecutor(this)) { event ->
                    handleVideoEvent(event)
                }
        } catch (error: Throwable) {
            recording = null
            recordButton.isEnabled = true
            setStatus("Gagal memulai rekaman")
            Toast.makeText(
                this,
                error.message ?: "Recorder tidak dapat dimulai.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun handleVideoEvent(event: VideoRecordEvent) {
        when (event) {
            is VideoRecordEvent.Start -> {
                timerText.text = "00:00:00"

                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
                recordButton.text = "STOP"
                recordButton.background = roundedBackground(
                    Color.rgb(145, 22, 30),
                    Color.rgb(255, 88, 98),
                    24
                )
                photoButton.isEnabled = false
                dualButton.isEnabled = false
                mediaOnlyButton.isEnabled = false
                switchButton.isEnabled = true
                recordButton.isEnabled = true
                setStatus(
                    "REC • " + recordingTemplate.displayName +
                        if (hasAudioPermission()) " • MIC ON" else " • MIC OFF"
                )
            }

            is VideoRecordEvent.Status -> {
                timerText.text = formatDuration(event.recordingStats.recordedDurationNanos)

                if (
                    !lowStorageStopRequested &&
                    availableStorageBytes() in 0 until STOP_FREE_BYTES
                ) {
                    lowStorageStopRequested = true
                    setStatus("Storage tersisa < 1 GB • menghentikan rekaman dengan aman...")
                    recording?.stop()
                }
            }

            is VideoRecordEvent.Finalize -> {
                val finishedRecording = recording
                recording = null
                finishedRecording?.close()
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR

                recordButton.text = "REKAM"
                recordButton.background = roundedBackground(
                    Color.rgb(220, 35, 45),
                    Color.rgb(220, 35, 45),
                    24
                )
                recordButton.isEnabled = true
                photoButton.isEnabled = true
                dualButton.isEnabled = true
                mediaOnlyButton.isEnabled = true
                switchButton.isEnabled = true

                if (overlayFailed) {
                    setControlsEnabled(false)
                    setStatus("Watermark engine error • buka ulang aplikasi")
                    return
                }

                if (event.hasError()) {
                    val error = event.error
                    val uri = event.outputResults.outputUri

                    if (isCorruptOutputError(error)) {
                        if (uri != android.net.Uri.EMPTY) {
                            runCatching { contentResolver.delete(uri, null, null) }
                        }
                        setStatus("Rekaman gagal • file korup dibersihkan")
                        Toast.makeText(
                            this,
                            "Rekaman gagal: " + recordingErrorLabel(error),
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        setStatus("VIDEO PARSIAL TERSIMPAN • " + recordingErrorLabel(error))
                        Toast.makeText(
                            this,
                            "Bagian video yang masih valid tetap disimpan.",
                            Toast.LENGTH_LONG
                        ).show()
                    }

                    if (needsRecorderRebuild(error)) {
                        runCatching {
                            buildCameraUseCases()
                            bindCamera()
                        }
                    }
                } else {
                    setStatus("VIDEO TERSIMPAN ✓ • watermark sudah tertanam")
                    Toast.makeText(
                        this,
                        "VIDEO TERSIMPAN ✓",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun takePhoto() {
        if (!cameraReady || overlayFailed || recording != null || photoCaptureInProgress) return

        if (availableStorageBytes() in 0 until STOP_FREE_BYTES) {
            Toast.makeText(this, "Storage hampir penuh.", Toast.LENGTH_LONG).show()
            return
        }

        val capture = imageCapture ?: return
        watermarkRenderer?.template = selectedTemplate

        val values = ContentValues().apply {
            put(
                MediaStore.Images.Media.DISPLAY_NAME,
                "MEDIA_WAHID_TV_" + templateSuffix(selectedTemplate) + "_" + timestamp() + ".jpg"
            )
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                Environment.DIRECTORY_PICTURES + "/MEDIA WAHID TV"
            )
        }

        val options = ImageCapture.OutputFileOptions.Builder(
            contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            values
        ).build()

        photoCaptureInProgress = true
        recordButton.isEnabled = false
        photoButton.isEnabled = false
        switchButton.isEnabled = false
        dualButton.isEnabled = false
        mediaOnlyButton.isEnabled = false
        setStatus("Mengambil foto...")

        try {
            capture.takePicture(
                options,
                ContextCompat.getMainExecutor(this),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                        photoCaptureInProgress = false
                        restoreIdleControls()
                        setStatus("FOTO TERSIMPAN ✓ • watermark sudah tertanam")
                        Toast.makeText(
                            this@MainActivity,
                            "FOTO TERSIMPAN ✓",
                            Toast.LENGTH_SHORT
                        ).show()
                    }

                    override fun onError(exception: ImageCaptureException) {
                        photoCaptureInProgress = false
                        restoreIdleControls()
                        setStatus("Foto gagal")
                        Toast.makeText(
                            this@MainActivity,
                            exception.message ?: "Foto gagal.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            )
        } catch (error: Throwable) {
            photoCaptureInProgress = false
            restoreIdleControls()
            setStatus("Foto gagal")
            Toast.makeText(
                this,
                error.message ?: "Kamera gagal mengambil foto.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun switchCamera() {
        val provider = cameraProvider ?: return
        if (!cameraReady || photoCaptureInProgress) return

        val newFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }

        val selector = CameraSelector.Builder()
            .requireLensFacing(newFacing)
            .build()

        val available = runCatching { provider.hasCamera(selector) }.getOrDefault(false)
        if (!available) {
            Toast.makeText(this, "Kamera tersebut tidak tersedia.", Toast.LENGTH_SHORT).show()
            return
        }

        switchButton.isEnabled = false
        lensFacing = newFacing
        setStatus(
            if (recording != null) {
                "REC • mengganti kamera..."
            } else {
                "Mengganti kamera..."
            }
        )

        runCatching { bindCamera() }
            .onSuccess {
                waitForPreviewReady(
                    onReady = {
                        switchButton.isEnabled = true
                        setStatus(
                            if (recording != null) {
                                "REC • " + recordingTemplate.displayName
                            } else {
                                "Siap • " + selectedTemplate.displayName + " • " + exposureInfo
                            }
                        )
                    },
                    onTimeout = {
                        switchButton.isEnabled = true
                        if (recording != null) {
                            setStatus("Preview gagal stabil • rekaman dihentikan aman")
                            recording?.stop()
                        } else {
                            setStatus("Preview gagal stabil")
                        }
                    }
                )
            }
            .onFailure { error ->
                lensFacing = if (newFacing == CameraSelector.LENS_FACING_BACK) {
                    CameraSelector.LENS_FACING_FRONT
                } else {
                    CameraSelector.LENS_FACING_BACK
                }
                runCatching { bindCamera() }
                switchButton.isEnabled = true
                setStatus("Gagal mengganti kamera")
                Toast.makeText(
                    this,
                    error.message ?: "Gagal mengganti kamera.",
                    Toast.LENGTH_LONG
                ).show()
            }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun configurePreviewGestures() {
        val scaleDetector = ScaleGestureDetector(
            this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val camera = boundCamera ?: return false
                    val state = camera.cameraInfo.zoomState.value ?: return false
                    val target = (
                        state.zoomRatio * detector.scaleFactor
                    ).coerceIn(state.minZoomRatio, state.maxZoomRatio)
                    camera.cameraControl.setZoomRatio(target)
                    return true
                }
            }
        )

        var downX = 0f
        var downY = 0f

        previewView.setOnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event)

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    true
                }

                MotionEvent.ACTION_UP -> {
                    val moved = max(
                        kotlin.math.abs(event.x - downX),
                        kotlin.math.abs(event.y - downY)
                    )
                    if (!scaleDetector.isInProgress && moved < dp(16)) {
                        focusAt(event.x, event.y)
                    }
                    true
                }

                else -> true
            }
        }
    }

    private fun focusAt(x: Float, y: Float) {
        val camera = boundCamera ?: return
        val point = previewView.meteringPointFactory.createPoint(x, y)
        val action = FocusMeteringAction.Builder(point)
            .setAutoCancelDuration(3, TimeUnit.SECONDS)
            .build()

        camera.cameraControl.startFocusAndMetering(action)
    }

    private fun restoreIdleControls() {
        if (recording != null || overlayFailed || !cameraReady) return
        recordButton.isEnabled = true
        photoButton.isEnabled = true
        switchButton.isEnabled = true
        dualButton.isEnabled = true
        mediaOnlyButton.isEnabled = true
    }

    private fun waitForPreviewReady(
        attempt: Int = 0,
        onReady: () -> Unit,
        onTimeout: () -> Unit,
    ) {
        if (overlayFailed) {
            cameraReady = false
            onTimeout()
            return
        }

        if (
            previewView.width > 0 &&
            previewView.height > 0 &&
            previewView.sensorToViewTransform != null
        ) {
            cameraReady = true
            onReady()
            return
        }

        if (attempt >= 30) {
            cameraReady = false
            onTimeout()
            return
        }

        previewView.postDelayed(
            {
                waitForPreviewReady(
                    attempt = attempt + 1,
                    onReady = onReady,
                    onTimeout = onTimeout
                )
            },
            100L
        )
    }

    private fun setControlsEnabled(enabled: Boolean) {
        recordButton.isEnabled = enabled
        photoButton.isEnabled = enabled
        switchButton.isEnabled = enabled
        dualButton.isEnabled = enabled
        mediaOnlyButton.isEnabled = enabled
    }

    @Suppress("DEPRECATION")
    private fun availableStorageBytes(): Long =
        runCatching {
            android.os.StatFs(
                Environment.getExternalStorageDirectory().absolutePath
            ).availableBytes
        }.getOrDefault(-1L)

    private fun isCorruptOutputError(error: Int): Boolean =
        error == VideoRecordEvent.Finalize.ERROR_UNKNOWN ||
            error == VideoRecordEvent.Finalize.ERROR_INVALID_OUTPUT_OPTIONS ||
            error == VideoRecordEvent.Finalize.ERROR_ENCODING_FAILED ||
            error == VideoRecordEvent.Finalize.ERROR_RECORDER_ERROR ||
            error == VideoRecordEvent.Finalize.ERROR_NO_VALID_DATA ||
            error == VideoRecordEvent.Finalize.ERROR_RECORDING_GARBAGE_COLLECTED

    private fun needsRecorderRebuild(error: Int): Boolean =
        error == VideoRecordEvent.Finalize.ERROR_UNKNOWN ||
            error == VideoRecordEvent.Finalize.ERROR_ENCODING_FAILED ||
            error == VideoRecordEvent.Finalize.ERROR_RECORDER_ERROR

    private fun recordingErrorLabel(error: Int): String =
        when (error) {
            VideoRecordEvent.Finalize.ERROR_UNKNOWN -> "error tidak dikenal"
            VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED -> "batas file sistem tercapai"
            VideoRecordEvent.Finalize.ERROR_INSUFFICIENT_STORAGE -> "storage habis"
            VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE -> "kamera berhenti mengirim frame"
            VideoRecordEvent.Finalize.ERROR_INVALID_OUTPUT_OPTIONS -> "output tidak valid"
            VideoRecordEvent.Finalize.ERROR_ENCODING_FAILED -> "encoder gagal"
            VideoRecordEvent.Finalize.ERROR_RECORDER_ERROR -> "recorder error"
            VideoRecordEvent.Finalize.ERROR_NO_VALID_DATA -> "tidak ada data video valid"
            VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED -> "batas durasi sistem tercapai"
            VideoRecordEvent.Finalize.ERROR_RECORDING_GARBAGE_COLLECTED -> "sesi recording terlepas"
            else -> "kode error $error"
        }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

    private fun hasAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

    private fun setStatus(message: String) {
        statusText.text = message
    }

    private fun timestamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())

    private fun templateSuffix(template: WatermarkTemplate): String =
        if (template == WatermarkTemplate.DUAL) "DUAL" else "MEDIA"

    private fun formatDuration(nanos: Long): String {
        val totalSeconds = TimeUnit.NANOSECONDS.toSeconds(nanos)
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
    }

    private fun roundedBackground(fill: Int, stroke: Int, radiusDp: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(radiusDp).toFloat()
            setColor(fill)
            setStroke(dp(1), stroke)
        }

    private fun forceMaxScreenBrightness() {
        val params = window.attributes
        params.screenBrightness = 1.0f
        window.attributes = params
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, root).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
