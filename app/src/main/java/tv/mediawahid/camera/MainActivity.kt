package tv.mediawahid.camera

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ContentValues
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Environment
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
    private lateinit var statusText: TextView

    private var currentVideoFile: File? = null
    private var currentPhotoFile: File? = null
    private var cameraLaunchInProgress = false

    private val videoLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // Full Samsung Camera mode intentionally does not return the recorded
        // file to us. Once the user exits the camera, open Android's video
        // picker so the just-recorded full-quality file can be watermarked.
        cameraLaunchInProgress = false
        statusText.text = "Pilih video yang baru direkam..."
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
                    "Foto belum diterima. Coba foto lagi."
                }
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildUi()
        hideSystemBars()

        videoButton.setOnClickListener {
            launchSamsungVideo()
        }

        photoButton.setOnClickListener {
            launchSamsungPhoto()
        }
    }

    override fun onResume() {
        super.onResume()
        if (::root.isInitialized) hideSystemBars()
    }

    private fun buildUi() {
        root = FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(7, 9, 12))
            keepScreenOn = true
        }
        setContentView(root)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(22), dp(34), dp(22), dp(28))
        }
        root.addView(content, FrameLayout.LayoutParams(-1, -1))

        val mediaLogo = ImageView(this).apply {
            setImageResource(R.drawable.media_wahid_logo)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        content.addView(
            mediaLogo,
            LinearLayout.LayoutParams(-1, dp(86))
        )

        content.addView(TextView(this).apply {
            text = "MEDIA WAHID TV CAMERA"
            setTextColor(Color.WHITE)
            textSize = 23f
            gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(38), 0, dp(6))
        }, LinearLayout.LayoutParams(-1, -2))

        content.addView(TextView(this).apply {
            text = "Kamera Samsung • Video & Foto • logo otomatis"
            setTextColor(Color.rgb(181, 186, 194))
            textSize = 14f
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-1, -2))

        content.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))

        val badge = TextView(this).apply {
            text = "PILIH MODE"
            setTextColor(Color.rgb(218, 225, 235))
            textSize = 12f
            gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(18), dp(9), dp(18), dp(9))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(20).toFloat()
                setColor(Color.rgb(25, 29, 36))
                setStroke(dp(1), Color.rgb(53, 61, 73))
            }
        }
        content.addView(badge, LinearLayout.LayoutParams(-2, -2).apply {
            bottomMargin = dp(18)
        })

        videoButton = TextView(this).apply {
            text = "VIDEO"
            setTextColor(Color.WHITE)
            textSize = 17f
            gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(28).toFloat()
                setColor(Color.rgb(230, 41, 50))
            }
        }
        content.addView(videoButton, LinearLayout.LayoutParams(-1, dp(58)))

        photoButton = TextView(this).apply {
            text = "FOTO"
            setTextColor(Color.WHITE)
            textSize = 17f
            gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(28).toFloat()
                setColor(Color.rgb(30, 35, 43))
                setStroke(dp(1), Color.rgb(77, 86, 99))
            }
        }
        content.addView(photoButton, LinearLayout.LayoutParams(-1, dp(58)).apply {
            topMargin = dp(12)
        })

        statusText = TextView(this).apply {
            text = "Selesai ambil gambar → logo MEDIA WAHID TV dipasang otomatis"
            setTextColor(Color.rgb(150, 157, 168))
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(0, dp(15), 0, 0)
        }
        content.addView(statusText, LinearLayout.LayoutParams(-1, -2))

        val processing = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.argb(244, 7, 9, 12))
            visibility = View.GONE

            addView(ProgressBar(this@MainActivity), LinearLayout.LayoutParams(dp(64), dp(64)))

            processingTitle = TextView(this@MainActivity).apply {
                text = "MEMPROSES..."
                setTextColor(Color.WHITE)
                textSize = 20f
                gravity = Gravity.CENTER
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(0, dp(20), 0, 0)
            }
            addView(processingTitle)

            addView(TextView(this@MainActivity).apply {
                text = "Memasang logo MEDIA WAHID TV"
                setTextColor(Color.rgb(180, 186, 195))
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(dp(28), dp(8), dp(28), 0)
            })
        }
        processingPanel = processing
        root.addView(processing, FrameLayout.LayoutParams(-1, -1))
    }

    private fun launchSamsungVideo() {
        if (cameraLaunchInProgress) return

        currentVideoFile?.delete()
        currentVideoFile = null
        cameraLaunchInProgress = true
        statusText.text = "Samsung Camera full • rekam bebas, lalu kembali ke app"

        val baseIntent = Intent(MediaStore.INTENT_ACTION_VIDEO_CAMERA)

        try {
            videoLauncher.launch(Intent(baseIntent).apply {
                setPackage("com.sec.android.app.camera")
            })
        } catch (_: ActivityNotFoundException) {
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
        } catch (_: SecurityException) {
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
    }

    private fun launchSamsungPhoto() {
        if (cameraLaunchInProgress) return

        val capturesDir = File(cacheDir, "captures").apply { mkdirs() }
        val captureFile = File(capturesDir, "samsung_photo_${System.currentTimeMillis()}.jpg")
        currentPhotoFile?.delete()
        currentPhotoFile = captureFile

        val outputUri = FileProvider.getUriForFile(
            this,
            "$packageName.fileprovider",
            captureFile
        )

        val baseIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, outputUri)
            clipData = ClipData.newRawUri("MEDIA WAHID TV photo", outputUri)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        cameraLaunchInProgress = true
        statusText.text = "Membuka foto Samsung..."

        launchPreferredSamsungCamera(baseIntent, captureFile, isVideo = false)
    }

    private fun launchPreferredSamsungCamera(
        baseIntent: Intent,
        fallbackFile: File,
        isVideo: Boolean
    ) {
        try {
            val samsungIntent = Intent(baseIntent).apply {
                setPackage("com.sec.android.app.camera")
            }
            if (isVideo) videoLauncher.launch(samsungIntent)
            else photoLauncher.launch(samsungIntent)
        } catch (_: ActivityNotFoundException) {
            launchGenericCamera(baseIntent, fallbackFile, isVideo)
        } catch (_: SecurityException) {
            launchGenericCamera(baseIntent, fallbackFile, isVideo)
        }
    }

    private fun launchGenericCamera(
        baseIntent: Intent,
        fallbackFile: File,
        isVideo: Boolean
    ) {
        try {
            val genericIntent = Intent(baseIntent).apply { setPackage(null) }
            if (isVideo) videoLauncher.launch(genericIntent)
            else photoLauncher.launch(genericIntent)
        } catch (_: Throwable) {
            cameraLaunchInProgress = false
            fallbackFile.delete()
            if (isVideo) currentVideoFile = null else currentPhotoFile = null
            showReadyState("Aplikasi kamera tidak ditemukan")
            Toast.makeText(
                this,
                "Kamera bawaan tidak bisa dibuka.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun copyReturnedFileToCache(
        uri: Uri,
        prefix: String,
        extension: String
    ): File? {
        return try {
            val file = File(cacheDir, "${prefix}_${System.currentTimeMillis()}.$extension")
            contentResolver.openInputStream(uri)?.use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
            file.takeIf { it.length() > 0L }
        } catch (_: Throwable) {
            null
        }
    }

    private fun setProcessing(isVideo: Boolean, visible: Boolean) {
        processingTitle.text = if (isVideo) "MEMPROSES VIDEO..." else "MEMPROSES FOTO..."
        processingPanel.visibility = if (visible) View.VISIBLE else View.GONE
        videoButton.isEnabled = !visible
        photoButton.isEnabled = !visible
    }

    private fun processAndSaveVideo(inputUri: Uri) {
        setProcessing(isVideo = true, visible = true)
        val watermarked = File(cacheDir, "wahid_${System.currentTimeMillis()}.mp4")

        WatermarkExporter(this).export(
            inputUri = inputUri,
            output = watermarked,
            onCompleted = {
                runOnUiThread {
                    try {
                        saveVideoToGallery(watermarked)
                        watermarked.delete()
                        setProcessing(isVideo = true, visible = false)
                        showReadyState("VIDEO TERSIMPAN ✓ • logo MEDIA WAHID TV terpasang")
                        Toast.makeText(this, "VIDEO TERSIMPAN ✓", Toast.LENGTH_SHORT).show()
                    } catch (_: Throwable) {
                        setProcessing(isVideo = true, visible = false)
                        showReadyState("Video belum berhasil disimpan")
                        Toast.makeText(this, "Video belum berhasil disimpan.", Toast.LENGTH_LONG).show()
                    }
                }
            },
            onError = {
                runOnUiThread {
                    watermarked.delete()
                    setProcessing(isVideo = true, visible = false)
                    showReadyState("Pemrosesan video gagal. Coba pilih ulang.")
                    Toast.makeText(this, "Video belum berhasil diproses.", Toast.LENGTH_LONG).show()
                }
            }
        )
    }

    private fun processAndSaveVideo(rawFile: File) {
        setProcessing(isVideo = true, visible = true)
        val watermarked = File(cacheDir, "wahid_${System.currentTimeMillis()}.mp4")

        WatermarkExporter(this).export(
            input = rawFile,
            output = watermarked,
            onCompleted = {
                runOnUiThread {
                    rawFile.delete()
                    currentVideoFile = null
                    try {
                        saveVideoToGallery(watermarked)
                        watermarked.delete()
                        setProcessing(isVideo = true, visible = false)
                        showReadyState("VIDEO TERSIMPAN ✓ • logo MEDIA WAHID TV terpasang")
                        Toast.makeText(this, "VIDEO TERSIMPAN ✓", Toast.LENGTH_SHORT).show()
                    } catch (_: Throwable) {
                        setProcessing(isVideo = true, visible = false)
                        showReadyState("Video belum berhasil disimpan")
                        Toast.makeText(this, "Video belum berhasil disimpan.", Toast.LENGTH_LONG).show()
                    }
                }
            },
            onError = {
                runOnUiThread {
                    rawFile.delete()
                    watermarked.delete()
                    currentVideoFile = null
                    setProcessing(isVideo = true, visible = false)
                    showReadyState("Pemrosesan video gagal. Rekam ulang.")
                    Toast.makeText(this, "Video belum berhasil diproses.", Toast.LENGTH_LONG).show()
                }
            }
        )
    }

    private fun processAndSavePhoto(rawFile: File) {
        setProcessing(isVideo = false, visible = true)

        Thread {
            val watermarked = File(cacheDir, "wahid_photo_${System.currentTimeMillis()}.jpg")
            try {
                PhotoWatermarker(this).process(rawFile, watermarked)
                rawFile.delete()
                currentPhotoFile = null

                runOnUiThread {
                    try {
                        savePhotoToGallery(watermarked)
                        watermarked.delete()
                        setProcessing(isVideo = false, visible = false)
                        showReadyState("FOTO TERSIMPAN ✓ • logo MEDIA WAHID TV terpasang")
                        Toast.makeText(this, "FOTO TERSIMPAN ✓", Toast.LENGTH_SHORT).show()
                    } catch (_: Throwable) {
                        watermarked.delete()
                        setProcessing(isVideo = false, visible = false)
                        showReadyState("Foto belum berhasil disimpan")
                        Toast.makeText(this, "Foto belum berhasil disimpan.", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (_: Throwable) {
                rawFile.delete()
                watermarked.delete()
                currentPhotoFile = null
                runOnUiThread {
                    setProcessing(isVideo = false, visible = false)
                    showReadyState("Pemrosesan foto gagal. Foto ulang.")
                    Toast.makeText(this, "Foto belum berhasil diproses.", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun showReadyState(message: String) {
        statusText.text = message
        videoButton.text = "VIDEO"
        photoButton.text = "FOTO"
        videoButton.isEnabled = true
        photoButton.isEnabled = true
    }

    private fun saveVideoToGallery(file: File): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "MEDIA_WAHID_TV_${System.currentTimeMillis()}.mp4")
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

        contentResolver.openOutputStream(uri)?.use { output ->
            file.inputStream().use { input -> input.copyTo(output) }
        } ?: error("Tidak dapat menulis video")

        values.clear()
        values.put(MediaStore.Video.Media.IS_PENDING, 0)
        contentResolver.update(uri, values, null, null)
        return uri
    }

    private fun savePhotoToGallery(file: File): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "MEDIA_WAHID_TV_${System.currentTimeMillis()}.jpg")
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

        contentResolver.openOutputStream(uri)?.use { output ->
            file.inputStream().use { input -> input.copyTo(output) }
        } ?: error("Tidak dapat menulis foto")

        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        contentResolver.update(uri, values, null, null)
        return uri
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
