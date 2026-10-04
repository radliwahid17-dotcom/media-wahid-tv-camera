package tv.mediawahid.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.MediaMetadataRetriever
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

object WatermarkVerifier {

    fun verifyPhoto(file: File): Boolean {
        val options = decodeOptionsForMaxSide(file, 1920)
        val bitmap = BitmapFactory.decodeFile(file.absolutePath, options) ?: return false
        return try {
            hasBrandPatternInCorner(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    fun verifyVideo(file: File): Boolean {
        val retriever = MediaMetadataRetriever()

        return try {
            retriever.setDataSource(file.absolutePath)

            val durationMs = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION
            )?.toLongOrNull() ?: 0L

            val width = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH
            )?.toIntOrNull()?.coerceAtLeast(1) ?: 1280

            val height = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT
            )?.toIntOrNull()?.coerceAtLeast(1) ?: 720

            val maxSide = max(width, height)
            val scale = if (maxSide > 1280) 1280f / maxSide.toFloat() else 1f
            val scaledWidth = (width * scale).roundToInt().coerceAtLeast(1)
            val scaledHeight = (height * scale).roundToInt().coerceAtLeast(1)

            val candidatesUs = listOf(
                0L,
                500_000L,
                if (durationMs > 3000L) 2_000_000L else (durationMs * 500L)
            ).distinct()

            var checked = 0
            var verified = 0

            for (timeUs in candidatesUs) {
                val frame = try {
                    retriever.getScaledFrameAtTime(
                        timeUs.coerceAtLeast(0L),
                        MediaMetadataRetriever.OPTION_CLOSEST,
                        scaledWidth,
                        scaledHeight
                    )
                } catch (_: Throwable) {
                    retriever.getFrameAtTime(
                        timeUs.coerceAtLeast(0L),
                        MediaMetadataRetriever.OPTION_CLOSEST
                    )
                } ?: continue

                checked++
                if (hasBrandPatternInCorner(frame)) verified++
                frame.recycle()

                if (verified >= 2) return true
            }

            checked > 0 && verified == checked
        } catch (_: Throwable) {
            false
        } finally {
            try {
                retriever.release()
            } catch (_: Throwable) {
            }
        }
    }

    private fun decodeOptionsForMaxSide(file: File, maxSide: Int): BitmapFactory.Options {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)

        var sample = 1
        var largest = max(bounds.outWidth, bounds.outHeight)
        while (largest / sample > maxSide && sample < 8) {
            sample *= 2
        }

        return BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
    }

    private fun hasBrandPatternInCorner(bitmap: Bitmap): Boolean {
        if (bitmap.width < 40 || bitmap.height < 40) return false

        // Watermark resmi selalu berada di kanan atas.
        // Verifikasi hanya area target agar konten video/foto di sudut lain
        // tidak pernah bisa memicu false-positive.
        val regionWidth = (bitmap.width * 0.38f).roundToInt().coerceAtLeast(1)
        val regionHeight = (bitmap.height * 0.32f).roundToInt().coerceAtLeast(1)

        return hasBrandPattern(
            bitmap,
            (bitmap.width - regionWidth).coerceAtLeast(0),
            0,
            bitmap.width,
            regionHeight.coerceAtMost(bitmap.height)
        )
    }

    private fun hasBrandPattern(
        bitmap: Bitmap,
        startX: Int,
        startY: Int,
        endX: Int,
        endY: Int
    ): Boolean {
        val step = max(1, minOf(bitmap.width, bitmap.height) / 420)

        var total = 0
        var red = 0
        var blue = 0
        var white = 0
        var dark = 0

        var y = startY
        while (y < endY) {
            var x = startX
            while (x < endX) {
                val color = bitmap.getPixel(x, y)
                val r = Color.red(color)
                val g = Color.green(color)
                val b = Color.blue(color)

                total++

                if (r > 150 && r > g * 1.22f && r > b * 1.10f) red++
                if (b > 90 && b > r * 1.12f && b > g * 1.02f) blue++
                if (r > 212 && g > 212 && b > 212) white++
                if (r < 70 && g < 70 && b < 70) dark++

                x += step
            }
            y += step
        }

        if (total <= 0) return false

        val redRatio = red.toFloat() / total
        val blueRatio = blue.toFloat() / total
        val whiteRatio = white.toFloat() / total
        val contrastRatio = (white + dark).toFloat() / total

        return redRatio >= 0.0025f &&
            blueRatio >= 0.0025f &&
            whiteRatio >= 0.010f &&
            contrastRatio >= 0.035f
    }
}
