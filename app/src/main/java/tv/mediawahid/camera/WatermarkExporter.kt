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
        template: WatermarkTemplate,
        onProgress: (Int) -> Unit,
        onCompleted: () -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        cancel()
        cancelled = false

        if (output.exists()) output.delete()

        val mediaLogoSource = decodeLogo(R.drawable.media_wahid_logo_original)
            ?: run {
                onError(IllegalStateException("Logo MEDIA WAHID TV tidak dapat dibaca"))
                return
            }

        val masjidLogoSource = if (template == WatermarkTemplate.DUAL) {
            decodeLogo(R.drawable.masjid_raya_logo)
                ?: run {
                    mediaLogoSource.recycle()
                    onError(IllegalStateException("Logo Masjid tidak dapat dibaca"))
                    return
                }
        } else {
            null
        }

        val displayWidth = readDisplayWidth(inputUri)
        val targetWidth = WatermarkGeometry.targetWidth(displayWidth, template)

        val mediaLogo = scaleLogo(mediaLogoSource, targetWidth)
        val masjidLogo = masjidLogoSource?.let { scaleLogo(it, targetWidth) }

        if (mediaLogo !== mediaLogoSource) mediaLogoSource.recycle()
        if (masjidLogoSource != null && masjidLogo !== masjidLogoSource) {
            masjidLogoSource.recycle()
        }

        val overlays = mutableListOf<BitmapOverlay>()

        if (masjidLogo != null) {
            val masjidSettings = StaticOverlaySettings.Builder()
                .setOverlayFrameAnchor(-1f, 1f)
                .setBackgroundFrameAnchor(-0.94f, 0.92f)
                .setScale(1f, 1f)
                .setAlphaScale(1f)
                .build()

            overlays += BitmapOverlay.createStaticBitmapOverlay(
                masjidLogo,
                masjidSettings
            )
        }

        val mediaSettings = StaticOverlaySettings.Builder()
            .setOverlayFrameAnchor(1f, 1f)
            .setBackgroundFrameAnchor(0.94f, 0.92f)
            .setScale(1f, 1f)
            .setAlphaScale(1f)
            .build()

        overlays += BitmapOverlay.createStaticBitmapOverlay(
            mediaLogo,
            mediaSettings
        )

        val editedMediaItem = EditedMediaItem.Builder(
            MediaItem.fromUri(inputUri)
        )
            .setEffects(
                Effects(
                    emptyList(),
                    listOf(OverlayEffect(overlays))
                )
            )
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

                    mediaLogo.recycle()
                    masjidLogo?.recycle()

                    Thread {
                        try {
                            if (cancelled) return@Thread

                            if (!output.exists() || output.length() <= 0L) {
                                error("Hasil video kosong")
                            }

                            onProgress(100)

                            if (cancelled) return@Thread

                            if (!WatermarkVerifier(context).verifyVideo(output, template)) {
                                output.delete()
                                error("Watermark template belum terverifikasi pada hasil video")
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

                    if (!mediaLogo.isRecycled) mediaLogo.recycle()
                    if (masjidLogo != null && !masjidLogo.isRecycled) masjidLogo.recycle()

                    output.delete()

                    if (!cancelled) {
                        onError(
                            IllegalStateException(
                                "Gagal merender video: " +
                                    (exportException.message ?: "codec tidak tersedia"),
                                exportException
                            )
                        )
                    }
                }
            })
            .build()

        transformer = localTransformer

        try {
            localTransformer.start(editedMediaItem, output.absolutePath)
            startProgress(localTransformer, onProgress)
        } catch (error: Throwable) {
            transformer = null

            if (!mediaLogo.isRecycled) mediaLogo.recycle()
            if (masjidLogo != null && !masjidLogo.isRecycled) masjidLogo.recycle()

            output.delete()

            if (!cancelled) onError(error)
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

    private fun decodeLogo(resourceId: Int): Bitmap? =
        BitmapFactory.decodeResource(
            context.resources,
            resourceId,
            BitmapFactory.Options().apply {
                inScaled = false
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
        )

    private fun scaleLogo(source: Bitmap, targetWidth: Int): Bitmap {
        val targetHeight = (
            targetWidth * source.height.toFloat() / source.width.toFloat()
        )
            .roundToInt()
            .coerceAtLeast(1)

        return Bitmap.createScaledBitmap(
            source,
            targetWidth,
            targetHeight,
            true
        )
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

    private fun readDisplayWidth(inputUri: Uri): Int {
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

            if (rotation == 90 || rotation == 270) rawHeight else rawWidth
        } catch (_: Throwable) {
            1080
        } finally {
            try {
                retriever.release()
            } catch (_: Throwable) {
            }
        }
    }
}
