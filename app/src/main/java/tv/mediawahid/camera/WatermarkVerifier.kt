package tv.mediawahid.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.MediaMetadataRetriever
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

class WatermarkVerifier(private val context: Context) {

    fun verifyPhoto(file: File, template: WatermarkTemplate): Boolean {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)

        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false

        var sample = 1
        val largest = max(bounds.outWidth, bounds.outHeight)
        while (largest / sample > 1920 && sample < 8) sample *= 2

        val bitmap = BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
        ) ?: return false

        return try {
            verifyFrame(
                frame = bitmap,
                template = template,
                originalDisplayWidth = bounds.outWidth
            )
        } finally {
            bitmap.recycle()
        }
    }

    fun verifyVideo(file: File, template: WatermarkTemplate): Boolean {
        val retriever = MediaMetadataRetriever()

        return try {
            retriever.setDataSource(file.absolutePath)

            val durationMs = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION
            )?.toLongOrNull() ?: 0L

            val rawWidth = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH
            )?.toIntOrNull()?.coerceAtLeast(1) ?: 1280

            val rawHeight = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT
            )?.toIntOrNull()?.coerceAtLeast(1) ?: 720

            val rotation = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION
            )?.toIntOrNull() ?: 0

            val displayWidth = if (rotation == 90 || rotation == 270) rawHeight else rawWidth
            val displayHeight = if (rotation == 90 || rotation == 270) rawWidth else rawHeight

            val maxSide = max(displayWidth, displayHeight)
            val scale = if (maxSide > 1280) 1280f / maxSide.toFloat() else 1f
            val requestWidth = (displayWidth * scale).roundToInt().coerceAtLeast(1)
            val requestHeight = (displayHeight * scale).roundToInt().coerceAtLeast(1)

            val candidatesUs = listOf(
                0L,
                700_000L,
                if (durationMs > 4000L) 2_500_000L else (durationMs * 500L)
            ).distinct()

            var checked = 0
            var verified = 0

            for (timeUs in candidatesUs) {
                val frame = try {
                    retriever.getScaledFrameAtTime(
                        timeUs.coerceAtLeast(0L),
                        MediaMetadataRetriever.OPTION_CLOSEST,
                        requestWidth,
                        requestHeight
                    )
                } catch (_: Throwable) {
                    retriever.getFrameAtTime(
                        timeUs.coerceAtLeast(0L),
                        MediaMetadataRetriever.OPTION_CLOSEST
                    )
                } ?: continue

                checked++

                if (verifyFrame(frame, template, displayWidth)) {
                    verified++
                }

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

    private fun verifyFrame(
        frame: Bitmap,
        template: WatermarkTemplate,
        originalDisplayWidth: Int
    ): Boolean {
        val mediaLogo = decodeLogo(R.drawable.media_wahid_logo_original) ?: return false

        try {
            val mediaOk = matchesLogo(
                frame = frame,
                reference = mediaLogo,
                originalDisplayWidth = originalDisplayWidth,
                template = template,
                right = true
            )

            if (!mediaOk) return false
            if (template == WatermarkTemplate.MEDIA_ONLY) return true

            val masjidLogo = decodeLogo(R.drawable.masjid_raya_logo) ?: return false

            return try {
                matchesLogo(
                    frame = frame,
                    reference = masjidLogo,
                    originalDisplayWidth = originalDisplayWidth,
                    template = template,
                    right = false
                )
            } finally {
                masjidLogo.recycle()
            }
        } finally {
            mediaLogo.recycle()
        }
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

    private fun matchesLogo(
        frame: Bitmap,
        reference: Bitmap,
        originalDisplayWidth: Int,
        template: WatermarkTemplate,
        right: Boolean
    ): Boolean {
        val scaleToFrame = frame.width.toFloat() / originalDisplayWidth.coerceAtLeast(1)
        val fullTargetWidth = WatermarkGeometry.targetWidth(originalDisplayWidth, template)

        val logoWidth = (fullTargetWidth * scaleToFrame)
            .roundToInt()
            .coerceAtLeast(20)
            .coerceAtMost(frame.width)

        val logoHeight = (
            logoWidth * reference.height.toFloat() / reference.width.toFloat()
        )
            .roundToInt()
            .coerceAtLeast(12)
            .coerceAtMost(frame.height)

        val rect = WatermarkGeometry.topCornerRect(
            frameWidth = frame.width,
            frameHeight = frame.height,
            logoWidth = logoWidth,
            logoHeight = logoHeight,
            right = right
        )

        if (rect.width() < 20 || rect.height() < 12) return false

        val scaledReference = Bitmap.createScaledBitmap(
            reference,
            rect.width(),
            rect.height(),
            true
        )

        return try {
            compareInformativePixels(frame, rect.left, rect.top, scaledReference)
        } finally {
            scaledReference.recycle()
        }
    }

    private fun compareInformativePixels(
        frame: Bitmap,
        startX: Int,
        startY: Int,
        reference: Bitmap
    ): Boolean {
        val step = max(1, reference.width / 140)

        var informative = 0
        var close = 0
        var totalDifference = 0L

        var y = 0
        while (y < reference.height) {
            var x = 0
            while (x < reference.width) {
                val refColor = reference.getPixel(x, y)
                val rr = Color.red(refColor)
                val rg = Color.green(refColor)
                val rb = Color.blue(refColor)

                val distanceFromWhite =
                    abs(rr - 245) + abs(rg - 245) + abs(rb - 245)

                if (distanceFromWhite >= 40) {
                    val targetX = startX + x
                    val targetY = startY + y

                    if (targetX in 0 until frame.width && targetY in 0 until frame.height) {
                        val frameColor = frame.getPixel(targetX, targetY)
                        val fr = Color.red(frameColor)
                        val fg = Color.green(frameColor)
                        val fb = Color.blue(frameColor)

                        val diff = (
                            abs(fr - rr) +
                                abs(fg - rg) +
                                abs(fb - rb)
                            ) / 3

                        informative++
                        totalDifference += diff.toLong()

                        if (diff <= 70) close++
                    }
                }

                x += step
            }
            y += step
        }

        if (informative < 45) return false

        val averageDifference = totalDifference.toFloat() / informative
        val closeRatio = close.toFloat() / informative

        return averageDifference <= 58f && closeRatio >= 0.58f
    }
}
