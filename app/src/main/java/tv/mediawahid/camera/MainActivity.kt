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
import android.view.Gravity
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
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    companion object {
        private const val PREFS_NAME = "media_wahid_camera"
        private const val KEY_TEMPLATE = "selected_template"

        // Absolute guard only. A 90-minute FHD recording normally needs much more
        // free space; the app warns below 12 GB but does not impose an artificial
        // duration or file-size cap.
        private const val MIN_START_FREE_BYTES = 2L * 1024L * 1024L * 1024L
        private const val NINETY_MINUTE_WARNING_BYTES = 12L * 1024L * 1024L * 1024L
    }

    private lateinit var root: FrameLayout
    private lateinit var previewView: PreviewView
    private lateinit var statusText: TextView
    private lateinit var storageText: TextView
    private lateinit var recordButton: TextView
    private lateinit var photoButton: TextView
    private lateinit var switchButton: TextView
    private lateinit var dualButton: TextView
    private lateinit var mediaButton: TextView

    private val preferences by lazy {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private var cameraProvider: ProcessCameraProvider? = null
    private var boundCamera: Camera? = null
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var cameraSwitchInProgress = false

    private lateinit var previewUseCase: Preview
    private lateinit var imageCapture: ImageCapture
    private lateinit var videoCapture: VideoCapture<Recorder>
    private lateinit var overlayEffect: OverlayEffect
    private lateinit var liveWatermarkRenderer: LiveWatermarkRenderer

    private var selectedTemplate = WatermarkTemplate.DUAL
    private var recordingTemplate = WatermarkTemplate.DUAL
    private var activeRecording: Recording? = null
    private var stoppingRecording = false
    private var effectFailed = false
    private var cameraReady = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val cameraGranted =
            grants[Manifest.permission.CAMERA] == true ||
                hasPermission(Manifest.permission.CAMERA)

        if (!cameraGranted) {
            statusText.text = "IZIN KAMERA WAJIB"
            Toast.makeText(
                this,
                "Izin kamera ditolak. Aplikasi tidak dapat merekam.",
                Toast.LENGTH_LONG
            ).show()
            return@registerForActivityResult
        }

        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
            Toast.makeText(
                this,
                "Izin mikrofon belum diberikan. Video tetap bisa direkam tanpa suara.",
                Toast.LENGTH_LONG
            ).show()
        }

        initializeCamera()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        selectedTemplate = WatermarkTemplate.fromStorage(
            preferences.getString(KEY_TEMPLATE, WatermarkTemplate.DUAL.storageValue)
        )
        recordingTemplate = selectedTemplate

        buildUi()
        applyTemplateSelection()
        hideSystemBars()
        updateStorageLabel()

        liveWatermarkRenderer = LiveWatermarkRenderer(this, previewView).apply {
            template = selectedTemplate
        }

        setupOverlayEffect()
        setupActions()
        setupBackHandling()
        ensurePermissionsAndStart()
    }

    override fun onResume() {
        super.onResume()
        if (::root.isInitialized) hideSystemBars()
        updateStorageLabel()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        if (activeRecording == null && ::previewUseCase.isInitialized) {
            val rotation = safeDisplayRotation()
            previewUseCase.targetRotation = rotation
            imageCapture.targetRotation = rotation
            videoCapture.targetRotation = rotation
        }
    }

    override fun onDestroy() {
        try {
            activeRecording?.stop()
        } catch (_: Throwable) {
        }
        try {
            activeRecording?.close()
        } catch (_: Throwable) {
        }
        activeRecording = null

        cameraProvider?.unbindAll()

        if (::overlayEffect.isInitialized) {
            try {
                overlayEffect.clearOnDrawListener()
                overlayEffect.close()
            } catch (_: Throwable) {
            }
        }

        if (::liveWatermarkRenderer.isInitialized) {
            try {
                liveWatermarkRenderer.close()
            } catch (_: Throwable) {
            }
        }

        super.onDestroy()
    }

    private fun setupActions() {
        recordButton.setOnClickListener {
            if (activeRecording == null) startRecording() else stopRecording()
        }

        photoButton.setOnClickListener {
            takePhoto()
        }

        switchButton.setOnClickListener {
            switchCamera()
        }

        dualButton.setOnClickListener {
            selectTemplate(WatermarkTemplate.DUAL)
        }

        mediaButton.setOnClickListener {
            selectTemplate(WatermarkTemplate.MEDIA_ONLY)
        }
    }

    private fun setupBackHandling() {
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (activeRecording != null || stoppingRecording) {
                        Toast.makeText(
                            this@MainActivity,
                            "Hentikan rekaman dulu supaya file tersimpan dengan aman.",
                            Toast.LENGTH_LONG
                        ).show()
                        return
                    }

                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        )
    }

    private fun ensurePermissionsAndStart() {
        if (hasPermission(Manifest.permission.CAMERA)) {
            initializeCamera()

            if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
                permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
            }
            return
        }

        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO
            )
        )
    }

    private fun setupOverlayEffect() {
        overlayEffect = OverlayEffect(
            CameraEffect.PREVIEW or
                CameraEffect.VIDEO_CAPTURE or
                CameraEffect.IMAGE_CAPTURE,
            0,
            mainHandler
        ) { error ->
            effectFailed = true
            cameraReady = false

            try {
                activeRecording?.stop()
            } catch (_: Throwable) {
            }

            statusText.text =
                "WATERMARK ENGINE ERROR • CAPTURE DIHENTIKAN DEMI KEAMANAN"
            recordButton.isEnabled = false
            photoButton.isEnabled = false

            Toast.makeText(
                this,
                "Watermark engine error: " + (error.message ?: "unknown"),
                Toast.LENGTH_LONG
            ).show()
        }

        overlayEffect.setOnDrawListener { frame ->
            liveWatermarkRenderer.draw(frame)
        }
    }

    private fun initializeCamera() {
        if (!hasPermission(Manifest.permission.CAMERA) || effectFailed) return

        if (!::previewUseCase.isInitialized) {
            createUseCases()
        }

        val future = ProcessCameraProvider.getInstance(this)
        future.addListener(
            {
                try {
                    cameraProvider = future.get()
                    previewView.post {
                        bindCurrentCamera(showError = true)
                    }
                } catch (error: Throwable) {
                    cameraReady = false
                    statusText.text = "KAMERA GAGAL DISIAPKAN"
                    updateControlState()

                    Toast.makeText(
                        this,
                        error.message ?: "Kamera gagal disiapkan.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            },
            ContextCompat.getMainExecutor(this)
        )
    }

    private fun createUseCases() {
        val rotation = safeDisplayRotation()

        previewUseCase = Preview.Builder()
            .setTargetRotation(rotation)
            .build()
            .also { it.surfaceProvider = previewView.surfaceProvider }

        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setTargetRotation(rotation)
            .build()

        videoCapture = createVideoCapture(rotation)
    }

    private fun createVideoCapture(rotation: Int): VideoCapture<Recorder> {
        val recorder = Recorder.Builder()
            .setQualitySelector(
                QualitySelector.from(
                    Quality.FHD,
                    FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)
                )
            )
            .build()

        return VideoCapture.withOutput(recorder).also {
            it.targetRotation = rotation
        }
    }

    private fun bindCurrentCamera(showError: Boolean): Boolean {
        val provider = cameraProvider ?: return false
        if (effectFailed) return false

        val selector = CameraSelector.Builder()
            .requireLensFacing(lensFacing)
            .build()

        return try {
            if (!provider.hasCamera(selector)) {
                error("Kamera yang dipilih tidak tersedia")
            }

            provider.unbindAll()

            val groupBuilder = UseCaseGroup.Builder()
                .addUseCase(previewUseCase)
                .addUseCase(imageCapture)
                .addUseCase(videoCapture)
                .addEffect(overlayEffect)

            previewView.viewPort?.let { groupBuilder.setViewPort(it) }

            boundCamera = provider.bindToLifecycle(
                this,
                selector,
                groupBuilder.build()
            )

            cameraReady = true
            cameraSwitchInProgress = false

            if (activeRecording != null) {
                statusText.text =
                    "REKAMAN LANJUT ✓ • KAMERA BERHASIL DIGANTI"
            } else {
                statusText.text =
                    "SIAP • FHD 1080p • " + selectedTemplate.displayName
            }

            updateControlState()
            true
        } catch (error: Throwable) {
            cameraReady = false
            cameraSwitchInProgress = false
            updateControlState()

            if (showError) {
                statusText.text = "KAMERA GAGAL DIBUKA"
                Toast.makeText(
                    this,
                    "Kamera gagal dibuka: " + (error.message ?: "unknown"),
                    Toast.LENGTH_LONG
                ).show()
            }

            false
        }
    }

    private fun rebuildRecorderAfterFailure() {
        if (activeRecording != null || effectFailed) return

        videoCapture = createVideoCapture(safeDisplayRotation())
        bindCurrentCamera(showError = true)
    }

    private fun selectTemplate(template: WatermarkTemplate) {
        if (activeRecording != null || stoppingRecording) {
            Toast.makeText(
                this,
                "Template dikunci selama rekaman.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        val persisted = preferences.edit()
            .putString(KEY_TEMPLATE, template.storageValue)
            .commit()

        if (!persisted) {
            Toast.makeText(
                this,
                "Template gagal disimpan. Coba lagi.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        selectedTemplate = template
        recordingTemplate = template
        liveWatermarkRenderer.template = template
        applyTemplateSelection()

        statusText.text =
            "SIAP • TEMPLATE: " + template.displayName
    }

    private fun applyTemplateSelection() {
        val selectedFill = Color.rgb(24, 88, 67)
        val selectedStroke = Color.rgb(100, 232, 178)
        val idleFill = Color.argb(210, 12, 16, 20)
        val idleStroke = Color.rgb(78, 88, 101)

        val dual = selectedTemplate == WatermarkTemplate.DUAL

        dualButton.background = roundedBackground(
            if (dual) selectedFill else idleFill,
            if (dual) selectedStroke else idleStroke,
            18
        )

        mediaButton.background = roundedBackground(
            if (!dual) selectedFill else idleFill,
            if (!dual) selectedStroke else idleStroke,
            18
        )

        dualButton.text = if (dual) "✓ MASJID + MEDIA" else "MASJID + MEDIA"
        mediaButton.text = if (!dual) "✓ MEDIA WAHID TV" else "MEDIA WAHID TV"
    }

    @ExperimentalPersistentRecording
    @SuppressLint("MissingPermission")
    private fun startRecording() {
        if (!cameraReady || effectFailed || stoppingRecording) {
            Toast.makeText(
                this,
                "Kamera belum siap.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        if (activeRecording != null) return

        val free = availableStorageBytes()

        if (free in 0 until MIN_START_FREE_BYTES) {
            Toast.makeText(
                this,
                "Ruang kosong kurang dari 2 GB. Kosongkan penyimpanan dulu.",
                Toast.LENGTH_LONG
            ).show()
            statusText.text = "STORAGE TIDAK CUKUP UNTUK MULAI"
            return
        }

        if (free in MIN_START_FREE_BYTES until NINETY_MINUTE_WARNING_BYTES) {
            Toast.makeText(
                this,
                "Sisa storage di bawah 12 GB. Rekaman 1,5 jam mungkin tidak cukup.",
                Toast.LENGTH_LONG
            ).show()
        }

        recordingTemplate = selectedTemplate
        liveWatermarkRenderer.template = recordingTemplate

        val suffix =
            if (recordingTemplate == WatermarkTemplate.DUAL) "DUAL" else "MEDIA"

        val timestamp = SimpleDateFormat(
            "yyyyMMdd_HHmmss",
            Locale.US
        ).format(Date())

        val contentValues = ContentValues().apply {
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

        val output = MediaStoreOutputOptions.Builder(
            contentResolver,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        )
            .setContentValues(contentValues)
            .build()

        var pending = videoCapture.output
            .prepareRecording(this, output)
            .asPersistentRecording()

        val audioEnabled = hasPermission(Manifest.permission.RECORD_AUDIO)
        if (audioEnabled) {
            pending = pending.withAudioEnabled()
        }

        stoppingRecording = false
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
        updateControlState(recordingStarting = true)

        try {
            activeRecording = pending.start(
                ContextCompat.getMainExecutor(this)
            ) { event ->
                handleVideoEvent(event, audioEnabled)
            }
        } catch (error: Throwable) {
            activeRecording = null
            stoppingRecording = false
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
            updateControlState()

            statusText.text = "GAGAL MEMULAI REKAMAN"
            Toast.makeText(
                this,
                error.message ?: "Gagal memulai rekaman.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun handleVideoEvent(
        event: VideoRecordEvent,
        audioEnabled: Boolean,
    ) {
        when (event) {
            is VideoRecordEvent.Start -> {
                recordButton.text = "STOP"
                recordButton.isEnabled = true

                statusText.text =
                    "REC ● 00:00:00 • " +
                        recordingTemplate.displayName +
                        if (audioEnabled) " • AUDIO ON" else " • AUDIO OFF"

                updateControlState()
            }

            is VideoRecordEvent.Status -> {
                showRecordingStatus(
                    event.recordingStats.recordedDurationNanos,
                    event.recordingStats.numBytesRecorded
                )
            }

            is VideoRecordEvent.Finalize -> {
                finalizeRecording(event)
            }

            else -> Unit
        }
    }

    private fun finalizeRecording(event: VideoRecordEvent.Finalize) {
        val finishedRecording = activeRecording
        activeRecording = null
        stoppingRecording = false

        try {
            finishedRecording?.close()
        } catch (_: Throwable) {
        }

        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        updateControlState()
        updateStorageLabel()

        if (!event.hasError()) {
            statusText.text =
                "VIDEO TERSIMPAN ✓ • " + recordingTemplate.displayName

            Toast.makeText(
                this,
                "Video tersimpan langsung di Galeri ✓",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val error = event.error
        val uri = event.outputResults.outputUri

        if (isCorruptOutputError(error)) {
            if (uri != Uri.EMPTY) {
                try {
                    contentResolver.delete(uri, null, null)
                } catch (_: Throwable) {
                }
            }

            statusText.text =
                "REKAMAN GAGAL • " + recordingErrorLabel(error)

            Toast.makeText(
                this,
                "File tidak dipakai karena " + recordingErrorLabel(error),
                Toast.LENGTH_LONG
            ).show()
        } else {
            statusText.text =
                "VIDEO PARSIAL TERSIMPAN • " + recordingErrorLabel(error)

            Toast.makeText(
                this,
                "Rekaman berhenti, bagian yang valid tetap disimpan.",
                Toast.LENGTH_LONG
            ).show()
        }

        if (
            error == VideoRecordEvent.Finalize.ERROR_RECORDER_ERROR ||
            error == VideoRecordEvent.Finalize.ERROR_ENCODING_FAILED ||
            error == VideoRecordEvent.Finalize.ERROR_UNKNOWN
        ) {
            rebuildRecorderAfterFailure()
        }
    }

    private fun isCorruptOutputError(error: Int): Boolean =
        error == VideoRecordEvent.Finalize.ERROR_UNKNOWN ||
            error == VideoRecordEvent.Finalize.ERROR_INVALID_OUTPUT_OPTIONS ||
            error == VideoRecordEvent.Finalize.ERROR_ENCODING_FAILED ||
            error == VideoRecordEvent.Finalize.ERROR_RECORDER_ERROR ||
            error == VideoRecordEvent.Finalize.ERROR_NO_VALID_DATA ||
            error == VideoRecordEvent.Finalize.ERROR_RECORDING_GARBAGE_COLLECTED

    private fun stopRecording() {
        val recording = activeRecording ?: return
        if (stoppingRecording) return

        stoppingRecording = true
        recordButton.text = "MENYIMPAN..."
        recordButton.isEnabled = false
        statusText.text = "MENYELESAIKAN FILE VIDEO..."

        try {
            recording.stop()
        } catch (error: Throwable) {
            stoppingRecording = false
            recordButton.isEnabled = true
            recordButton.text = "STOP"

            Toast.makeText(
                this,
                error.message ?: "Gagal menghentikan rekaman dengan aman.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun showRecordingStatus(
        durationNanos: Long,
        bytes: Long,
    ) {
        statusText.text =
            "REC ● " + formatDuration(durationNanos) +
                " • " + formatBytes(bytes) +
                " • " + recordingTemplate.displayName

        updateStorageLabel()
    }

    private fun switchCamera() {
        if (!cameraReady || cameraSwitchInProgress || stoppingRecording) return

        val provider = cameraProvider ?: return
        val oldFacing = lensFacing
        val newFacing =
            if (oldFacing == CameraSelector.LENS_FACING_BACK) {
                CameraSelector.LENS_FACING_FRONT
            } else {
                CameraSelector.LENS_FACING_BACK
            }

        val newSelector = CameraSelector.Builder()
            .requireLensFacing(newFacing)
            .build()

        val available = try {
            provider.hasCamera(newSelector)
        } catch (_: Throwable) {
            false
        }

        if (!available) {
            Toast.makeText(
                this,
                "Kamera tersebut tidak tersedia.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        cameraSwitchInProgress = true
        lensFacing = newFacing
        switchButton.isEnabled = false

        val switched = bindCurrentCamera(showError = false)

        if (!switched) {
            lensFacing = oldFacing
            bindCurrentCamera(showError = true)

            Toast.makeText(
                this,
                "Gagal mengganti kamera. Kamera sebelumnya dipulihkan.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun takePhoto() {
        if (!cameraReady || activeRecording != null || stoppingRecording || effectFailed) {
            return
        }

        val template = selectedTemplate
        liveWatermarkRenderer.template = template

        photoButton.isEnabled = false
        statusText.text = "MENGAMBIL FOTO • " + template.displayName

        val suffix =
            if (template == WatermarkTemplate.DUAL) "DUAL" else "MEDIA"

        val values = ContentValues().apply {
            put(
                MediaStore.Images.Media.DISPLAY_NAME,
                "MEDIA_WAHID_TV_" + suffix + "_" +
                    System.currentTimeMillis() + ".jpg"
            )
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                Environment.DIRECTORY_PICTURES + "/MEDIA WAHID TV"
            )
        }

        val outputOptions = ImageCapture.OutputFileOptions.Builder(
            contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            values
        ).build()

        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(
                    outputFileResults: ImageCapture.OutputFileResults
                ) {
                    photoButton.isEnabled = true
                    statusText.text =
                        "FOTO TERSIMPAN ✓ • " + template.displayName

                    Toast.makeText(
                        this@MainActivity,
                        "Foto tersimpan di Galeri ✓",
                        Toast.LENGTH_LONG
                    ).show()
                }

                override fun onError(exception: ImageCaptureException) {
                    photoButton.isEnabled = true
                    statusText.text = "FOTO GAGAL"

                    Toast.makeText(
                        this@MainActivity,
                        exception.message ?: "Foto gagal diambil.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        )
    }

    private fun updateControlState(recordingStarting: Boolean = false) {
        val recording = activeRecording != null || recordingStarting

        recordButton.isEnabled =
            cameraReady && !effectFailed && !stoppingRecording

        photoButton.isEnabled =
            cameraReady && !effectFailed && !recording && !stoppingRecording

        dualButton.isEnabled = !recording && !stoppingRecording
        mediaButton.isEnabled = !recording && !stoppingRecording

        switchButton.isEnabled =
            cameraReady && !cameraSwitchInProgress && !stoppingRecording

        if (!recording && !stoppingRecording) {
            recordButton.text = "REC"
        }
    }

    private fun recordingErrorLabel(error: Int): String =
        when (error) {
            VideoRecordEvent.Finalize.ERROR_UNKNOWN ->
                "error tidak dikenal"
            VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED ->
                "batas file sistem tercapai"
            VideoRecordEvent.Finalize.ERROR_INSUFFICIENT_STORAGE ->
                "storage habis"
            VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE ->
                "kamera berhenti mengirim frame"
            VideoRecordEvent.Finalize.ERROR_INVALID_OUTPUT_OPTIONS ->
                "output tidak valid"
            VideoRecordEvent.Finalize.ERROR_ENCODING_FAILED ->
                "encoder gagal"
            VideoRecordEvent.Finalize.ERROR_RECORDER_ERROR ->
                "recorder error"
            VideoRecordEvent.Finalize.ERROR_NO_VALID_DATA ->
                "tidak ada data video valid"
            VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED ->
                "batas durasi sistem tercapai"
            VideoRecordEvent.Finalize.ERROR_RECORDING_GARBAGE_COLLECTED ->
                "sesi recording terlepas"
            else ->
                "kode error $error"
        }

    private fun availableStorageBytes(): Long =
        try {
            StatFs(Environment.getExternalStorageDirectory().absolutePath).availableBytes
        } catch (_: Throwable) {
            -1L
        }

    private fun updateStorageLabel() {
        if (!::storageText.isInitialized) return

        val bytes = availableStorageBytes()

        storageText.text =
            if (bytes >= 0L) {
                "Storage kosong: " + formatBytes(bytes) +
                    " • target FHD 1080p / 90+ menit"
            } else {
                "FHD 1080p • tanpa batas durasi buatan aplikasi"
            }
    }

    private fun formatDuration(nanos: Long): String {
        val totalSeconds = (nanos / 1_000_000_000L).coerceAtLeast(0L)
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60

        return String.format(
            Locale.US,
            "%02d:%02d:%02d",
            hours,
            minutes,
            seconds
        )
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 0L) return "?"

        val gb = bytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
        return String.format(Locale.US, "%.1f GB", gb)
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(
            this,
            permission
        ) == PackageManager.PERMISSION_GRANTED

    @Suppress("DEPRECATION")
    private fun safeDisplayRotation(): Int =
        previewView.display?.rotation ?: windowManager.defaultDisplay.rotation

    private fun buildUi() {
        root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }
        setContentView(root)

        previewView = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            setBackgroundColor(Color.BLACK)
        }

        root.addView(
            previewView,
            FrameLayout.LayoutParams(-1, -1)
        )

        val topPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(12))
            background = roundedBackground(
                Color.argb(205, 6, 8, 11),
                Color.argb(90, 255, 255, 255),
                18
            )
        }

        root.addView(
            topPanel,
            FrameLayout.LayoutParams(-1, -2).apply {
                gravity = Gravity.TOP
                setMargins(dp(12), dp(18), dp(12), 0)
            }
        )

        topPanel.addView(
            TextView(this).apply {
                text = "MEDIA WAHID TV CAMERA • FINAL"
                setTextColor(Color.WHITE)
                textSize = 17f
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
            }
        )

        statusText = TextView(this).apply {
            text = "MENYIAPKAN KAMERA..."
            setTextColor(Color.rgb(212, 220, 230))
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(4))
        }
        topPanel.addView(statusText)

        storageText = TextView(this).apply {
            setTextColor(Color.rgb(152, 165, 180))
            textSize = 11f
            gravity = Gravity.CENTER
        }
        topPanel.addView(storageText)

        val templateRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, 0)
        }
        topPanel.addView(templateRow)

        dualButton = compactButton("MASJID + MEDIA")
        mediaButton = compactButton("MEDIA WAHID TV")

        templateRow.addView(
            dualButton,
            LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                marginEnd = dp(5)
            }
        )

        templateRow.addView(
            mediaButton,
            LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                marginStart = dp(5)
            }
        )

        val bottomPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(14), dp(16), dp(18))
            background = roundedBackground(
                Color.argb(215, 6, 8, 11),
                Color.argb(100, 255, 255, 255),
                22
            )
        }

        root.addView(
            bottomPanel,
            FrameLayout.LayoutParams(-1, -2).apply {
                gravity = Gravity.BOTTOM
                setMargins(dp(12), 0, dp(12), dp(20))
            }
        )

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        bottomPanel.addView(controls)

        photoButton = actionButton(
            text = "FOTO",
            fill = Color.rgb(34, 42, 52),
            stroke = Color.rgb(92, 105, 122)
        )

        recordButton = actionButton(
            text = "REC",
            fill = Color.rgb(214, 38, 48),
            stroke = Color.rgb(244, 80, 90)
        )

        switchButton = actionButton(
            text = "BALIK",
            fill = Color.rgb(34, 42, 52),
            stroke = Color.rgb(92, 105, 122)
        )

        controls.addView(
            photoButton,
            LinearLayout.LayoutParams(0, dp(58), 1f).apply {
                marginEnd = dp(7)
            }
        )

        controls.addView(
            recordButton,
            LinearLayout.LayoutParams(0, dp(58), 1.2f).apply {
                marginStart = dp(7)
                marginEnd = dp(7)
            }
        )

        controls.addView(
            switchButton,
            LinearLayout.LayoutParams(0, dp(58), 1f).apply {
                marginStart = dp(7)
            }
        )

        bottomPanel.addView(
            TextView(this).apply {
                text = "Watermark live • video langsung ke Galeri • tanpa render ulang"
                setTextColor(Color.rgb(151, 164, 179))
                textSize = 11f
                gravity = Gravity.CENTER
                setPadding(0, dp(10), 0, 0)
            }
        )
    }

    private fun compactButton(label: String): TextView =
        TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 11f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            isClickable = true
            isFocusable = true
        }

    private fun actionButton(
        text: String,
        fill: Int,
        stroke: Int,
    ): TextView =
        TextView(this).apply {
            this.text = text
            setTextColor(Color.WHITE)
            textSize = 15f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            background = roundedBackground(fill, stroke, 28)
            isClickable = true
            isFocusable = true
        }

    private fun roundedBackground(
        fill: Int,
        stroke: Int,
        radiusDp: Int,
    ): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(radiusDp).toFloat()
            setColor(fill)
            setStroke(dp(1), stroke)
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
