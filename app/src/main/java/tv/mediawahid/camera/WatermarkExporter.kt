package tv.mediawahid.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
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
    ) {
        if (output.exists()) output.delete()

        val originalLogo = BitmapFactory.decodeResource(
            context.resources,
            R.drawable.media_wahid_logo
        ) ?: run {
            onError(IllegalStateException("Logo MEDIA WAHID TV tidak dapat dibaca"))
            return
        }

        // Pakai logo asli, hanya diberi kartu putih supaya tetap jelas di video terang/gelap.
        val cardWidth = 380
        val cardHeight = 250
        val logoCard = Bitmap.createBitmap(cardWidth, cardHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(logoCard)
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(238, 255, 255, 255) }
        canvas.drawRoundRect(RectF(0f, 0f, cardWidth.toFloat(), cardHeight.toFloat()), 24f, 24f, bg)

        val pad = 18
        val dst = RectF(
            pad.toFloat(),
            pad.toFloat(),
            (cardWidth - pad).toFloat(),
            (cardHeight - pad).toFloat()
        )
        canvas.drawBitmap(originalLogo, null, dst, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))

        val settings = StaticOverlaySettings.Builder()
            .setOverlayFrameAnchor(1f, 1f)
            .setBackgroundFrameAnchor(0.94f, 0.92f)
            .setScale(0.58f, 0.58f)
            .setAlphaScale(0.96f)
            .build()

        val logoOverlay = BitmapOverlay.createStaticBitmapOverlay(logoCard, settings)
        val overlayEffect = OverlayEffect(listOf(logoOverlay))

        val editedMediaItem = EditedMediaItem.Builder(
            MediaItem.fromUri(Uri.fromFile(input))
        )
            .setEffects(Effects(emptyList(), listOf(overlayEffect)))
            .build()

        // Paksa transcode supaya efek video benar-benar dieksekusi,
        // bukan sekadar salin stream MP4 mentah.
        Transformer.Builder(context)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(
                    composition: Composition,
                    exportResult: ExportResult,
                ) {
                    if (!output.exists() || output.length() <= 0L) {
                        onError(IllegalStateException("Hasil watermark kosong"))
                        return
                    }

                    if (verifyWatermark(output)) {
                        onCompleted()
                    } else {
                        output.delete()
                        onError(IllegalStateException("Watermark tidak terdeteksi pada video hasil"))
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
                300_000L,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC
            ) ?: retriever.getFrameAtTime(0L)
            ?: return false

            val startX = (frame.width * 0.55f).toInt().coerceAtLeast(0)
            val endY = (frame.height * 0.40f).toInt().coerceAtMost(frame.height)

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

                    if (r > 155 && r > g * 1.35 && r > b * 1.20) redPixels++
                    if (b > 95 && b > r * 1.25 && b > g * 1.05) bluePixels++
                    if (r > 220 && g > 220 && b > 220) whitePixels++

                    x += 5
                }
                y += 5
            }

            frame.recycle()

            redPixels > 25 && bluePixels > 25 && whitePixels > 120
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
