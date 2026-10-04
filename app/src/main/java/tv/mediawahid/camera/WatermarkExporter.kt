package tv.mediawahid.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.StaticOverlaySettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import java.io.File
import kotlin.math.roundToInt

@UnstableApi
class WatermarkExporter(private val context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var transformer: Transformer? = null
    private var progressRunnable: Runnable? = null
    @Volatile private var cancelled = false

    fun export(
        inputUri: Uri,
        output: File,
        onProgress: (Int) -> Unit,
        onCompleted: () -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        cancel()
        cancelled = false
        if (output.exists()) output.delete()

        val sourceLogo = BitmapFactory.decodeResource(
            context.resources,
            R.drawable.media_wahid_logo_original,
            BitmapFactory.Options().apply {
                inScaled = false
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
        ) ?: run {
            onError(IllegalStateException("Logo MEDIA WAHID TV tidak dapat dibaca"))
            return
        }

        val targetWidth = readTargetLogoWidth(inputUri)
        val targetHeight = (
            targetWidth * sourceLogo.height.toFloat() / sourceLogo.width.toFloat()
        ).roundToInt().coerceAtLeast(1)

        val scaledLogo = Bitmap.createScaledBitmap(
            sourceLogo,
            targetWidth,
            targetHeight,
            true
        )

        if (scaledLogo !== sourceLogo) {
            sourceLogo.recycle()
        }

        val mediaSettings = StaticOverlaySettings.Builder()
            .setOverlayFrameAnchor(1f, 1f)
            .setBackgroundFrameAnchor(0.94f, 0.92f)
            .setScale(1f, 1f)
            .setAlphaScale(1f)
            .build()

        val mediaOverlay = BitmapOverlay.createStaticBitmapOverlay(
            scaledLogo,
            mediaSettings
        )
        val overlayEffect = OverlayEffect(listOf(mediaOverlay))

        val editedMediaItem = EditedMediaItem.Builder(
            MediaItem.fromUri(inputUri)
        )
            .setEffects(Effects(emptyList(), listOf(overlayEffect)))
            .build()

        val localTransformer = Transformer.Builder(context)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(
                    composition: Composition,
                    exportResult: ExportResult,
                ) {
                    stopProgress()
                    transformer = null

                    if (!scaledLogo.isRecycled) scaledLogo.recycle()

                    // Listener Transformer berjalan di application/main thread.
                    // Verifikasi frame bisa berat pada video 4K/panjang, jadi wajib
                    // dipindah ke worker agar UI tidak freeze/ANR.
                    Thread {
                        try {
                            if (cancelled) return@Thread

                            if (!output.exists() || output.length() <= 0L) {
                                error("Hasil video kosong")
                            }

                            onProgress(100)

                            if (cancelled) return@Thread

                            if (!WatermarkVerifier.verifyVideo(output)) {
                                output.delete()
                                error("Logo MEDIA WAHID TV belum terdeteksi pada hasil video")
                            }

                            if (!cancelled) onCompleted()
                        } catch (error: Throwable) {
                            if (!cancelled) onError(error)
                        }
                    }.start()
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException,
                ) {
                    stopProgress()
                    transformer = null
                    if (!scaledLogo.isRecycled) scaledLogo.recycle()
                    output.delete()
                    onError(
                        IllegalStateException(
                            "Gagal merender video berlogo: " +
                                (exportException.message ?: "codec tidak tersedia"),
                            exportException
                        )
                    )
                }
            })
            .build()

        transformer = localTransformer

        try {
            localTransformer.start(editedMediaItem, output.absolutePath)
            startProgress(localTransformer, onProgress)
        } catch (error: Throwable) {
            transformer = null
            if (!scaledLogo.isRecycled) scaledLogo.recycle()
            output.delete()
            onError(error)
        }
    }

    fun cancel() {
        cancelled = true
        stopProgress()
        try {
            transformer?.cancel()
        } catch (_: Throwable) {
        }
        transformer = null
    }

    private fun startProgress(
        activeTransformer: Transformer,
        onProgress: (Int) -> Unit
    ) {
        val holder = ProgressHolder()

        val runnable = object : Runnable {
            override fun run() {
                if (transformer !== activeTransformer) return

                try {
                    val state = activeTransformer.getProgress(holder)
                    if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                        onProgress(holder.progress.coerceIn(0, 99))
                    }

                    if (state != Transformer.PROGRESS_STATE_NOT_STARTED) {
                        mainHandler.postDelayed(this, 500L)
                    }
                } catch (_: Throwable) {
                }
            }
        }

        progressRunnable = runnable
        mainHandler.post(runnable)
    }

    private fun stopProgress() {
        progressRunnable?.let { mainHandler.removeCallbacks(it) }
        progressRunnable = null
    }

    private fun readTargetLogoWidth(inputUri: Uri): Int {
        val retriever = MediaMetadataRetriever()

        return try {
            retriever.setDataSource(context, inputUri)

            val rawWidth = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH
            )?.toIntOrNull()?.coerceAtLeast(1) ?: 1080

            val rawHeight = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT
            )?.toIntOrNull()?.coerceAtLeast(1) ?: 1920

            val rotation = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION
            )?.toIntOrNull() ?: 0

            val displayWidth = if (rotation == 90 || rotation == 270) {
                rawHeight
            } else {
                rawWidth
            }

            (displayWidth * 0.20f)
                .roundToInt()
                .coerceIn(180, 620)
        } catch (_: Throwable) {
            260
        } finally {
            try {
                retriever.release()
            } catch (_: Throwable) {
            }
        }
    }
}
