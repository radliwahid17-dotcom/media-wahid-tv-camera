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
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.provider.MediaStore
import android.util.Log
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraEffect
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.MirrorMode
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.effects.OverlayEffect
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.PendingRecording
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

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MediaWahidCamera"
        private const val PREFS_NAME = "media_wahid_camera"
        private const val KEY_TEMPLATE = "selected_template"
        private const val HARD_MIN_FREE_BYTES = 1024L * 1024L * 1024L
        private const val LONG_RECORD_WARNING_BYTES = 8L * 1024L * 1024L * 1024L
    }

    private lateinit var root: FrameLayout
    private lateinit var previewView: PreviewView
    private lateinit var statusText: TextView
    private lateinit var timerText: TextView
    private lateinit var dualTemplateButton: TextView
    private lateinit var mediaTemplateButton: TextView
    private lateinit var switchButton: TextView
    private lateinit var photoButton: TextView
    private lateinit var recordButton: TextView
    private lateinit var torchButton: TextView

    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var preview: Preview? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null

    private lateinit var overlayEffect: OverlayEffect
    private lateinit var watermarkRenderer: LiveWatermarkRenderer

    private var activeRecording: Recording? = null
    private var cameraSelector: CameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

    private var selectedTemplate = WatermarkTemplate.DUAL
    private var lockedRecordingTemplate: WatermarkTemplate? = null

    private var cameraReady = false
    private var watermarkReady = false
    private var effectFailed = false
    private var recordingStarting = false
    private var isRecording = false
    private var finalizing = false
    private var deleteFinalizedOutput = false
    private var torchEnabled = false
    private var lastRecordedDurationNanos = 0L

    private val preferences by lazy {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (hasCameraPermission()) {
            startCamera()
        } else {
            cameraReady = false
            statusText.text = "Izin kamera wajib untuk menjalankan aplikasi"
            refreshControlState()
            Toast.makeText(
                this,
                "Izinkan kamera agar MEDIA WAHID TV Camera dapat digunakan.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        selectedTemplate = WatermarkTemplate.fromStorage(
            preferences.getString(KEY_TEMPLATE, WatermarkTemplate.DUAL.storageValue)
        )

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        buildUi()
        hideSystemBars()
        setupOverlayPipeline()
        setupCameraGestures()
        setupBackHandling()
        updateTemplateUi()
        refreshControlState()

        previewView.post {
            requestPermissionsOrStart()
        }
    }

    override fun onResume() {
        super.onResume()
        if (::root.isInitialized) hideSystemBars()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!isRecording && !recordingStarting && !finalizing) {
            previewView.post {
                updateTargetRotation()
            }
        }
    }

    override fun onDestroy() {
        try {
            activeRecording?.close()
        } catch (_: Throwable) {
        }
        activeRecording = null

        try {
            cameraProvider?.unbindAll()
        } catch (_: Throwable) {
        }

        if (::overlayEffect.isInitialized) {
            try {
                overlayEffect.clearOnDrawListener()
                overlayEffect.close()
            } catch (_: Throwable) {
            }
        }

        if (::watermarkRenderer.isInitialized) {
            try {
                watermarkRenderer.close()
            } catch (_: Throwable) {
            }
        }

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
            implementationMode = PreviewView.ImplementationMode.PERFORMANCE
            setBackgroundColor(Color.BLACK)
        }
        root.addView(previewView, FrameLayout.LayoutParams(-1, -1))

        val topPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(14), dp(18), dp(14), dp(10))
            background = verticalScrim()
        }

        timerText = TextView(this).apply {
            text = "00:00:00"
            setTextColor(Color.WHITE)
            textSize = 21f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(14), dp(7), dp(14), dp(7))
            background = roundedBackground(
                Color.argb(165, 5, 7, 10),
                Color.argb(120, 255, 255, 255),
                22
            )
        }
        topPanel.addView(timerText, LinearLayout.LayoutParams(-2, -2))

        statusText = TextView(this).apply {
            text = "Menyiapkan kamera dan watermark..."
            setTextColor(Color.WHITE)
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        topPanel.addView(statusText, LinearLayout.LayoutParams(-1, -2))

        val templateRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        dualTemplateButton = compactButton("MASJID + MEDIA", false)
        mediaTemplateButton = compactButton("MEDIA ONLY", false)

        templateRow.addView(
            dualTemplateButton,
            LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                marginEnd = dp(5)
            }
        )
        templateRow.addView(
            mediaTemplateButton,
            LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                marginStart = dp(5)
            }
        )
        topPanel.addView(
            templateRow,
            LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(2)
            }
        )

        root.addView(
            topPanel,
            FrameLayout.LayoutParams(-1, -2).apply {
                gravity = Gravity.TOP
            }
        )

        val bottomPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(12), dp(14), dp(22))
            background = bottomScrim()
        }

        val controlsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        switchButton = compactButton("GANTI", false)
        photoButton = compactButton("FOTO", false)
        recordButton = compactButton("REKAM", true)
        torchButton = compactButton("FLASH", false)

        controlsRow.addView(
            switchButton,
            LinearLayout.LayoutParams(0, dp(54), 1f).apply {
                marginEnd = dp(5)
            }
        )
        controlsRow.addView(
            photoButton,
            LinearLayout.LayoutParams(0, dp(54), 1f).apply {
                marginStart = dp(5)
                marginEnd = dp(5)
            }
        )
        controlsRow.addView(
            recordButton,
            LinearLayout.LayoutParams(0, dp(54), 1.2f).apply {
                marginStart = dp(5)
                marginEnd = dp(5)
            }
        )
        controlsRow.addView(
            torchButton,
            LinearLayout.LayoutParams(0, dp(54), 1f).apply {
                marginStart = dp(5)
            }
        )

        bottomPanel.addView(controlsRow, LinearLayout.LayoutParams(-1, -2))

        bottomPanel.addView(
            TextView(this).apply {
                text = "FHD • watermark langsung tertanam • tidak perlu render ulang"
                setTextColor(Color.rgb(196, 204, 214))
                textSize = 11f
                gravity = Gravity.CENTER
                setPadding(0, dp(10), 0, 0)
            },
            LinearLayout.LayoutParams(-1, -2)
        )

        root.addView(
            bottomPanel,
            FrameLayout.LayoutParams(-1, -2).apply {
                gravity = Gravity.BOTTOM
            }
        )

        dualTemplateButton.setOnClickListener {
            selectTemplate(WatermarkTemplate.DUAL)
        }
        mediaTemplateButton.setOnClickListener {
            selectTemplate(WatermarkTemplate.MEDIA_ONLY)
        }
        switchButton.setOnClickListener {
            switchCamera()
        }
        photoButton.setOnClickListener {
            takePhoto()
        }
        recordButton.setOnClickListener {
            if (isRecording || recordingStarting || finalizing) {
                if (isRecording && !finalizing) stopRecording()
            } else {
                startRecording()
            }
        }
        torchButton.setOnClickListener {
            toggleTorch()
        }
    }

    private fun compactButton(label: String, primary: Boolean): TextView {
        return TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = if (primary) 14f else 12f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            background = roundedBackground(
                if (primary) Color.rgb(219, 38, 50) else Color.argb(205, 18, 22, 28),
                if (primary) Color.rgb(240, 66, 76) else Color.rgb(92, 102, 116),
                18
            )
            isAllCaps = false
        }
    }

    private fun setupOverlayPipeline() {
        try {
            watermarkRenderer = LiveWatermarkRenderer(this, previewView).apply {
                template = selectedTemplate
            }

            val targets =
                CameraEffect.PREVIEW or CameraEffect.VIDEO_CAPTURE or CameraEffect.IMAGE_CAPTURE

            overlayEffect = OverlayEffect(
                targets,
                0,
                Handler(Looper.getMainLooper())
            ) { throwable ->
                onOverlayFailure(throwable)
            }

            overlayEffect.setOnDrawListener { frame ->
                val rendered = try {
                    watermarkRenderer.draw(frame)
                } catch (error: Throwable) {
                    onOverlayFailure(error)
                    false
                }

                if (rendered && !watermarkReady) {
                    watermarkReady = true
                    if (!isRecording && !recordingStarting && !finalizing) {
                        statusText.text =
                            "Siap • watermark live aktif • " + freeStorageLabel()
                    }
                    refreshControlState()
                }

                rendered
            }
        } catch (error: Throwable) {
            effectFailed = true
            watermarkReady = false
            statusText.text = "Watermark gagal disiapkan"
            Toast.makeText(
                this,
                error.message ?: "Gagal menyiapkan watermark.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun onOverlayFailure(error: Throwable) {
        Log.e(TAG, "OverlayEffect failure", error)

        if (effectFailed) return

        effectFailed = true
        watermarkReady = false
        deleteFinalizedOutput = activeRecording != null

        if (activeRecording != null) {
            try {
                activeRecording?.stop()
            } catch (_: Throwable) {
            }
        }

        runOnUiThread {
            statusText.text = "Watermark error • rekaman dihentikan demi keamanan hasil"
            refreshControlState()
            Toast.makeText(
                this,
                "Watermark berhenti bekerja. Rekaman dihentikan agar tidak ada video tanpa logo.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun requestPermissionsOrStart() {
        val permissions = mutableListOf<String>()
        if (!hasCameraPermission()) permissions.add(Manifest.permission.CAMERA)
        if (!hasMicrophonePermission()) permissions.add(Manifest.permission.RECORD_AUDIO)

        if (permissions.isEmpty()) {
            startCamera()
        } else {
            permissionLauncher.launch(permissions.toTypedArray())
        }
    }

    private fun startCamera() {
        if (effectFailed || !::overlayEffect.isInitialized) {
            statusText.text = "Kamera tidak dibuka karena watermark belum aman"
            return
        }

        cameraReady = false
        watermarkReady = false
        refreshControlState()
        statusText.text = "Membuka kamera..."

        val future = ProcessCameraProvider.getInstance(this)
        future.addListener(
            {
                try {
                    cameraProvider = future.get()
                    if (preview == null || imageCapture == null || videoCapture == null) {
                        buildUseCases()
                    }
                    bindCamera()
                } catch (error: Throwable) {
                    cameraReady = false
                    statusText.text = "Kamera gagal dibuka"
                    refreshControlState()
                    Toast.makeText(
                        this,
                        error.message ?: "Kamera gagal dibuka.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            },
            ContextCompat.getMainExecutor(this)
        )
    }

    private fun buildUseCases() {
        val rotation = currentRotation()

        preview = Preview.Builder()
            .setTargetRotation(rotation)
            .setMirrorMode(MirrorMode.MIRROR_MODE_ON_FRONT_ONLY)
            .build()
            .also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setTargetRotation(rotation)
            .build()

        videoCapture = buildVideoCapture(rotation)
    }

    private fun buildVideoCapture(rotation: Int): VideoCapture<Recorder> {
        val qualitySelector = QualitySelector.fromOrderedList(
            listOf(Quality.FHD, Quality.HD, Quality.SD),
            FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)
        )

        val recorder = Recorder.Builder()
            .setQualitySelector(qualitySelector)
            .build()

        return VideoCapture.Builder(recorder)
            .setTargetRotation(rotation)
            .setMirrorMode(MirrorMode.MIRROR_MODE_ON_FRONT_ONLY)
            .build()
    }

    private fun bindCamera() {
        val provider = cameraProvider ?: return
        val previewUseCase = preview ?: return
        val photoUseCase = imageCapture ?: return
        val videoUseCase = videoCapture ?: return

        try {
            provider.unbindAll()

            val groupBuilder = UseCaseGroup.Builder()
                .addUseCase(previewUseCase)
                .addUseCase(photoUseCase)
                .addUseCase(videoUseCase)
                .addEffect(overlayEffect)

            previewView.viewPort?.let {
                groupBuilder.setViewPort(it)
            }

            camera = provider.bindToLifecycle(
                this,
                cameraSelector,
                groupBuilder.build()
            )

            cameraReady = true
            torchEnabled = false
            updateTorchUi()

            if (!isRecording && !recordingStarting && !finalizing) {
                statusText.text = "Kamera aktif • menunggu watermark live..."
            }

            refreshControlState()
        } catch (error: Throwable) {
            cameraReady = false
            statusText.text = "Kamera gagal dikonfigurasi"
            refreshControlState()
            Toast.makeText(
                this,
                error.message ?: "Konfigurasi kamera gagal.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun selectTemplate(template: WatermarkTemplate) {
        if (isRecording || recordingStarting || finalizing) {
            Toast.makeText(
                this,
                "Template dikunci selama perekaman.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        val saved = preferences.edit()
            .putString(KEY_TEMPLATE, template.storageValue)
            .commit()

        if (!saved) {
            Toast.makeText(
                this,
                "Template gagal disimpan. Coba lagi.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        selectedTemplate = template
        watermarkRenderer.template = template
        updateTemplateUi()
        statusText.text = "Template aktif • " + template.displayName
    }

    private fun updateTemplateUi() {
        val selectedStroke = Color.rgb(64, 202, 151)
        val selectedFill = Color.argb(220, 21, 34, 31)
        val idleStroke = Color.rgb(88, 99, 114)
        val idleFill = Color.argb(205, 18, 22, 28)

        val dualSelected = selectedTemplate == WatermarkTemplate.DUAL

        dualTemplateButton.background = roundedBackground(
            if (dualSelected) selectedFill else idleFill,
            if (dualSelected) selectedStroke else idleStroke,
            18
        )
        mediaTemplateButton.background = roundedBackground(
            if (!dualSelected) selectedFill else idleFill,
            if (!dualSelected) selectedStroke else idleStroke,
            18
        )

        dualTemplateButton.text =
            if (dualSelected) "✓ MASJID + MEDIA" else "MASJID + MEDIA"
        mediaTemplateButton.text =
            if (!dualSelected) "✓ MEDIA ONLY" else "MEDIA ONLY"
    }

    @SuppressLint("MissingPermission")
    private fun startRecording() {
        val capture = videoCapture ?: return

        if (!cameraReady) {
            Toast.makeText(this, "Kamera belum siap.", Toast.LENGTH_SHORT).show()
            return
        }
        if (!watermarkReady || effectFailed) {
            Toast.makeText(
                this,
                "Watermark belum siap. Rekaman tidak dimulai.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        val freeBytes = freeStorageBytes()
        if (freeBytes in 0 until HARD_MIN_FREE_BYTES) {
            Toast.makeText(
                this,
                "Penyimpanan terlalu penuh. Sisakan minimal 1 GB sebelum merekam.",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        if (freeBytes in HARD_MIN_FREE_BYTES until LONG_RECORD_WARNING_BYTES) {
            Toast.makeText(
                this,
                "Sisa penyimpanan di bawah 8 GB. Target 1,5 jam bisa terpotong jika storage habis.",
                Toast.LENGTH_LONG
            ).show()
        }

        lockedRecordingTemplate = selectedTemplate
        watermarkRenderer.template = selectedTemplate
        deleteFinalizedOutput = false
        effectFailed = false
        recordingStarting = true
        lastRecordedDurationNanos = 0L

        val suffix =
            if (selectedTemplate == WatermarkTemplate.DUAL) "DUAL" else "MEDIA"
        val timestamp = SimpleDateFormat(
            "yyyyMMdd_HHmmss",
            Locale.US
        ).format(System.currentTimeMillis())

        val values = ContentValues().apply {
            put(
                MediaStore.Video.Media.DISPLAY_NAME,
                "MEDIA_WAHID_TV_" + suffix + "_" + timestamp + ".mp4"
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
        )
            .setContentValues(values)
            .build()

        try {
            var pending: PendingRecording = capture.output
                .prepareRecording(this, outputOptions)
                .asPersistentRecording()

            if (hasMicrophonePermission()) {
                pending = pending.withAudioEnabled()
            }

            statusText.text = "Memulai rekaman • template terkunci"
            refreshControlState()

            activeRecording = pending.start(
                ContextCompat.getMainExecutor(this)
            ) { event ->
                handleVideoEvent(event)
            }
        } catch (error: Throwable) {
            activeRecording = null
            recordingStarting = false
            lockedRecordingTemplate = null
            statusText.text = "Rekaman gagal dimulai"
            refreshControlState()
            Toast.makeText(
                this,
                error.message ?: "Rekaman gagal dimulai.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun handleVideoEvent(event: VideoRecordEvent) {
        when (event) {
            is VideoRecordEvent.Start -> {
                recordingStarting = false
                isRecording = true
                finalizing = false
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
                recordButton.text = "STOP"
                statusText.text =
                    "REC • " + (lockedRecordingTemplate ?: selectedTemplate).displayName
                refreshControlState()
            }

            is VideoRecordEvent.Status -> {
                lastRecordedDurationNanos = event.recordingStats.recordedDurationNanos
                timerText.text = formatDuration(lastRecordedDurationNanos)
                statusText.text =
                    "REC • " +
                        formatBytes(event.recordingStats.numBytesRecorded) +
                        " • " +
                        freeStorageLabel()
            }

            is VideoRecordEvent.Finalize -> {
                finalizeRecording(event)
            }

            else -> Unit
        }
    }

    private fun stopRecording() {
        if (!isRecording || finalizing) return

        finalizing = true
        recordButton.text = "MENYIMPAN"
        statusText.text = "Menyelesaikan file..."
        refreshControlState()

        try {
            activeRecording?.stop()
        } catch (error: Throwable) {
            finalizing = false
            statusText.text = "Gagal menghentikan rekaman dengan aman"
            Toast.makeText(
                this,
                error.message ?: "Gagal menghentikan rekaman.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun finalizeRecording(event: VideoRecordEvent.Finalize) {
        val outputUri = event.outputResults.outputUri
        val wasEffectFailure = deleteFinalizedOutput || effectFailed

        try {
            activeRecording?.close()
        } catch (_: Throwable) {
        }

        activeRecording = null
        recordingStarting = false
        isRecording = false
        finalizing = false
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR

        val duration = lastRecordedDurationNanos
        lockedRecordingTemplate = null
        watermarkRenderer.template = selectedTemplate

        if (wasEffectFailure) {
            deleteOutput(outputUri)
            statusText.text = "Video dibuang karena watermark tidak terjamin"
            Toast.makeText(
                this,
                "Video tidak disimpan karena sistem watermark sempat gagal.",
                Toast.LENGTH_LONG
            ).show()
        } else if (!event.hasError()) {
            statusText.text =
                "VIDEO TERSIMPAN ✓ • " + formatDuration(duration) + " • watermark tertanam"
            Toast.makeText(
                this,
                "VIDEO TERSIMPAN ✓",
                Toast.LENGTH_LONG
            ).show()
        } else {
            handleRecordingError(event, outputUri)
        }

        deleteFinalizedOutput = false
        timerText.text = formatDuration(duration)
        recordButton.text = "REKAM"
        refreshControlState()

        if (
            event.error == VideoRecordEvent.Finalize.ERROR_RECORDER_ERROR ||
            event.error == VideoRecordEvent.Finalize.ERROR_ENCODING_FAILED
        ) {
            rebuildVideoPipeline()
        }
    }

    private fun handleRecordingError(
        event: VideoRecordEvent.Finalize,
        outputUri: Uri,
    ) {
        val message = when (event.error) {
            VideoRecordEvent.Finalize.ERROR_INSUFFICIENT_STORAGE ->
                "Rekaman berhenti karena penyimpanan habis. Bagian yang sempat tersimpan dipertahankan."

            VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE ->
                "Rekaman berhenti karena kamera menjadi tidak aktif."

            VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED ->
                "Rekaman dihentikan oleh batas file perangkat."

            VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED ->
                "Rekaman dihentikan oleh batas durasi perangkat."

            VideoRecordEvent.Finalize.ERROR_ENCODING_FAILED ->
                "Encoder video gagal. File rusak dibuang dan kamera dipulihkan."

            VideoRecordEvent.Finalize.ERROR_RECORDER_ERROR ->
                "Recorder mengalami error. File rusak dibuang dan kamera dipulihkan."

            VideoRecordEvent.Finalize.ERROR_NO_VALID_DATA ->
                "Tidak ada data video valid. File dibuang."

            else ->
                "Rekaman berhenti karena error kamera."
        }

        val deleteBrokenFile =
            event.error == VideoRecordEvent.Finalize.ERROR_ENCODING_FAILED ||
                event.error == VideoRecordEvent.Finalize.ERROR_RECORDER_ERROR ||
                event.error == VideoRecordEvent.Finalize.ERROR_NO_VALID_DATA ||
                event.error == VideoRecordEvent.Finalize.ERROR_INVALID_OUTPUT_OPTIONS ||
                event.error == VideoRecordEvent.Finalize.ERROR_UNKNOWN

        if (deleteBrokenFile) {
            deleteOutput(outputUri)
        }

        statusText.text = message
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun rebuildVideoPipeline() {
        if (activeRecording != null) return

        try {
            videoCapture = buildVideoCapture(currentRotation())
            bindCamera()
        } catch (error: Throwable) {
            cameraReady = false
            statusText.text = "Recorder belum berhasil dipulihkan"
            refreshControlState()
        }
    }

    private fun deleteOutput(uri: Uri) {
        if (uri == Uri.EMPTY) return
        try {
            contentResolver.delete(uri, null, null)
        } catch (_: Throwable) {
        }
    }

    private fun takePhoto() {
        val capture = imageCapture ?: return

        if (!cameraReady || !watermarkReady || effectFailed) {
            Toast.makeText(
                this,
                "Kamera atau watermark belum siap.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        if (isRecording || recordingStarting || finalizing) {
            Toast.makeText(
                this,
                "Foto dinonaktifkan selama video agar rekaman panjang tetap stabil.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        watermarkRenderer.template = selectedTemplate
        photoButton.isEnabled = false

        val suffix =
            if (selectedTemplate == WatermarkTemplate.DUAL) "DUAL" else "MEDIA"
        val timestamp = SimpleDateFormat(
            "yyyyMMdd_HHmmss",
            Locale.US
        ).format(System.currentTimeMillis())

        val values = ContentValues().apply {
            put(
                MediaStore.Images.Media.DISPLAY_NAME,
                "MEDIA_WAHID_TV_" + suffix + "_" + timestamp + ".jpg"
            )
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                Environment.DIRECTORY_PICTURES + "/MEDIA WAHID TV"
            )
        }

        val output = ImageCapture.OutputFileOptions.Builder(
            contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            values
        ).build()

        capture.takePicture(
            output,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(
                    outputFileResults: ImageCapture.OutputFileResults
                ) {
                    statusText.text =
                        "FOTO TERSIMPAN ✓ • " + selectedTemplate.displayName
                    Toast.makeText(
                        this@MainActivity,
                        "FOTO TERSIMPAN ✓",
                        Toast.LENGTH_SHORT
                    ).show()
                    refreshControlState()
                }

                override fun onError(exception: ImageCaptureException) {
                    statusText.text = "Foto gagal disimpan"
                    Toast.makeText(
                        this@MainActivity,
                        exception.message ?: "Foto gagal.",
                        Toast.LENGTH_LONG
                    ).show()
                    refreshControlState()
                }
            }
        )
    }

    private fun switchCamera() {
        val provider = cameraProvider ?: return

        val target =
            if (cameraSelector == CameraSelector.DEFAULT_BACK_CAMERA) {
                CameraSelector.DEFAULT_FRONT_CAMERA
            } else {
                CameraSelector.DEFAULT_BACK_CAMERA
            }

        val available = try {
            provider.hasCamera(target)
        } catch (_: Throwable) {
            false
        }

        if (!available) {
            Toast.makeText(
                this,
                "Kamera tersebut tidak tersedia di perangkat ini.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        torchEnabled = false
        updateTorchUi()
        cameraSelector = target

        try {
            bindCamera()

            statusText.text =
                if (isRecording) {
                    "REC • kamera diganti • file tetap satu"
                } else {
                    "Kamera diganti • watermark tetap aktif"
                }
        } catch (error: Throwable) {
            Toast.makeText(
                this,
                error.message ?: "Gagal mengganti kamera.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun toggleTorch() {
        val activeCamera = camera ?: return

        if (!activeCamera.cameraInfo.hasFlashUnit()) {
            Toast.makeText(
                this,
                "Flash tidak tersedia di kamera ini.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        torchEnabled = !torchEnabled
        activeCamera.cameraControl.enableTorch(torchEnabled)
        updateTorchUi()
    }

    private fun updateTorchUi() {
        torchButton.text = if (torchEnabled) "FLASH ON" else "FLASH"
        torchButton.alpha =
            if (camera?.cameraInfo?.hasFlashUnit() == true) 1f else 0.5f
    }

    private fun setupCameraGestures() {
        val scaleDetector = ScaleGestureDetector(
            this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val activeCamera = camera ?: return false
                    val zoomState = activeCamera.cameraInfo.zoomState.value ?: return false
                    val newRatio = (
                        zoomState.zoomRatio * detector.scaleFactor
                    ).coerceIn(
                        zoomState.minZoomRatio,
                        zoomState.maxZoomRatio
                    )
                    activeCamera.cameraControl.setZoomRatio(newRatio)
                    return true
                }
            }
        )

        val gestureDetector = GestureDetector(
            this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean = true

                override fun onSingleTapUp(e: MotionEvent): Boolean {
                    focusAt(e.x, e.y)
                    return true
                }
            }
        )

        previewView.setOnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event)
            gestureDetector.onTouchEvent(event)
            true
        }
    }

    private fun focusAt(x: Float, y: Float) {
        val activeCamera = camera ?: return
        val point = previewView.meteringPointFactory.createPoint(x, y)
        val action = FocusMeteringAction.Builder(
            point,
            FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
        )
            .setAutoCancelDuration(3, TimeUnit.SECONDS)
            .build()

        activeCamera.cameraControl.startFocusAndMetering(action)
    }

    private fun setupBackHandling() {
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (activeRecording != null || recordingStarting || finalizing) {
                        Toast.makeText(
                            this@MainActivity,
                            "Hentikan rekaman dulu sebelum keluar.",
                            Toast.LENGTH_SHORT
                        ).show()
                        return
                    }

                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        )
    }

    private fun updateTargetRotation() {
        val rotation = currentRotation()
        preview?.targetRotation = rotation
        imageCapture?.targetRotation = rotation
        videoCapture?.targetRotation = rotation
    }

    private fun currentRotation(): Int {
        return previewView.display?.rotation ?: Surface.ROTATION_0
    }

    private fun refreshControlState() {
        val idleReady = cameraReady && watermarkReady && !effectFailed
        val busy = recordingStarting || finalizing

        recordButton.isEnabled =
            (idleReady && !busy) || (isRecording && !finalizing)
        recordButton.alpha = if (recordButton.isEnabled) 1f else 0.55f

        photoButton.isEnabled =
            idleReady && !isRecording && !recordingStarting && !finalizing
        photoButton.alpha = if (photoButton.isEnabled) 1f else 0.55f

        dualTemplateButton.isEnabled =
            !isRecording && !recordingStarting && !finalizing
        mediaTemplateButton.isEnabled =
            !isRecording && !recordingStarting && !finalizing
        dualTemplateButton.alpha =
            if (dualTemplateButton.isEnabled) 1f else 0.55f
        mediaTemplateButton.alpha =
            if (mediaTemplateButton.isEnabled) 1f else 0.55f

        switchButton.isEnabled = cameraReady && !finalizing
        switchButton.alpha = if (switchButton.isEnabled) 1f else 0.55f

        torchButton.isEnabled = cameraReady && !finalizing
        torchButton.alpha =
            if (torchButton.isEnabled && camera?.cameraInfo?.hasFlashUnit() == true) {
                1f
            } else {
                0.5f
            }

        if (!isRecording && !recordingStarting && !finalizing) {
            recordButton.text = "REKAM"
        }
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasMicrophonePermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun freeStorageBytes(): Long {
        return try {
            val path = (externalCacheDir ?: cacheDir).absolutePath
            StatFs(path).availableBytes
        } catch (_: Throwable) {
            -1L
        }
    }

    private fun freeStorageLabel(): String {
        val bytes = freeStorageBytes()
        if (bytes < 0L) return "storage tersedia"
        val gb = bytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
        return String.format(Locale.US, "sisa %.1f GB", gb)
    }

    private fun formatDuration(durationNanos: Long): String {
        val totalSeconds = (durationNanos / 1_000_000_000L).coerceAtLeast(0L)
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L

        return String.format(
            Locale.US,
            "%02d:%02d:%02d",
            hours,
            minutes,
            seconds
        )
    }

    private fun formatBytes(bytes: Long): String {
        val mb = bytes.toDouble() / (1024.0 * 1024.0)
        return if (mb < 1024.0) {
            String.format(Locale.US, "%.0f MB", mb)
        } else {
            String.format(Locale.US, "%.2f GB", mb / 1024.0)
        }
    }

    private fun roundedBackground(
        fill: Int,
        stroke: Int,
        radiusDp: Int,
    ): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(radiusDp).toFloat()
            setColor(fill)
            setStroke(dp(1), stroke)
        }
    }

    private fun verticalScrim(): GradientDrawable {
        return GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(
                Color.argb(215, 0, 0, 0),
                Color.argb(120, 0, 0, 0),
                Color.TRANSPARENT
            )
        )
    }

    private fun bottomScrim(): GradientDrawable {
        return GradientDrawable(
            GradientDrawable.Orientation.BOTTOM_TOP,
            intArrayOf(
                Color.argb(235, 0, 0, 0),
                Color.argb(160, 0, 0, 0),
                Color.TRANSPARENT
            )
        )
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, root).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }
}
