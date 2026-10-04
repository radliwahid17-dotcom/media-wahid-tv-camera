package tv.mediawahid.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Paint
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt

class PhotoWatermarker(private val context: Context) {

    fun process(
        input: File,
        output: File,
        template: WatermarkTemplate
    ) {
        if (output.exists()) output.delete()

        val source = ImageDecoder.createSource(input)
        val photo = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE

            val maxSide = maxOf(info.size.width, info.size.height)
            if (maxSide > 4096) {
                val scale = 4096f / maxSide.toFloat()
                decoder.setTargetSize(
                    (info.size.width * scale).roundToInt().coerceAtLeast(1),
                    (info.size.height * scale).roundToInt().coerceAtLeast(1)
                )
            }
        }

        val mediaLogo = decodeLogo(R.drawable.media_wahid_logo_original)
            ?: run {
                photo.recycle()
                error("Logo MEDIA WAHID TV tidak dapat dibaca")
            }

        val masjidLogo = if (template == WatermarkTemplate.DUAL) {
            decodeLogo(R.drawable.masjid_raya_logo)
                ?: run {
                    photo.recycle()
                    mediaLogo.recycle()
                    error("Logo Masjid tidak dapat dibaca")
                }
        } else {
            null
        }

        val result = Bitmap.createBitmap(
            photo.width,
            photo.height,
            Bitmap.Config.ARGB_8888
        )

        try {
            val canvas = Canvas(result)
            canvas.drawBitmap(photo, 0f, 0f, null)

            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                alpha = 255
            }

            val targetWidth = WatermarkGeometry.targetWidth(result.width, template)

            drawLogo(
                canvas = canvas,
                frameWidth = result.width,
                frameHeight = result.height,
                logo = mediaLogo,
                targetWidth = targetWidth,
                right = true,
                paint = paint
            )

            if (masjidLogo != null) {
                drawLogo(
                    canvas = canvas,
                    frameWidth = result.width,
                    frameHeight = result.height,
                    logo = masjidLogo,
                    targetWidth = targetWidth,
                    right = false,
                    paint = paint
                )
            }

            FileOutputStream(output).use { stream ->
                if (!result.compress(Bitmap.CompressFormat.JPEG, 97, stream)) {
                    error("Foto gagal disimpan")
                }
            }
        } finally {
            photo.recycle()
            result.recycle()
            mediaLogo.recycle()
            masjidLogo?.recycle()
        }

        if (!output.exists() || output.length() <= 0L) {
            output.delete()
            error("Hasil foto kosong")
        }

        if (!WatermarkVerifier(context).verifyPhoto(output, template)) {
            output.delete()
            error("Watermark template belum terverifikasi pada hasil foto")
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

    private fun drawLogo(
        canvas: Canvas,
        frameWidth: Int,
        frameHeight: Int,
        logo: Bitmap,
        targetWidth: Int,
        right: Boolean,
        paint: Paint
    ) {
        val targetHeight = (
            targetWidth * logo.height.toFloat() / logo.width.toFloat()
        )
            .roundToInt()
            .coerceAtLeast(1)

        val rect = WatermarkGeometry.topCornerRect(
            frameWidth = frameWidth,
            frameHeight = frameHeight,
            logoWidth = targetWidth,
            logoHeight = targetHeight,
            right = right
        )

        canvas.drawBitmap(logo, null, rect, paint)
    }
}
