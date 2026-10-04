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
import android.os.Handler
import android.os.Looper
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
    private lateinit var openCameraButton: TextView
    private lateinit var processingPanel: View
    private lateinit var statusText: TextView

    private var currentCaptureFile: File? = null
    private var cameraLaunchInProgress = false
    private var autoLaunchDone = false

    private val mainHandler = Handler(Looper.getMainLooper())

    private val cameraLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        cameraLaunchInProgress = false

        val captureFile = currentCaptureFile
        val returnedUri = result.data?.data

        val usableFile = when {
            captureFile != null && captureFile.exists() && captureFile.length() > 0L -> captureFile
            returnedUri != null -> copyReturnedVideoToCache(returnedUri)
            else -> null
        }

        if (usableFile != null && usableFile.exists() && usableFile.length() > 0L) {
            processAndSave(usableFile)
        } else {
            captureFile?.delete()
            currentCaptureFile = null
            showReadyState(
                if (result.resultCode == Activity.RESULT_CANCELED) {
                    "Rekaman dibatalkan"
                } else {
                    "Video belum diterima. Coba rekam lagi."
                }
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildUi()
        hideSystemBars()

        openCameraButton.setOnClickListener {
            launchSamsungCamera()
        }

        if (savedInstanceState == null) {
            mainHandler.postDelayed({
                if (!isFinishing && !cameraLaunchInProgress && !autoLaunchDone) {
                    autoLaunchDone = true
                    launchSamsungCamera()
                }
            }, 450)
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

        val logoRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        val masjidLogo = ImageView(this).apply {
            setImageResource(R.drawable.masjid_raya_logo)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        val mediaLogo = ImageView(this).apply {
            setImageResource(R.drawable.media_wahid_logo_original)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }

        logoRow.addView(
            masjidLogo,
            LinearLayout.LayoutParams(0, dp(86), 1f).apply { marginEnd = dp(8) }
        )
        logoRow.addView(
            mediaLogo,
            LinearLayout.LayoutParams(0, dp(86), 1f).apply { marginStart = dp(8) }
        )
        content.addView(logoRow, LinearLayout.LayoutParams(-1, dp(86)))

        content.addView(TextView(this).apply {
            text = "MEDIA WAHID TV CAMERA"
            setTextColor(Color.WHITE)
            textSize = 23f
            gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(38), 0, dp(6))
        }, LinearLayout.LayoutParams(-1, -2))

        content.addView(TextView(this).apply {
            text = "Kualitas kamera Samsung • 2 logo otomatis"
            setTextColor(Color.rgb(181, 186, 194))
            textSize = 14f
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-1, -2))

        content.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))

        val badge = TextView(this).apply {
            text = "KAMERA BAWAAN SAMSUNG"
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

        openCameraButton = TextView(this).apply {
            text = "BUKA KAMERA"
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
        content.addView(openCameraButton, LinearLayout.LayoutParams(-1, dp(58)))

        statusText = TextView(this).apply {
            text = "Selesai rekam → 2 logo dipasang otomatis"
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

            addView(TextView(this@MainActivity).apply {
                text = "MEMPROSES VIDEO..."
                setTextColor(Color.WHITE)
                textSize = 20f
                gravity = Gravity.CENTER
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(0, dp(20), 0, 0)
            })

            addView(TextView(this@MainActivity).apply {
                text = "Memasang logo Masjid Raya + MEDIA WAHID TV"
                setTextColor(Color.rgb(180, 186, 195))
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(dp(28), dp(8), dp(28), 0)
            })
        }
        processingPanel = processing
        root.addView(processing, FrameLayout.LayoutParams(-1, -1))
    }

    private fun launchSamsungCamera() {
        if (cameraLaunchInProgress) return

        val capturesDir = File(cacheDir, "captures").apply { mkdirs() }
        val captureFile = File(capturesDir, "samsung_${System.currentTimeMillis()}.mp4")
        currentCaptureFile?.delete()
        currentCaptureFile = captureFile

        val outputUri = FileProvider.getUriForFile(
            this,
            "$packageName.fileprovider",
            captureFile
        )

        val baseIntent = Intent(MediaStore.ACTION_VIDEO_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, outputUri)
            putExtra(MediaStore.EXTRA_VIDEO_QUALITY, 1)
            clipData = ClipData.newRawUri("MEDIA WAHID TV video", outputUri)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        cameraLaunchInProgress = true
        statusText.text = "Membuka kamera Samsung..."

        try {
            cameraLauncher.launch(Intent(baseIntent).apply {
                setPackage("com.sec.android.app.camera")
            })
        } catch (_: ActivityNotFoundException) {
            launchGenericCamera(baseIntent, captureFile)
        } catch (_: SecurityException) {
            launchGenericCamera(baseIntent, captureFile)
        }
    }

    private fun launchGenericCamera(baseIntent: Intent, captureFile: File) {
        try {
            cameraLauncher.launch(Intent(baseIntent).apply { setPackage(null) })
        } catch (_: Throwable) {
            cameraLaunchInProgress = false
            captureFile.delete()
            currentCaptureFile = null
            showReadyState("Aplikasi kamera tidak ditemukan")
            Toast.makeText(
                this,
                "Kamera bawaan tidak bisa dibuka.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun copyReturnedVideoToCache(uri: Uri): File? {
        return try {
            val file = File(cacheDir, "returned_${System.currentTimeMillis()}.mp4")
            contentResolver.openInputStream(uri)?.use { input ->
                file.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: return null
            file.takeIf { it.length() > 0L }
        } catch (_: Throwable) {
            null
        }
    }

    private fun processAndSave(rawFile: File) {
        processingPanel.visibility = View.VISIBLE
        openCameraButton.isEnabled = false
        val watermarked = File(cacheDir, "wahid_${System.currentTimeMillis()}.mp4")

        WatermarkExporter(this).export(
            input = rawFile,
            output = watermarked,
            onCompleted = {
                runOnUiThread {
                    rawFile.delete()
                    currentCaptureFile = null
                    try {
                        saveToGallery(watermarked)
                        watermarked.delete()
                        processingPanel.visibility = View.GONE
                        openCameraButton.isEnabled = true
                        showReadyState("VIDEO TERSIMPAN ✓ • 2 logo sudah terpasang")
                        Toast.makeText(
                            this,
                            "VIDEO TERSIMPAN ✓",
                            Toast.LENGTH_SHORT
                        ).show()
                    } catch (_: Throwable) {
                        processingPanel.visibility = View.GONE
                        openCameraButton.isEnabled = true
                        showReadyState("Video belum berhasil disimpan")
                        Toast.makeText(
                            this,
                            "Video belum berhasil disimpan.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            },
            onError = {
                runOnUiThread {
                    rawFile.delete()
                    watermarked.delete()
                    currentCaptureFile = null
                    processingPanel.visibility = View.GONE
                    openCameraButton.isEnabled = true
                    showReadyState("Pemrosesan video gagal. Rekam ulang.")
                    Toast.makeText(
                        this,
                        "Video belum berhasil diproses.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        )
    }

    private fun showReadyState(message: String) {
        statusText.text = message
        openCameraButton.text = "REKAM LAGI"
        openCameraButton.isEnabled = true
    }

    private fun saveToGallery(file: File): Uri {
        val values = ContentValues().apply {
            put(
                MediaStore.Video.Media.DISPLAY_NAME,
                "MEDIA_WAHID_TV_${System.currentTimeMillis()}.mp4"
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

        contentResolver.openOutputStream(uri)?.use { output ->
            file.inputStream().use { input ->
                input.copyTo(output)
            }
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
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
