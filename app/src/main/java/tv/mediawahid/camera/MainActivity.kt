package tv.mediawahid.camera

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.util.UnstableApi
import java.io.File

@UnstableApi
class MainActivity : ComponentActivity() {

    companion object {
        private const val PREFS_NAME = "media_wahid_camera"
        private const val KEY_TEMPLATE = "selected_template"
        private const val KEY_PENDING_TEMPLATE = "pending_capture_template"
        private const val KEY_PENDING_PHOTO_PATH = "pending_photo_path"
    }

    private lateinit var root: FrameLayout
    private lateinit var videoButton: TextView
    private lateinit var photoButton: TextView
    private lateinit var processingPanel: View
    private lateinit var processingTitle: TextView
    private lateinit var processingDetail: TextView
    private lateinit var processingProgress: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var selectedTemplateText: TextView
    private lateinit var dualCard: LinearLayout
    private lateinit var mediaOnlyCard: LinearLayout
    private lateinit var dualState: TextView
    private lateinit var mediaOnlyState: TextView

    private var currentPhotoFile: File? = null
    private var cameraLaunchInProgress = false
    private var activeExporter: WatermarkExporter? = null
    private var selectedTemplate = WatermarkTemplate.DUAL
    private var pendingTemplate = WatermarkTemplate.DUAL

    private val preferences by lazy {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
    }

    private val videoLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        cameraLaunchInProgress = false
        pendingTemplate = WatermarkTemplate.fromStorage(
            preferences.getString(KEY_PENDING_TEMPLATE, selectedTemplate.storageValue)
        )

