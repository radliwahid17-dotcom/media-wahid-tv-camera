package tv.mediawahid.camera

import android.content.ContentValues
import android.content.res.ColorStateList
import android.provider.OpenableColumns
import android.view.View
import android.widget.ProgressBar
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadataRetriever
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
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

/**
 * Studio: import original Samsung media -> preset -> real logos -> save a NEW copy.
 * Does not use CameraX or alter the stock camera. No duration cap is imposed.
 */
@OptIn(UnstableApi::class)
class StudioActivity : ComponentActivity() {
    private val ui = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val prefs by lazy { getSharedPreferences("media_wahid_studio", MODE_PRIVATE) }
    private val branding by lazy { StudioWatermark(this) }
    private var inputUri: Uri? = null
    private var isVideo = false
    private var preset = StudioEffect.DEFAULT
    private var template = WatermarkTemplate.DUAL
    private var exporter: Transformer? = null
    private var exportFile: File? = null
    private var exporting = false
    private var previewToken = 0
    private lateinit var preview: ImageView
    private lateinit var status: TextView
    private lateinit var saveButton: TextView
    private lateinit var cancelButton: TextView
    private lateinit var dualButton: TextView
    private lateinit var singleButton: TextView
    private lateinit var sourceInfo: TextView
    private lateinit var compareButton: TextView
    private lateinit var effectDescription: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var progressPanel: LinearLayout
    private lateinit var resultPanel: LinearLayout
    private var originalPreview: Bitmap? = null
    private var editedPreview: Bitmap? = null
    private var showingOriginal = false
    private var lastOutput: Uri? = null
    private var savingVideo = false
    private val effectButtons = mutableMapOf<StudioEffect, TextView>()

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { chosen ->
            runCatching { contentResolver.takePersistableUriPermission(chosen, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            selectMedia(chosen)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        template = WatermarkTemplate.fromStorage(prefs.getString("template", "dual"))
        buildUi()
        handleShare(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShare(intent)
    }

    private fun handleShare(incoming: Intent?) {
        if (incoming == null) return
        val shared = when (incoming.action) {
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                incoming.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            }
            Intent.ACTION_VIEW -> incoming.data
            else -> null
        }
        if (shared != null) {
            val mime = incoming.type ?: contentResolver.getType(shared) ?: ""
            if (mime.startsWith("image/") || mime.startsWith("video/")) selectMedia(shared)
            else setStatus("Hanya foto atau video yang dapat diproses")
        }
    }

    private fun buildUi() {
        val white = Color.WHITE
        val subtle = Color.rgb(165, 186, 193)
        val root = ScrollView(this).apply {
            setBackgroundColor(Color.rgb(9, 16, 22))
            isFillViewport = true
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(27), dp(18), dp(34))
        }
        root.addView(container)
        setContentView(root)

        val brand = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
        }
        brand.addView(label("MW", 20f, Color.rgb(7, 29, 24), true).apply {
            gravity = Gravity.CENTER
            background = roundedBackground(Color.rgb(77, 225, 173), Color.rgb(77, 225, 173), 16)
        }, LinearLayout.LayoutParams(dp(56), dp(56)))
        val brandText = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        brandText.addView(label("MEDIA WAHID TV", 18f, white, true))
        brandText.addView(label("S T U D I O   /   6.1", 11f, Color.rgb(94, 221, 172), true))
        brand.addView(brandText, LinearLayout.LayoutParams(0, -2, 1f))
        brand.addView(label("● OFFLINE", 10f, Color.rgb(95, 222, 170), true).apply {
            setPadding(dp(8), dp(10), dp(8), dp(10))
            background = roundedBackground(Color.rgb(26, 55, 49), Color.rgb(26, 55, 49), 12)
        })
        container.addView(brand)
        container.addView(label("Rekam asli. Edit berkelas.", 24f, white, true).apply {
            setPadding(0, dp(27), 0, dp(6))
        })
        container.addView(label(
            "Rekam dengan kamera Samsung. Logo dan warna disempurnakan di Studio, tanpa mengubah file asli.",
            13f, subtle
        ).apply { setPadding(0, 0, 0, dp(20)) })
        val steps = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("01  GALERI", "02  EDIT", "03  SIMPAN").forEachIndexed { index, title ->
            steps.addView(label(title, 10f,
                if (index == 0) Color.rgb(93, 229, 176) else Color.rgb(146, 165, 174), true).apply {
                gravity = Gravity.CENTER
                background = roundedBackground(
                    if (index == 0) Color.rgb(20, 57, 48) else Color.rgb(25, 36, 44),
                    Color.rgb(38, 58, 67), 10)
            }, LinearLayout.LayoutParams(0, dp(35), 1f).apply {
                rightMargin = dp(5)
            })
        }
        container.addView(steps, LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = dp(18)
        })

