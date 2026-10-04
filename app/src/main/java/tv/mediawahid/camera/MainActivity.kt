package tv.mediawahid.camera

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color
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

    private lateinit var root: FrameLayout
    private lateinit var videoButton: TextView
    private lateinit var photoButton: TextView
    private lateinit var processingPanel: View
    private lateinit var processingTitle: TextView
    private lateinit var processingDetail: TextView
    private lateinit var processingProgress: ProgressBar
    private lateinit var statusText: TextView

    private var currentPhotoFile: File? = null
    private var cameraLaunchInProgress = false
    private var activeExporter: WatermarkExporter? = null

    private val videoLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        cameraLaunchInProgress = false
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
        processAndSaveVideo(uri)
    }

    private val photoLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        cameraLaunchInProgress = false

        val captureFile = currentPhotoFile
        val returnedUri = result.data?.data

        val usableFile = when {
            captureFile != null && captureFile.exists() && captureFile.length() > 0L -> captureFile
            returnedUri != null -> copyReturnedFileToCache(returnedUri, "returned_photo", "jpg")
            else -> null
        }

        if (usableFile != null && usableFile.exists() && usableFile.length() > 0L) {
            processAndSavePhoto(usableFile)
        } else {
            captureFile?.delete()
            currentPhotoFile = null
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
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildUi()
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
                            "Video/foto sedang diproses. Tunggu sampai selesai.",
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

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(22), dp(30), dp(22), dp(26))
        }
        root.addView(content, FrameLayout.LayoutParams(-1, -1))

        val mediaLogo = ImageView(this).apply {
            setImageResource(R.drawable.media_wahid_logo_original)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        content.addView(mediaLogo, LinearLayout.LayoutParams(-1, dp(92)))

        content.addView(TextView(this).apply {
            text = "MEDIA WAHID TV CAMERA"
            setTextColor(Color.WHITE)
            textSize = 23f
            gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(30), 0, dp(6))
        }, LinearLayout.LayoutParams(-1, -2))

        content.addView(TextView(this).apply {
            text = "V3 • Samsung Camera • Watermark Verified"
            setTextColor(Color.rgb(165, 174, 187))
            textSize = 13f
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-1, -2))

        content.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))

        val verifiedBadge = TextView(this).apply {
            text = "LOGO WAJIB TERVERIFIKASI"
            setTextColor(Color.rgb(235, 239, 246))
            textSize = 11f
            gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(18), dp(9), dp(18), dp(9))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(22).toFloat()
                setColor(Color.rgb(22, 27, 34))
                setStroke(dp(1), Color.rgb(58, 69, 83))
            }
        }
        content.addView(verifiedBadge, LinearLayout.LayoutParams(-2, -2).apply {
            bottomMargin = dp(20)
        })

        videoButton = TextView(this).apply {
            text = "REKAM VIDEO"
            setTextColor(Color.WHITE)
            textSize = 17f
            gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(28).toFloat()
                setColor(Color.rgb(225, 36, 48))
            }
        }
        content.addView(videoButton, LinearLayout.LayoutParams(-1, dp(60)))

        photoButton = TextView(this).apply {
            text = "AMBIL FOTO"
            setTextColor(Color.WHITE)
            textSize = 17f
            gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(28).toFloat()
                setColor(Color.rgb(29, 35, 43))
                setStroke(dp(1), Color.rgb(76, 87, 102))
            }
        }
        content.addView(photoButton, LinearLayout.LayoutParams(-1, dp(60)).apply {
            topMargin = dp(12)
        })

        statusText = TextView(this).apply {
            text = "Hasil baru disimpan setelah logo MEDIA WAHID TV lolos verifikasi"
            setTextColor(Color.rgb(145, 154, 167))
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(17), dp(8), 0)
        }
        content.addView(statusText, LinearLayout.LayoutParams(-1, -2))

        val processing = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(32), dp(32), dp(32), dp(32))
            setBackgroundColor(Color.argb(250, 6, 8, 11))
            visibility = View.GONE

            addView(ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.media_wahid_logo_original)
                scaleType = ImageView.ScaleType.FIT_CENTER
            }, LinearLayout.LayoutParams(dp(190), dp(110)))

            processingTitle = TextView(this@MainActivity).apply {
                text = "MEMPROSES..."
                setTextColor(Color.WHITE)
                textSize = 20f
                gravity = Gravity.CENTER
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(0, dp(24), 0, 0)
            }
            addView(processingTitle)

            processingDetail = TextView(this@MainActivity).apply {
                text = "Memasang logo..."
                setTextColor(Color.rgb(180, 188, 199))
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(dp(20), dp(9), dp(20), 0)
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

    private fun launchSamsungVideo() {
        if (cameraLaunchInProgress || processingPanel.visibility == View.VISIBLE) return

        cameraLaunchInProgress = true
        statusText.text = "Rekam bebas di Samsung Camera, lalu kembali ke aplikasi"

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

        val capturesDir = File(externalCacheDir ?: cacheDir, "captures").apply { mkdirs() }
        val captureFile = File(
            capturesDir,
            "media_wahid_photo_" + System.currentTimeMillis() + ".jpg"
        )
        currentPhotoFile?.delete()
        currentPhotoFile = captureFile

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
        statusText.text = "Membuka Samsung Camera..."

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
            currentPhotoFile = null
            showReadyState("Aplikasi kamera foto tidak ditemukan")
            Toast.makeText(
                this,
                "Kamera foto bawaan tidak bisa dibuka.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun processAndSaveVideo(inputUri: Uri) {
        if (!hasEnoughWorkingSpace(inputUri)) {
            showReadyState("Ruang kosong belum cukup untuk proses video panjang")
            Toast.makeText(
                this,
                "Kosongkan penyimpanan dulu. App butuh ruang sementara untuk merender video berlogo.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        setProcessing(
            visible = true,
            title = "MEMPROSES VIDEO",
            detail = "Menanam logo MEDIA WAHID TV • 0%",
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
            onProgress = { progress ->
                runOnUiThread {
                    processingProgress.isIndeterminate = false
                    processingProgress.progress = progress
                    processingDetail.text =
                        if (progress < 100) {
                            "Menanam logo MEDIA WAHID TV • " + progress + "%"
                        } else {
                            "Memverifikasi watermark..."
                        }
                }
            },
            onCompleted = {
                runOnUiThread {
                    activeExporter = null
                    try {
                        saveVideoToGallery(watermarked)
                        watermarked.delete()
                        setProcessing(false)
                        showReadyState("VIDEO TERSIMPAN ✓ • WATERMARK TERVERIFIKASI")
                        Toast.makeText(
                            this,
                            "VIDEO TERSIMPAN ✓ • LOGO TERVERIFIKASI",
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
                    showReadyState("Video tidak disimpan karena watermark belum terverifikasi")
                    Toast.makeText(
                        this,
                        error.message ?: "Pemrosesan video gagal.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        )
    }

    private fun processAndSavePhoto(rawFile: File) {
        setProcessing(
            visible = true,
            title = "MEMPROSES FOTO",
            detail = "Menanam dan memverifikasi logo MEDIA WAHID TV",
            indeterminate = true
        )

        Thread {
            val exportDir = File(externalCacheDir ?: cacheDir, "exports").apply { mkdirs() }
            val watermarked = File(
                exportDir,
                "media_wahid_photo_" + System.currentTimeMillis() + ".jpg"
            )

            try {
                PhotoWatermarker(this).process(rawFile, watermarked)
                rawFile.delete()
                currentPhotoFile = null

                runOnUiThread {
                    try {
                        savePhotoToGallery(watermarked)
                        watermarked.delete()
                        setProcessing(false)
                        showReadyState("FOTO TERSIMPAN ✓ • WATERMARK TERVERIFIKASI")
                        Toast.makeText(
                            this,
                            "FOTO TERSIMPAN ✓ • LOGO TERVERIFIKASI",
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
                currentPhotoFile = null
                runOnUiThread {
                    setProcessing(false)
                    showReadyState("Foto tidak disimpan karena watermark belum terverifikasi")
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
            val doubled = if (inputSize > Long.MAX_VALUE / 2L) Long.MAX_VALUE else inputSize * 2L
            if (doubled > Long.MAX_VALUE - reserve) Long.MAX_VALUE else doubled + reserve
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
    }

    private fun saveVideoToGallery(file: File): Uri {
        val values = ContentValues().apply {
            put(
                MediaStore.Video.Media.DISPLAY_NAME,
                "MEDIA_WAHID_TV_" + System.currentTimeMillis() + ".mp4"
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

    private fun savePhotoToGallery(file: File): Uri {
        val values = ContentValues().apply {
            put(
                MediaStore.Images.Media.DISPLAY_NAME,
                "MEDIA_WAHID_TV_" + System.currentTimeMillis() + ".jpg"
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
