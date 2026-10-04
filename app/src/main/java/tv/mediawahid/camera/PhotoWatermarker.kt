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

    fun process(input: File, output: File) {
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

        val mediaLogo = BitmapFactory.decodeResource(
            context.resources,
            R.drawable.media_wahid_logo_original,
            BitmapFactory.Options().apply {
                inScaled = false
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
        ) ?: run {
            photo.recycle()
            error("Logo MEDIA WAHID TV tidak dapat dibaca")
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

            val marginX = (result.width * 0.03f).roundToInt().coerceAtLeast(12)
            val marginY = (result.height * 0.025f).roundToInt().coerceAtLeast(12)

            val desiredWidth = (result.width * 0.20f).roundToInt()
            val targetWidth = desiredWidth
                .coerceAtLeast((result.width * 0.18f).roundToInt())
                .coerceAtMost(620)
                .coerceAtMost(result.width - marginX * 2)
                .coerceAtLeast(1)

            val targetHeight =
                (targetWidth * mediaLogo.height.toFloat() / mediaLogo.width.toFloat())
                    .roundToInt()
                    .coerceAtLeast(1)

            val mediaDest = android.graphics.Rect(
                result.width - marginX - targetWidth,
                marginY,
                result.width - marginX,
                marginY + targetHeight
            )

            canvas.drawBitmap(mediaLogo, null, mediaDest, paint)

            FileOutputStream(output).use { stream ->
                if (!result.compress(Bitmap.CompressFormat.JPEG, 97, stream)) {
                    error("Foto gagal disimpan")
                }
            }
        } finally {
            photo.recycle()
            result.recycle()
            mediaLogo.recycle()
        }

        if (!output.exists() || output.length() <= 0L) {
            output.delete()
            error("Hasil foto kosong")
        }

        if (!WatermarkVerifier.verifyPhoto(output)) {
            output.delete()
            error("Logo MEDIA WAHID TV belum terdeteksi pada hasil foto")
        }
    }
}
