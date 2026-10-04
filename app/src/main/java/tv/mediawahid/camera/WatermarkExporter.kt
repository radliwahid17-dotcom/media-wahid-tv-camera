package tv.mediawahid.camera

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
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
import androidx.media3.transformer.Transformer
import java.io.File

@UnstableApi
class WatermarkExporter(private val context: Context) {

    fun export(
        input: File,
        output: File,
        onCompleted: () -> Unit,
        onError: (Throwable) -> Unit,
    ) = export(
        inputUri = Uri.fromFile(input),
        output = output,
        onCompleted = onCompleted,
        onError = onError,
    )

    fun export(
        inputUri: Uri,
        output: File,
        onCompleted: () -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        if (output.exists()) output.delete()

        // FINAL: decode logo JPEG asli user secara langsung.
        // Tidak ada masking, transparansi, redraw, atau kartu putih buatan.
        val originalLogo = BitmapFactory.decodeResource(
            context.resources,
            R.drawable.media_wahid_logo_original,
            BitmapFactory.Options().apply { inScaled = false }
        ) ?: run {
            onError(IllegalStateException("Logo asli MEDIA WAHID TV tidak dapat dibaca"))
            return
        }

        val mediaSettings = StaticOverlaySettings.Builder()
            .setOverlayFrameAnchor(1f, 1f)
            .setBackgroundFrameAnchor(0.94f, 0.92f)
            .setScale(0.82f, 0.82f)
            .setAlphaScale(0.96f)
            .build()

        val mediaOverlay = BitmapOverlay.createStaticBitmapOverlay(originalLogo, mediaSettings)
        val overlayEffect = OverlayEffect(listOf(mediaOverlay))

        val editedMediaItem = EditedMediaItem.Builder(
            MediaItem.fromUri(inputUri)
        )
            .setEffects(Effects(emptyList(), listOf(overlayEffect)))
            .build()

        // Wajib transcode agar efek watermark benar-benar dirender ke frame video.
        Transformer.Builder(context)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(
                    composition: Composition,
                    exportResult: ExportResult,
                ) {
                    if (!output.exists() || output.length() <= 0L) {
                        onError(IllegalStateException("Hasil video kosong"))
                        return
                    }

                    if (verifyWatermark(output)) {
                        onCompleted()
                    } else {
                        output.delete()
                        onError(IllegalStateException("Logo tidak terdeteksi pada hasil video"))
                    }
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException,
                ) {
                    onError(exportException)
                }
            })
            .build()
            .start(editedMediaItem, output.absolutePath)
    }

    private fun verifyWatermark(file: File): Boolean {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)

            val frame = retriever.getFrameAtTime(
                350_000L,
                MediaMetadataRetriever.OPTION_CLOSEST
            ) ?: retriever.getFrameAtTime(0L)
            ?: return false

            // Area target watermark: kanan-atas saja.
            val startX = (frame.width * 0.68f).toInt().coerceAtLeast(0)
            val endY = (frame.height * 0.28f).toInt().coerceAtMost(frame.height)

            var redPixels = 0
            var bluePixels = 0
            var whitePixels = 0

            var y = 0
            while (y < endY) {
                var x = startX
                while (x < frame.width) {
                    val color = frame.getPixel(x, y)
                    val r = Color.red(color)
                    val g = Color.green(color)
                    val b = Color.blue(color)

                    if (r > 150 && r > g * 1.30 && r > b * 1.15) redPixels++
                    if (b > 90 && b > r * 1.18 && b > g * 1.02) bluePixels++
                    if (r > 215 && g > 215 && b > 215) whitePixels++

                    x += 3
                }
                y += 3
            }

            frame.recycle()

            // Logo asli punya area merah + biru yang besar. White-card-only tidak lolos.
            redPixels > 120 && bluePixels > 120 && whitePixels > 350
        } catch (_: Throwable) {
            false
        } finally {
            try {
                retriever.release()
            } catch (_: Throwable) {
            }
        }
    }
}
