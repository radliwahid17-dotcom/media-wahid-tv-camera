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
import androidx.activity.OnBackPressedCallback
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
        // Recovery from interrupted previous exports: do not delete user gallery media.
        worker.execute {
            cacheDir.listFiles()?.filter {
                it.isFile && it.name.startsWith("wahid_studio_") &&
                    it.name.endsWith(".mp4") &&
                    System.currentTimeMillis() - it.lastModified() > 24L * 60L * 60L * 1000L
            }?.forEach { it.delete() }
        }
        buildUi()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (exporting) {
                    setStatus("Ekspor berjalan. Batalkan dulu jika ingin keluar.")
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
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

        val importCard = studioCard()
        importCard.addView(label("01   IMPORT MEDIA", 12f, Color.rgb(109, 231, 181), true))
        importCard.addView(label("Pilih hasil foto atau rekaman Samsung.", 13f, subtle).apply {
            setPadding(0, dp(6), 0, dp(12))
        })
        importCard.addView(button("＋   AMBIL FOTO / VIDEO DARI GALERI", Color.rgb(38, 201, 152)).apply {
            setTextColor(Color.rgb(7, 34, 28))
            setOnClickListener { if (!exporting) picker.launch(arrayOf("image/*", "video/*")) }
        }, LinearLayout.LayoutParams(-1, dp(53)))
        sourceInfo = label("Belum ada file • Share dari Galeri juga bisa", 12f, subtle).apply {
            setPadding(0, dp(13), 0, 0)
        }
        importCard.addView(sourceInfo)
        container.addView(importCard)

        val previewCard = studioCard()
        val previewBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        previewBar.addView(label("PREVIEW", 12f, Color.WHITE, true),
            LinearLayout.LayoutParams(0, -2, 1f))
        previewBar.addView(label("SAMSUNG  ×  STUDIO", 10f, Color.rgb(103, 225, 184), true))
        previewCard.addView(previewBar)
        val previewFrame = FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(10, 16, 23))
        }
        previewFrame.addView(label("▣\n\nMEDIA WAHID TV\nPilih media untuk mulai", 13f, subtle, true).apply {
            gravity = Gravity.CENTER
        }, FrameLayout.LayoutParams(-1, -1))
        preview = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "Pratinjau hasil watermark dan efek"
        }
        previewFrame.addView(preview, FrameLayout.LayoutParams(-1, -1))
        compareButton = button("◉   LIHAT ASLI", Color.rgb(32, 55, 63)).apply {
            visibility = View.GONE
            setPadding(dp(10), 0, dp(10), 0)
            setOnClickListener { toggleCompare() }
        }
        previewFrame.addView(compareButton,
            FrameLayout.LayoutParams(-2, dp(39), Gravity.BOTTOM or Gravity.END).apply {
                rightMargin = dp(10)
                bottomMargin = dp(10)
            })
        previewCard.addView(previewFrame, LinearLayout.LayoutParams(-1, dp(270)).apply {
            topMargin = dp(12)
        })
        previewCard.addView(label("Ketuk LIHAT ASLI untuk bandingkan sebelum dan sesudah.", 11f, subtle).apply {
            setPadding(0, dp(11), 0, 0)
        })
        container.addView(previewCard, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(12)
        })
        status = label("Pilih file untuk mulai. Mode Default aktif.", 12f, subtle)
        status.setPadding(0, dp(10), 0, dp(14))
        container.addView(status)

        container.addView(label("02   WATERMARK / IDENTITAS", 13f, Color.rgb(110, 224, 177), true))
        container.addView(label("Logo asli otomatis menempel di seluruh foto atau video.", 12f, subtle).apply {
            setPadding(0, dp(6), 0, dp(10))
        })
        val templateRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        dualButton = button("MASJID + MEDIA", Color.rgb(35, 45, 51))
        singleButton = button("MEDIA ONLY", Color.rgb(35, 45, 51))
        templateRow.addView(dualButton, LinearLayout.LayoutParams(0, dp(45), 1f).apply { rightMargin = dp(6) })
        templateRow.addView(singleButton, LinearLayout.LayoutParams(0, dp(45), 1f))
        container.addView(templateRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(9) })
        dualButton.setOnClickListener { chooseTemplate(WatermarkTemplate.DUAL) }
        singleButton.setOnClickListener { chooseTemplate(WatermarkTemplate.MEDIA_ONLY) }

        container.addView(label("03   COLOR LAB  /  6 LOOKS", 13f, Color.rgb(110, 224, 177), true).apply {
            setPadding(0, dp(23), 0, dp(9))
        })
        container.addView(label("Natural atau cinematic. Geser untuk melihat semua efek.", 12f, subtle).apply {
            setPadding(0, 0, 0, dp(11))
        })
        val effectScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val effectRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val symbols = listOf("◯", "✦", "☀", "◈", "▧", "◑")
        StudioEffect.entries.forEachIndexed { index, effect ->
            val chip = button(symbols[index] + "  " + effect.title + "\n" + effect.description,
                Color.rgb(31, 45, 54)).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), 0, dp(8), 0)
                textSize = 11.5f
                setOnClickListener {
                    if (exporting) return@setOnClickListener
                    preset = effect
                    showingOriginal = false
                    updateSelection()
                    renderPreview()
                }
            }
            effectButtons[effect] = chip
            effectRow.addView(chip, LinearLayout.LayoutParams(dp(170), dp(72)).apply { rightMargin = dp(8) })
        }
        effectScroll.addView(effectRow)
        container.addView(effectScroll)
        effectDescription = label("DEFAULT • Original Samsung", 12f, subtle).apply {
            setPadding(0, dp(12), 0, dp(6))
        }
        container.addView(effectDescription)
        container.addView(label("Efek memperhalus tampilan warna/cahaya, bukan memperbaiki fokus yang hilang.", 11f, subtle).apply {
            setPadding(0, 0, 0, dp(17))
        })

        val finishCard = studioCard()
        finishCard.addView(label("04   EXPORT / SELESAI", 13f, Color.rgb(112, 234, 181), true))
        finishCard.addView(label("File hasil disimpan terpisah. Asli tetap aman.", 12f, subtle).apply {
            setPadding(0, dp(7), 0, dp(12))
        })
        saveButton = button("↗   SIMPAN HASIL + WATERMARK", Color.rgb(47, 218, 163)).apply {
            setTextColor(Color.rgb(8, 33, 26))
            setOnClickListener { startExport() }
        }
        finishCard.addView(saveButton, LinearLayout.LayoutParams(-1, dp(57)))
        progressPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = ColorStateList.valueOf(Color.rgb(56, 224, 169))
            progressBackgroundTintList = ColorStateList.valueOf(Color.rgb(46, 67, 77))
        }
        progressPanel.addView(progressBar, LinearLayout.LayoutParams(-1, dp(7)).apply {
            topMargin = dp(13)
        })
        finishCard.addView(progressPanel)
        cancelButton = button("BATALKAN EKSPOR", Color.rgb(102, 50, 54)).apply {
            visibility = View.GONE
            setOnClickListener { cancelExport() }
        }
        finishCard.addView(cancelButton, LinearLayout.LayoutParams(-1, dp(46)).apply {
            topMargin = dp(10)
        })
        resultPanel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
        }
        resultPanel.addView(button("✓  BUKA DI GALERI", Color.rgb(29, 119, 91)).apply {
            setOnClickListener { openResult() }
        }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { rightMargin = dp(7) })
        resultPanel.addView(button("↗  BAGIKAN", Color.rgb(34, 65, 73)).apply {
            setOnClickListener { shareResult() }
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        finishCard.addView(resultPanel, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(11) })
        finishCard.addView(label("Render video panjang butuh ruang kosong dan Studio harus tetap terbuka.", 11f, subtle).apply {
            setPadding(0, dp(13), 0, 0)
        })
        container.addView(finishCard)
        container.addView(label("EDIT OFFLINE  •  PRIVASI TERJAGA  •  TIDAK UPLOAD VIDEO",
            10f, Color.rgb(103, 135, 145), true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(24), 0, dp(8))
        })
        updateSelection()
    }

    private fun studioCard(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(17), dp(16), dp(17))
        background = roundedBackground(Color.rgb(20, 30, 40), Color.rgb(45, 64, 72), 20)
    }

    private fun roundedBackground(fill: Int, outline: Int, radius: Int): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = dp(radius).toFloat()
            setColor(fill)
            setStroke(dp(1), outline)
        }

    private fun toggleCompare() {
        showingOriginal = !showingOriginal
        preview.setImageBitmap(if (showingOriginal) originalPreview else editedPreview)
        compareButton.text = if (showingOriginal) "◉  LIHAT HASIL" else "◉  LIHAT ASLI"
    }

    private fun openResult() {
        val output = lastOutput ?: return
        try {
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(output, contentResolver.getType(output)
                    ?: if (isVideo) "video/mp4" else "image/jpeg")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        } catch (_: Exception) {
            setStatus("Tidak ditemukan aplikasi Galeri untuk membuka hasil.")
        }
    }

    private fun shareResult() {
        val output = lastOutput ?: return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = contentResolver.getType(output) ?: if (isVideo) "video/mp4" else "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, output)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Bagikan Media Wahid TV"))
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
        showingOriginal = false
        lastOutput = null
        resultPanel.visibility = View.GONE
        compareButton.visibility = View.GONE
        sourceInfo.text = try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val filename = cursor.getString(0) ?: "Media Samsung"
                    val bytes = cursor.getLong(1).coerceAtLeast(0L)
                    val mb = bytes / 1048576.0
                    (if (isVideo) "▣ VIDEO" else "▧ FOTO") + "  •  " +
                        filename.take(30) + "  •  " +
                        String.format(java.util.Locale.US, "%.1f MB", mb)
                } else "Media Samsung dipilih"
            } ?: "Media Samsung dipilih"
        } catch (_: Exception) { "Media Samsung dipilih" }
        setStatus(if (isVideo) "Membuat pratinjau video..." else "Membaca foto...")
        renderPreview()
    }

    private fun chooseTemplate(value: WatermarkTemplate) {
        if (exporting) return
        template = value
        showingOriginal = false
        prefs.edit().putString("template", value.storageValue).apply()
        updateSelection()
        renderPreview()
    }

    private fun updateSelection() {
        val active = Color.rgb(22, 103, 81)
        val idle = Color.rgb(31, 46, 57)
        val dual = template == WatermarkTemplate.DUAL
        dualButton.background = roundedBackground(if (dual) active else idle,
            if (dual) Color.rgb(94, 235, 172) else Color.rgb(54, 74, 82), 12)
        singleButton.background = roundedBackground(if (!dual) active else idle,
            if (!dual) Color.rgb(94, 235, 172) else Color.rgb(54, 74, 82), 12)
        dualButton.text = if (dual) "✓ MASJID + MEDIA" else "◧ MASJID + MEDIA"
        singleButton.text = if (!dual) "✓ MEDIA ONLY" else "◩ MEDIA ONLY"
        effectButtons.forEach { (effect, chip) ->
            val chosen = effect == preset
            chip.background = roundedBackground(if (chosen) active else idle,
                if (chosen) Color.rgb(88, 229, 171) else Color.rgb(51, 67, 77), 13)
        }
        effectDescription.text = preset.title.uppercase() + "  •  " + preset.description
    }

    private fun renderPreview() {
        val uri = inputUri ?: return
        val selectedEffect = preset
        val selectedTemplate = template
        val video = isVideo
        val token = ++previewToken
        worker.execute {
            var frame: Bitmap? = null
            var result: Bitmap? = null
            try {
                frame = if (video) getVideoFrame(uri) else getImage(uri, 1400)
                result = selectedEffect.applyToPhoto(frame)
                branding.drawOnPhoto(result, selectedTemplate)
                val originalBitmap = frame
                val resultBitmap = result
                runOnUiThread {
                    if (token != previewToken || isFinishing || isDestroyed) {
                        originalBitmap.recycle()
                        resultBitmap.recycle()
                        return@runOnUiThread
                    }
                    val lastOriginal = originalPreview
                    val lastEdited = editedPreview
                    originalPreview = originalBitmap
                    editedPreview = resultBitmap
                    showingOriginal = false
                    preview.setImageBitmap(resultBitmap)
                    compareButton.text = "◉  LIHAT ASLI"
                    compareButton.visibility = View.VISIBLE
                    if (lastOriginal !== originalBitmap) lastOriginal?.recycle()
                    if (lastEdited !== resultBitmap) lastEdited?.recycle()
                    setStatus((if (video) "Preview frame video" else "Preview foto") +
                        "  •  " + selectedEffect.title + "  •  " + selectedTemplate.displayName)
                }
            } catch (error: Throwable) {
                result?.recycle()
                frame?.recycle()
                runOnUiThread {
                    if (token == previewToken && !isDestroyed)
                        setStatus("Gagal membaca preview: " + error.message)
                }
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
            val raw = retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: error("Frame video tidak dapat dibaca")
            val edge = max(raw.width, raw.height)
            if (edge <= 1400) raw else {
                val factor = 1400f / edge.toFloat()
                val resized = Bitmap.createScaledBitmap(raw,
                    max(1, (raw.width * factor).toInt()),
                    max(1, (raw.height * factor).toInt()), true)
                if (raw !== resized) raw.recycle()
                resized
            }
        } finally {
            retriever.release()
        }
    }

    private fun startExport() {
        val uri = inputUri ?: run { setStatus("Pilih foto atau video dulu."); return }
        if (exporting) return
        exporting = true
        savingVideo = false
        lastOutput = null
        resultPanel.visibility = View.GONE
        saveButton.isEnabled = false
        progressPanel.visibility = View.VISIBLE
        progressBar.progress = 0
        progressBar.isIndeterminate = !isVideo
        cancelButton.visibility = if (isVideo) View.VISIBLE else View.GONE
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
                runOnUiThread { endExport("Foto tersimpan di Galeri → Pictures/MEDIA WAHID TV", output) }
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
                        savingVideo = true
                        cancelButton.visibility = View.GONE
                        progressBar.isIndeterminate = true
                        setStatus("Render 100%. Menyimpan ke Galeri, jangan tutup aplikasi...")
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
                        progressBar.progress = progress.progress
                        setStatus("Render video " + progress.progress + "% • biarkan aplikasi terbuka")
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
                runOnUiThread { endExport("Video tersimpan di Galeri → Movies/MEDIA WAHID TV", destination) }
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
        if (savingVideo) {
            setStatus("Sedang menyimpan file final. Tidak bisa dibatalkan saat ini.")
            return
        }
        exporter?.cancel()
        exporter = null
        exportFile?.delete()
        endExport("Ekspor dibatalkan; file asli tetap aman.")
    }

    private fun endExport(message: String, output: Uri? = null) {
        exporting = false
        savingVideo = false
        exporter = null
        exportFile = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        saveButton.isEnabled = true
        progressPanel.visibility = View.GONE
        progressBar.isIndeterminate = false
        cancelButton.visibility = View.GONE
        if (output != null) {
            lastOutput = output
            resultPanel.visibility = View.VISIBLE
        }
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
        previewToken++
        exporter?.cancel()
        ui.removeCallbacksAndMessages(null)
        if (::preview.isInitialized) preview.setImageDrawable(null)
        originalPreview?.recycle()
        editedPreview?.recycle()
        originalPreview = null
        editedPreview = null
        worker.shutdown()
        super.onDestroy()
    }
}