        statusText.text = "Pilih rekaman yang baru dibuat"
        videoPickerLauncher.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
        )
    }

    private val videoPickerLauncher = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri == null) {
            showReadyState("Video belum dipilih")
            return@registerForActivityResult
        }

        processAndSaveVideo(uri, pendingTemplate)
    }

    private val photoLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        cameraLaunchInProgress = false
        pendingTemplate = WatermarkTemplate.fromStorage(
            preferences.getString(KEY_PENDING_TEMPLATE, selectedTemplate.storageValue)
        )

        val captureFile = currentPhotoFile ?: preferences
            .getString(KEY_PENDING_PHOTO_PATH, null)
            ?.let(::File)
        val returnedUri = result.data?.data

        val usableFile = when {
            captureFile != null && captureFile.exists() && captureFile.length() > 0L -> captureFile
            returnedUri != null -> copyReturnedFileToCache(returnedUri, "returned_photo", "jpg")
            else -> null
        }

        if (usableFile != null && usableFile.exists() && usableFile.length() > 0L) {
            processAndSavePhoto(usableFile, pendingTemplate)
        } else {
            captureFile?.delete()
            clearPendingPhotoPath()

            showReadyState(
                if (result.resultCode == Activity.RESULT_CANCELED) {
                    "Foto dibatalkan"
                } else {
                    "Foto belum diterima. Coba lagi."
                }
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        selectedTemplate = WatermarkTemplate.fromStorage(
            preferences.getString(KEY_TEMPLATE, WatermarkTemplate.DUAL.storageValue)
        )
        pendingTemplate = WatermarkTemplate.fromStorage(
            preferences.getString(KEY_PENDING_TEMPLATE, selectedTemplate.storageValue)
        )
        currentPhotoFile = preferences
            .getString(KEY_PENDING_PHOTO_PATH, null)
            ?.let(::File)
            ?.takeIf { it.exists() }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        buildUi()
        applyTemplateSelection(showToast = false)
        hideSystemBars()

        videoButton.setOnClickListener { launchSamsungVideo() }
        photoButton.setOnClickListener { launchSamsungPhoto() }

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (processingPanel.visibility == View.VISIBLE) {
                        Toast.makeText(
                            this@MainActivity,
                            "Sedang memproses. Tunggu sampai selesai.",
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        )
    }

    override fun onResume() {
        super.onResume()
        if (::root.isInitialized) hideSystemBars()
    }

    override fun onDestroy() {
        activeExporter?.cancel()
        activeExporter = null
        super.onDestroy()
    }

    private fun buildUi() {
        root = FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(6, 8, 11))
            keepScreenOn = true
        }
        setContentView(root)

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        root.addView(scroll, FrameLayout.LayoutParams(-1, -1))

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(28), dp(20), dp(28))
        }
        scroll.addView(content, FrameLayout.LayoutParams(-1, -2))

        val mediaLogo = ImageView(this).apply {
            setImageResource(R.drawable.media_wahid_logo_original)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        content.addView(mediaLogo, LinearLayout.LayoutParams(-1, dp(82)))

        content.addView(TextView(this).apply {
            text = "MEDIA WAHID TV CAMERA"
            setTextColor(Color.WHITE)
            textSize = 23f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(24), 0, dp(5))
        }, LinearLayout.LayoutParams(-1, -2))

        content.addView(TextView(this).apply {
            text = "Dual Template Studio • Watermark Verified"
            setTextColor(Color.rgb(157, 168, 183))
            textSize = 13f
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-1, -2))

        selectedTemplateText = TextView(this).apply {
            setTextColor(Color.rgb(235, 240, 247))
            textSize = 12f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(16), dp(9), dp(16), dp(9))
        }
        content.addView(selectedTemplateText, LinearLayout.LayoutParams(-2, -2).apply {
            topMargin = dp(18)
            bottomMargin = dp(18)
        })

        content.addView(TextView(this).apply {
            text = "PILIH TEMPLATE WATERMARK"
            setTextColor(Color.rgb(205, 213, 224))
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.START
        }, LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = dp(10)
        })

        dualCard = createDualTemplateCard()
        content.addView(dualCard, LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = dp(10)
        })

        mediaOnlyCard = createMediaOnlyTemplateCard()
        content.addView(mediaOnlyCard, LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = dp(20)
        })

        videoButton = TextView(this).apply {
            text = "REKAM VIDEO"
            setTextColor(Color.WHITE)
            textSize = 17f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            background = roundedBackground(
                fill = Color.rgb(225, 36, 48),
                stroke = Color.rgb(225, 36, 48),
                radiusDp = 28
            )
        }
        content.addView(videoButton, LinearLayout.LayoutParams(-1, dp(60)))

        photoButton = TextView(this).apply {
            text = "AMBIL FOTO"
            setTextColor(Color.WHITE)
            textSize = 17f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            background = roundedBackground(
                fill = Color.rgb(28, 34, 42),
                stroke = Color.rgb(76, 87, 102),
                radiusDp = 28
            )
        }
        content.addView(photoButton, LinearLayout.LayoutParams(-1, dp(60)).apply {
            topMargin = dp(11)
        })

        statusText = TextView(this).apply {
            text = "Hasil hanya disimpan setelah watermark template lolos verifikasi"
            setTextColor(Color.rgb(142, 152, 166))
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(16), dp(8), dp(8))
        }
        content.addView(statusText, LinearLayout.LayoutParams(-1, -2))

        content.addView(TextView(this).apply {
            text = "Original Samsung Camera • Foto & Video • pilihan template tersimpan"
            setTextColor(Color.rgb(103, 113, 127))
            textSize = 11f
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-1, -2))

        val processing = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(30), dp(30), dp(30), dp(30))
            setBackgroundColor(Color.argb(252, 6, 8, 11))
            visibility = View.GONE

            addView(ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.media_wahid_logo_original)
                scaleType = ImageView.ScaleType.FIT_CENTER
            }, LinearLayout.LayoutParams(dp(185), dp(105)))

            processingTitle = TextView(this@MainActivity).apply {
                text = "MEMPROSES..."
                setTextColor(Color.WHITE)
                textSize = 20f
                gravity = Gravity.CENTER
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(23), 0, 0)
            }
            addView(processingTitle)

            processingDetail = TextView(this@MainActivity).apply {
                text = "Menanam watermark..."
                setTextColor(Color.rgb(181, 189, 201))
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(dp(18), dp(9), dp(18), 0)
            }
            addView(processingDetail)

            processingProgress = ProgressBar(
                this@MainActivity,
                null,
                android.R.attr.progressBarStyleHorizontal
            ).apply {
                max = 100
                progress = 0
                isIndeterminate = true
            }
            addView(processingProgress, LinearLayout.LayoutParams(-1, dp(10)).apply {
                topMargin = dp(24)
            })
        }

        processingPanel = processing
        root.addView(processing, FrameLayout.LayoutParams(-1, -1))
    }

    private fun createDualTemplateCard(): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            isClickable = true
            isFocusable = true
            setOnClickListener { selectTemplate(WatermarkTemplate.DUAL) }
        }

        val preview = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        preview.addView(ImageView(this).apply {
            setImageResource(R.drawable.masjid_raya_logo)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }, LinearLayout.LayoutParams(0, dp(54), 1f).apply {
            marginEnd = dp(8)
        })

        preview.addView(ImageView(this).apply {
            setImageResource(R.drawable.media_wahid_logo_original)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }, LinearLayout.LayoutParams(0, dp(54), 1f).apply {
            marginStart = dp(8)
        })

        card.addView(preview, LinearLayout.LayoutParams(-1, dp(54)))

        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(11), 0, 0)
        }

        titleRow.addView(TextView(this).apply {
            text = "MASJID + MEDIA WAHID TV"
            setTextColor(Color.WHITE)
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, -2, 1f))

        dualState = TextView(this).apply {
            textSize = 11f
            setTypeface(typeface, Typeface.BOLD)
        }
        titleRow.addView(dualState)

        card.addView(titleRow)

        card.addView(TextView(this).apply {
            text = "Masjid kiri atas • MEDIA WAHID TV kanan atas"
            setTextColor(Color.rgb(146, 157, 171))
            textSize = 12f
            setPadding(0, dp(5), 0, 0)
        })

        return card
    }

    private fun createMediaOnlyTemplateCard(): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            isClickable = true
            isFocusable = true
            setOnClickListener { selectTemplate(WatermarkTemplate.MEDIA_ONLY) }
        }

        card.addView(ImageView(this).apply {
            setImageResource(R.drawable.media_wahid_logo_original)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }, LinearLayout.LayoutParams(-1, dp(54)))

        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(11), 0, 0)
        }

        titleRow.addView(TextView(this).apply {
            text = "MEDIA WAHID TV"
            setTextColor(Color.WHITE)
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, -2, 1f))

        mediaOnlyState = TextView(this).apply {
            textSize = 11f
            setTypeface(typeface, Typeface.BOLD)
        }
        titleRow.addView(mediaOnlyState)

        card.addView(titleRow)

        card.addView(TextView(this).apply {
            text = "Watermark bersih • MEDIA WAHID TV kanan atas"
            setTextColor(Color.rgb(146, 157, 171))
            textSize = 12f
            setPadding(0, dp(5), 0, 0)
        })

        return card
    }

    private fun selectTemplate(template: WatermarkTemplate) {
        if (processingPanel.visibility == View.VISIBLE || cameraLaunchInProgress) return

        selectedTemplate = template
        preferences.edit()
            .putString(KEY_TEMPLATE, template.storageValue)
            .apply()

        applyTemplateSelection(showToast = true)
    }

    private fun applyTemplateSelection(showToast: Boolean) {
        val selectedFill = Color.rgb(22, 29, 36)
        val selectedStroke = Color.rgb(78, 196, 151)
        val idleFill = Color.rgb(14, 18, 23)
        val idleStroke = Color.rgb(48, 58, 71)

        val dualSelected = selectedTemplate == WatermarkTemplate.DUAL

        dualCard.background = roundedBackground(
            fill = if (dualSelected) selectedFill else idleFill,
            stroke = if (dualSelected) selectedStroke else idleStroke,
            radiusDp = 18
        )

        mediaOnlyCard.background = roundedBackground(
            fill = if (!dualSelected) selectedFill else idleFill,
            stroke = if (!dualSelected) selectedStroke else idleStroke,
            radiusDp = 18
        )

        dualState.text = if (dualSelected) "✓ AKTIF" else "PILIH"
        mediaOnlyState.text = if (!dualSelected) "✓ AKTIF" else "PILIH"

        dualState.setTextColor(
            if (dualSelected) selectedStroke else Color.rgb(132, 143, 158)
        )
        mediaOnlyState.setTextColor(
            if (!dualSelected) selectedStroke else Color.rgb(132, 143, 158)
        )

        selectedTemplateText.text = "TEMPLATE AKTIF • " + selectedTemplate.displayName
        selectedTemplateText.background = roundedBackground(
            fill = Color.rgb(17, 23, 29),
            stroke = selectedStroke,
            radiusDp = 22
        )

        statusText.text =
            "Siap • " + selectedTemplate.displayName + " • watermark wajib terverifikasi"

        if (showToast) {
            Toast.makeText(
                this,
                "Template: " + selectedTemplate.displayName,
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun launchSamsungVideo() {
        if (cameraLaunchInProgress || processingPanel.visibility == View.VISIBLE) return

        pendingTemplate = selectedTemplate
        persistPendingTemplate()

        cameraLaunchInProgress = true
        statusText.text =
            "Rekam dengan Samsung Camera • template: " + pendingTemplate.displayName

        val baseIntent = Intent(MediaStore.INTENT_ACTION_VIDEO_CAMERA)

        try {
            videoLauncher.launch(Intent(baseIntent).apply {
                setPackage("com.sec.android.app.camera")
            })
        } catch (_: ActivityNotFoundException) {
            launchGenericVideo(baseIntent)
        } catch (_: SecurityException) {
            launchGenericVideo(baseIntent)
        }
    }

    private fun launchGenericVideo(baseIntent: Intent) {
        try {
            videoLauncher.launch(Intent(baseIntent).apply { setPackage(null) })
        } catch (_: Throwable) {
            cameraLaunchInProgress = false
            showReadyState("Aplikasi kamera video tidak ditemukan")

            Toast.makeText(
                this,
                "Kamera video bawaan tidak bisa dibuka.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun launchSamsungPhoto() {
        if (cameraLaunchInProgress || processingPanel.visibility == View.VISIBLE) return

        pendingTemplate = selectedTemplate
        persistPendingTemplate()

        val capturesDir = File(externalCacheDir ?: cacheDir, "captures").apply { mkdirs() }
        val captureFile = File(
            capturesDir,
            "media_wahid_photo_" + System.currentTimeMillis() + ".jpg"
        )

        currentPhotoFile?.delete()
        currentPhotoFile = captureFile
        preferences.edit()
            .putString(KEY_PENDING_PHOTO_PATH, captureFile.absolutePath)
            .apply()

        val outputUri = FileProvider.getUriForFile(
            this,
            packageName + ".fileprovider",
            captureFile
        )

        val baseIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, outputUri)
            clipData = ClipData.newRawUri("MEDIA WAHID TV photo", outputUri)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        cameraLaunchInProgress = true
        statusText.text =
            "Foto dengan Samsung Camera • template: " + pendingTemplate.displayName

        try {
            photoLauncher.launch(Intent(baseIntent).apply {
                setPackage("com.sec.android.app.camera")
            })
        } catch (_: ActivityNotFoundException) {
            launchGenericPhoto(baseIntent, captureFile)
        } catch (_: SecurityException) {
            launchGenericPhoto(baseIntent, captureFile)
        }
    }

    private fun launchGenericPhoto(baseIntent: Intent, captureFile: File) {
        try {
            photoLauncher.launch(Intent(baseIntent).apply { setPackage(null) })
        } catch (_: Throwable) {
            cameraLaunchInProgress = false
            captureFile.delete()
            clearPendingPhotoPath()
            showReadyState("Aplikasi kamera foto tidak ditemukan")

            Toast.makeText(
                this,
                "Kamera foto bawaan tidak bisa dibuka.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun persistPendingTemplate() {
        preferences.edit()
            .putString(KEY_PENDING_TEMPLATE, pendingTemplate.storageValue)
            .apply()
    }

    private fun clearPendingPhotoPath() {
        currentPhotoFile = null
        preferences.edit()
            .remove(KEY_PENDING_PHOTO_PATH)
            .apply()
    }

    private fun processAndSaveVideo(
        inputUri: Uri,
        template: WatermarkTemplate
    ) {
        if (!hasEnoughWorkingSpace(inputUri)) {
            showReadyState("Ruang kosong belum cukup untuk proses video panjang")

            Toast.makeText(
                this,
                "Kosongkan penyimpanan dulu. App butuh ruang sementara untuk merender video.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        setProcessing(
            visible = true,
            title = "MEMPROSES VIDEO",
            detail = "Menanam " + template.processingLabel + " • 0%",
            indeterminate = false
        )

        val exportDir = File(externalCacheDir ?: cacheDir, "exports").apply { mkdirs() }
        val watermarked = File(
            exportDir,
            "media_wahid_" + System.currentTimeMillis() + ".mp4"
        )

        val exporter = WatermarkExporter(this)
        activeExporter = exporter

        exporter.export(
            inputUri = inputUri,
            output = watermarked,
            template = template,
            onProgress = { progress ->
                runOnUiThread {
                    processingProgress.isIndeterminate = false
                    processingProgress.progress = progress
                    processingDetail.text =
                        if (progress < 100) {
                            "Menanam " + template.processingLabel + " • " + progress + "%"
                        } else {
                            "Memverifikasi " + template.processingLabel + "..."
                        }
                }
            },
            onCompleted = {
                runOnUiThread {
                    activeExporter = null

                    try {
                        saveVideoToGallery(watermarked, template)
                        watermarked.delete()
                        setProcessing(false)

                        showReadyState(
                            "VIDEO TERSIMPAN ✓ • " + template.displayName + " TERVERIFIKASI"
                        )

                        Toast.makeText(
                            this,
                            "VIDEO TERSIMPAN ✓ • WATERMARK TERVERIFIKASI",
                            Toast.LENGTH_LONG
                        ).show()
                    } catch (error: Throwable) {
                        setProcessing(false)
                        showReadyState("Video berlogo selesai, tapi gagal disimpan ke Galeri")

                        Toast.makeText(
                            this,
                            error.message ?: "Gagal menyimpan video.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            },
            onError = { error ->
                runOnUiThread {
                    activeExporter = null
                    watermarked.delete()
                    setProcessing(false)

                    showReadyState(
                        "Video tidak disimpan karena watermark belum terverifikasi"
                    )

                    Toast.makeText(
                        this,
                        error.message ?: "Pemrosesan video gagal.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        )
    }

    private fun processAndSavePhoto(
        rawFile: File,
        template: WatermarkTemplate
    ) {
        setProcessing(
            visible = true,
            title = "MEMPROSES FOTO",
            detail = "Menanam dan memverifikasi " + template.processingLabel,
            indeterminate = true
        )

        Thread {
            val exportDir = File(externalCacheDir ?: cacheDir, "exports").apply { mkdirs() }
            val watermarked = File(
                exportDir,
                "media_wahid_photo_" + System.currentTimeMillis() + ".jpg"
            )

            try {
                PhotoWatermarker(this).process(rawFile, watermarked, template)
                rawFile.delete()
                clearPendingPhotoPath()

                runOnUiThread {
                    try {
                        savePhotoToGallery(watermarked, template)
                        watermarked.delete()
                        setProcessing(false)

                        showReadyState(
                            "FOTO TERSIMPAN ✓ • " + template.displayName + " TERVERIFIKASI"
                        )

                        Toast.makeText(
                            this,
                            "FOTO TERSIMPAN ✓ • WATERMARK TERVERIFIKASI",
                            Toast.LENGTH_LONG
                        ).show()
                    } catch (error: Throwable) {
                        watermarked.delete()
                        setProcessing(false)
                        showReadyState("Foto berlogo selesai, tapi gagal disimpan ke Galeri")

                        Toast.makeText(
                            this,
                            error.message ?: "Gagal menyimpan foto.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            } catch (error: Throwable) {
                rawFile.delete()
                watermarked.delete()
                clearPendingPhotoPath()

                runOnUiThread {
                    setProcessing(false)
                    showReadyState(
                        "Foto tidak disimpan karena watermark belum terverifikasi"
                    )

                    Toast.makeText(
                        this,
                        error.message ?: "Pemrosesan foto gagal.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.start()
    }

    private fun copyReturnedFileToCache(
        uri: Uri,
        prefix: String,
        extension: String
    ): File? {
        return try {
            val dir = File(externalCacheDir ?: cacheDir, "imports").apply { mkdirs() }
            val file = File(
                dir,
                prefix + "_" + System.currentTimeMillis() + "." + extension
            )

            contentResolver.openInputStream(uri)?.use { input ->
                file.outputStream().buffered(1024 * 1024).use { output ->
                    input.copyTo(output, 1024 * 1024)
                }
            } ?: return null

            file.takeIf { it.length() > 0L }
        } catch (_: Throwable) {
            null
        }
    }

    private fun hasEnoughWorkingSpace(uri: Uri): Boolean {
        val inputSize = try {
            contentResolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
                descriptor.length
            } ?: -1L
        } catch (_: Throwable) {
            -1L
        }

        val stat = StatFs((externalCacheDir ?: cacheDir).absolutePath)
        val available = stat.availableBytes
        val reserve = 512L * 1024L * 1024L

        val required = if (inputSize > 0L) {
            val doubled =
                if (inputSize > Long.MAX_VALUE / 2L) Long.MAX_VALUE else inputSize * 2L

            if (doubled > Long.MAX_VALUE - reserve) {
                Long.MAX_VALUE
            } else {
                doubled + reserve
            }
        } else {
            2L * 1024L * 1024L * 1024L
        }

        return available > required
    }

    private fun setProcessing(
        visible: Boolean,
        title: String = "",
        detail: String = "",
        indeterminate: Boolean = true
    ) {
        processingPanel.visibility = if (visible) View.VISIBLE else View.GONE

        videoButton.isEnabled = !visible
        photoButton.isEnabled = !visible
        dualCard.isEnabled = !visible
        mediaOnlyCard.isEnabled = !visible

        if (visible) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
            processingTitle.text = title
            processingDetail.text = detail
            processingProgress.progress = 0
            processingProgress.isIndeterminate = indeterminate
        } else {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    private fun showReadyState(message: String) {
        statusText.text = message
        videoButton.isEnabled = true
        photoButton.isEnabled = true
        dualCard.isEnabled = true
        mediaOnlyCard.isEnabled = true
    }

    private fun saveVideoToGallery(
        file: File,
        template: WatermarkTemplate
    ): Uri {
        val templateSuffix =
            if (template == WatermarkTemplate.DUAL) "DUAL" else "MEDIA"

        val values = ContentValues().apply {
            put(
                MediaStore.Video.Media.DISPLAY_NAME,
                "MEDIA_WAHID_TV_" + templateSuffix + "_" +
                    System.currentTimeMillis() + ".mp4"
            )
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(
                MediaStore.Video.Media.RELATIVE_PATH,
                Environment.DIRECTORY_MOVIES + "/MEDIA WAHID TV"
            )
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }

        val uri = contentResolver.insert(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            values
        ) ?: error("Tidak dapat membuat file video")

        try {
            contentResolver.openOutputStream(uri)?.buffered(1024 * 1024).use { output ->
                requireNotNull(output) { "Tidak dapat membuka Galeri untuk video" }

                file.inputStream().buffered(1024 * 1024).use { input ->
                    input.copyTo(output, 1024 * 1024)
                }
            }

            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)

            return uri
        } catch (error: Throwable) {
            contentResolver.delete(uri, null, null)
            throw error
        }
    }

    private fun savePhotoToGallery(
        file: File,
        template: WatermarkTemplate
    ): Uri {
        val templateSuffix =
            if (template == WatermarkTemplate.DUAL) "DUAL" else "MEDIA"

        val values = ContentValues().apply {
            put(
                MediaStore.Images.Media.DISPLAY_NAME,
                "MEDIA_WAHID_TV_" + templateSuffix + "_" +
                    System.currentTimeMillis() + ".jpg"
            )
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                Environment.DIRECTORY_PICTURES + "/MEDIA WAHID TV"
            )
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }

        val uri = contentResolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            values
        ) ?: error("Tidak dapat membuat file foto")

        try {
            contentResolver.openOutputStream(uri)?.buffered(1024 * 1024).use { output ->
                requireNotNull(output) { "Tidak dapat membuka Galeri untuk foto" }

                file.inputStream().buffered(1024 * 1024).use { input ->
                    input.copyTo(output, 1024 * 1024)
                }
            }

            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)

            return uri
        } catch (error: Throwable) {
            contentResolver.delete(uri, null, null)
            throw error
        }
    }

    private fun roundedBackground(
        fill: Int,
        stroke: Int,
        radiusDp: Int
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