        container.addView(button("PILIH FOTO / VIDEO DARI GALERI", Color.rgb(24, 101, 88)).apply {
            setOnClickListener { if (!exporting) picker.launch(arrayOf("image/*", "video/*")) }
        }, LinearLayout.LayoutParams(-1, dp(52)))

        preview = ImageView(this).apply {
            setBackgroundColor(Color.rgb(27, 38, 47))
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "Pratinjau hasil watermark dan efek"
        }
        container.addView(preview, LinearLayout.LayoutParams(-1, dp(265)).apply {
            topMargin = dp(15)
        })

        status = label("Pilih foto atau video untuk mulai. Mode Default aktif.", 12f, subtle)
        status.setPadding(0, dp(10), 0, dp(14))
        container.addView(status)

        container.addView(label("TEMPLATE LOGO", 13f, white, true))
        val templateRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        dualButton = button("MASJID + MEDIA", Color.rgb(35, 45, 51))
        singleButton = button("MEDIA ONLY", Color.rgb(35, 45, 51))
        templateRow.addView(dualButton, LinearLayout.LayoutParams(0, dp(45), 1f).apply { rightMargin = dp(6) })
        templateRow.addView(singleButton, LinearLayout.LayoutParams(0, dp(45), 1f))
        container.addView(templateRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(9) })
        dualButton.setOnClickListener { chooseTemplate(WatermarkTemplate.DUAL) }
        singleButton.setOnClickListener { chooseTemplate(WatermarkTemplate.MEDIA_ONLY) }

        container.addView(label("EFEK (DEFAULT + 5 PILIHAN)", 13f, white, true).apply {
            setPadding(0, dp(21), 0, dp(9))
        })
        val effectScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val effectRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        StudioEffect.entries.forEach { effect ->
            val chip = button(effect.title, Color.rgb(35, 45, 51))
            chip.setOnClickListener {
                if (exporting) return@setOnClickListener
                preset = effect
                updateSelection()
                renderPreview()
            }
            effectButtons[effect] = chip
            effectRow.addView(chip, LinearLayout.LayoutParams(dp(132), dp(52)).apply { rightMargin = dp(7) })
        }
        effectScroll.addView(effectRow)
        container.addView(effectScroll)

        container.addView(label("Efek memperbaiki tampilan warna/cahaya, bukan memperbaiki rekaman yang tidak fokus.", 11f, subtle).apply {
            setPadding(0, dp(8), 0, dp(17))
        })

        saveButton = button("SIMPAN HASIL + WATERMARK", Color.rgb(22, 125, 102)).apply {
            setOnClickListener { startExport() }
        }
        container.addView(saveButton, LinearLayout.LayoutParams(-1, dp(55)))
        cancelButton = button("BATALKAN EKSPOR", Color.rgb(107, 44, 52)).apply {
            visibility = android.view.View.GONE
            setOnClickListener { cancelExport() }
        }
        container.addView(cancelButton, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(8) })
        container.addView(label("File asli tetap aman. Ekspor video panjang membutuhkan ruang kosong dan aplikasi harus tetap dibuka.", 11f, subtle).apply {
            setPadding(0, dp(12), 0, 0)
        })
        updateSelection()
    }

    private fun selectMedia(uri: Uri) {
        if (exporting) return
        val type = contentResolver.getType(uri) ?: ""
        if (!type.startsWith("image/") && !type.startsWith("video/")) {
            setStatus("Format tidak dikenali. Gunakan JPG, PNG, atau video.")
            return
        }
        inputUri = uri
        isVideo = type.startsWith("video/")
        setStatus(if (isVideo) "Membuat pratinjau video..." else "Membaca foto...")
        renderPreview()
    }

    private fun chooseTemplate(value: WatermarkTemplate) {
        if (exporting) return
        template = value
        prefs.edit().putString("template", value.storageValue).apply()
        updateSelection()
        renderPreview()
    }

    private fun updateSelection() {
        val active = Color.rgb(16, 105, 87)
        val idle = Color.rgb(39, 52, 61)
        dualButton.background = background(if (template == WatermarkTemplate.DUAL) active else idle)
        singleButton.background = background(if (template == WatermarkTemplate.MEDIA_ONLY) active else idle)
        effectButtons.forEach { (effect, chip) ->
            chip.background = background(if (effect == preset) active else idle)
            chip.text = (if (effect == preset) "✓ " else "") + effect.title
        }
    }

    private fun renderPreview() {
        val uri = inputUri ?: return
        val selectedEffect = preset
        val selectedTemplate = template
        val video = isVideo
        val token = ++previewToken
        worker.execute {
            try {
                val frame = if (video) getVideoFrame(uri) else getImage(uri, 1400)
                val result = selectedEffect.applyToPhoto(frame)
                branding.drawOnPhoto(result, selectedTemplate)
                runOnUiThread {
                    if (token != previewToken || isFinishing || isDestroyed) {
                        result.recycle()
                        return@runOnUiThread
                    }
                    preview.setImageBitmap(result)
                    setStatus((if (video) "Pratinjau 1 frame video" else "Pratinjau foto") +
                        " • " + selectedEffect.title + " • " + selectedTemplate.displayName)
                }
                frame.recycle()
            } catch (error: Throwable) {
                runOnUiThread { if (token == previewToken) setStatus("Gagal membaca pratinjau: " + error.message) }
            }
        }
    }

    private fun getImage(uri: Uri, maxEdge: Int): Bitmap {
        val source = android.graphics.ImageDecoder.createSource(contentResolver, uri)
        return android.graphics.ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val w = info.size.width
            val h = info.size.height
            val scale = min(1f, maxEdge.toFloat() / max(w, h))
            decoder.setTargetSize(max(1, (w * scale).toInt()), max(1, (h * scale).toInt()))
            decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
        }
    }

    private fun getVideoFrame(uri: Uri): Bitmap {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(this, uri)
            retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: error("Frame video tidak bisa dibaca")
        } finally {
            retriever.release()
        }
    }

    private fun startExport() {
        val uri = inputUri ?: run { setStatus("Pilih foto atau video dulu."); return }
        if (exporting) return
        exporting = true
        saveButton.isEnabled = false
        cancelButton.visibility = if (isVideo) android.view.View.VISIBLE else android.view.View.GONE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val effect = preset
        val chosenTemplate = template
        if (isVideo) exportVideo(uri, effect, chosenTemplate) else exportPhoto(uri, effect, chosenTemplate)
    }

    private fun exportPhoto(uri: Uri, effect: StudioEffect, watermark: WatermarkTemplate) {
        setStatus("Memproses foto resolusi tinggi...")
        worker.execute {
            try {
                // Decode at full size for ordinary Samsung photos, bounded to avoid phone OOM.
                val source = getImage(uri, 6000)
                val result = effect.applyToPhoto(source)
                source.recycle()
                branding.drawOnPhoto(result, watermark)
                val output = insertGalleryItem(false)
                try {
                    contentResolver.openOutputStream(output, "w")?.use { stream ->
                        check(result.compress(Bitmap.CompressFormat.JPEG, 96, stream)) { "JPEG gagal disimpan" }
                    } ?: error("Galeri tidak menerima file")
                    finishGalleryItem(output)
                } catch (failure: Throwable) {
                    contentResolver.delete(output, null, null)
                    throw failure
                } finally {
                    result.recycle()
                }
                runOnUiThread { endExport("Foto berhasil disimpan di Galeri → Pictures/MEDIA WAHID TV") }
            } catch (error: Throwable) {
                runOnUiThread { endExport("Gagal ekspor foto: " + error.message) }
            }
        }
    }

    private fun exportVideo(uri: Uri, effect: StudioEffect, watermark: WatermarkTemplate) {
        try {
            val fileSize = contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
            val free = StatFs(cacheDir.absolutePath).availableBytes
            // Temporary MP4 and a gallery copy coexist during final save.
            val needed = if (fileSize > 0) max(1024L * 1024L * 1024L, fileSize * 13L / 10L)
                         else 1024L * 1024L * 1024L
            require(free > needed) { "Ruang kosong kurang untuk memproses video besar." }
            val output = File(cacheDir, "wahid_studio_" + System.currentTimeMillis() + ".mp4")
            exportFile = output
            val videoEffects = effect.videoEffects() + branding.videoEffects(watermark)
            val edited = EditedMediaItem.Builder(MediaItem.fromUri(uri))
                .setEffects(Effects(emptyList(), videoEffects))
                .build()
            exporter = Transformer.Builder(this)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        setStatus("Render selesai. Menyalin hasil ke Galeri...")
                        saveVideoCopy(output)
                    }
                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exportException: ExportException
                    ) {
                        output.delete()
                        endExport("Gagal video: " + exportException.message)
                    }
                })
                .build()
            setStatus("Render video dimulai. Jangan tutup aplikasi...")
            exporter!!.start(edited, output.absolutePath)
            pollProgress()
        } catch (error: Throwable) {
            exportFile?.delete()
            endExport("Tidak dapat memulai ekspor: " + error.message)
        }
    }

    private fun pollProgress() {
        ui.postDelayed({
            val current = exporter
            if (exporting && current != null) {
                runCatching {
                    val progress = ProgressHolder()
                    if (current.getProgress(progress) == Transformer.PROGRESS_STATE_AVAILABLE) {
                        setStatus("Memproses video " + progress.progress + "% • biarkan aplikasi terbuka")
                    }
                }
                pollProgress()
            }
        }, 700L)
    }

    private fun saveVideoCopy(output: File) {
        worker.execute {
            try {
                check(output.isFile && output.length() > 0L) { "File render kosong" }
                val destination = insertGalleryItem(true)
                try {
                    contentResolver.openOutputStream(destination, "w")?.use { stream ->
                        output.inputStream().use { input -> input.copyTo(stream, bufferSize = 1024 * 1024) }
                    } ?: error("Tidak dapat menyimpan ke Galeri")
                    finishGalleryItem(destination)
                } catch (error: Throwable) {
                    contentResolver.delete(destination, null, null)
                    throw error
                }
                runOnUiThread { endExport("Video disimpan di Galeri → Movies/MEDIA WAHID TV") }
            } catch (error: Throwable) {
                runOnUiThread { endExport("Gagal menyimpan video: " + error.message) }
            } finally {
                output.delete()
            }
        }
    }

    private fun insertGalleryItem(video: Boolean): Uri {
        val filename = "MEDIA_WAHID_TV_" + System.currentTimeMillis() + if (video) ".mp4" else ".jpg"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, if (video) "video/mp4" else "image/jpeg")
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                (if (video) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES) +
                    "/MEDIA WAHID TV"
            )
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                         else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        return contentResolver.insert(collection, values) ?: error("Gagal membuat file di Galeri")
    }

    private fun finishGalleryItem(uri: Uri) {
        contentResolver.update(uri, ContentValues().apply {
            put(MediaStore.MediaColumns.IS_PENDING, 0)
        }, null, null)
    }

    private fun cancelExport() {
        exporter?.cancel()
        exporter = null
        exportFile?.delete()
        endExport("Ekspor dibatalkan; file asli tetap aman.")
    }

    private fun endExport(message: String) {
        exporting = false
        exporter = null
        exportFile = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        saveButton.isEnabled = true
        cancelButton.visibility = android.view.View.GONE
        setStatus(message)
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun setStatus(value: String) { status.text = value }

    private fun label(value: String, size: Float, color: Int, bold: Boolean = false): TextView =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

    private fun button(value: String, fill: Int): TextView =
        label(value, 12f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            background = background(fill)
            isClickable = true
            isFocusable = true
        }

    private fun background(fill: Int) = GradientDrawable().apply {
        cornerRadius = dp(13).toFloat()
        setColor(fill)
    }

    private fun dp(value: Int) = (resources.displayMetrics.density * value).toInt()

    override fun onDestroy() {
        exporter?.cancel()
        ui.removeCallbacksAndMessages(null)
        worker.shutdown()
        super.onDestroy()
    }
}
