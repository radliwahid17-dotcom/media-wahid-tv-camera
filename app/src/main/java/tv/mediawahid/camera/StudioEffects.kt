package tv.mediawahid.camera

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Brightness
import androidx.media3.effect.Contrast
import androidx.media3.effect.HslAdjustment
import androidx.media3.effect.RgbAdjustment

/**
 * Exactly six deterministic presets: Original + five optional looks.
 * Presets improve tonal appearance only; they do not recover missing detail.
 * The original photo/video is never overwritten.
 */
enum class StudioEffect(
    val title: String,
    val description: String,
    private val contrast: Float,
    private val saturation: Float,
    private val brightness: Float,
    private val red: Float = 1f,
    private val green: Float = 1f,
    private val blue: Float = 1f,
) {
    DEFAULT("Default", "Original Samsung", 1f, 1f, 0f),
    CRYSTAL("Crystal Clear", "Detail tampak lebih tegas", 1.08f, 1.04f, 0.015f),
    BRIGHT("Bright Vision", "Lebih terang untuk indoor", 1.02f, 1.03f, 0.065f),
    NATURAL("True Color", "Warna natural lebih hidup", 1.035f, 1.12f, 0.008f),
    CINEMATIC("Cinematic Pro", "Kontras dramatis sedikit dingin", 1.12f, 0.87f, -0.01f, 1.0f, 1.015f, 1.055f),
    MONO("Classic Mono", "Hitam putih profesional", 1.1f, 0f, 0f);

    fun photoFilter(): ColorMatrixColorFilter? {
        if (this == DEFAULT) return null
        val saturationMatrix = ColorMatrix().apply { setSaturation(saturation) }
        val offset = 127.5f * (1f - contrast) + brightness * 255f
        val grade = ColorMatrix(floatArrayOf(
            contrast * red, 0f, 0f, 0f, offset,
            0f, contrast * green, 0f, 0f, offset,
            0f, 0f, contrast * blue, 0f, offset,
            0f, 0f, 0f, 1f, 0f
        ))
        grade.postConcat(saturationMatrix)
        return ColorMatrixColorFilter(grade)
    }

    fun applyToPhoto(input: Bitmap): Bitmap {
        val output = Bitmap.createBitmap(input.width, input.height, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = photoFilter()
        }
        Canvas(output).drawBitmap(input, 0f, 0f, paint)
        return output
    }

    @OptIn(UnstableApi::class)
    fun videoEffects(): List<Effect> {
        if (this == DEFAULT) return emptyList()
        val effects = mutableListOf<Effect>()
        if (saturation != 1f) {
            effects.add(HslAdjustment.Builder()
                .adjustSaturation(((saturation - 1f) * 100f).coerceIn(-100f, 100f))
                .build())
        }
        if (contrast != 1f) effects.add(Contrast((contrast - 1f).coerceIn(-1f, 1f)))
        if (brightness != 0f) effects.add(Brightness(brightness.coerceIn(-1f, 1f)))
        if (red != 1f || green != 1f || blue != 1f) {
            effects.add(RgbAdjustment.Builder()
                .setRedScale(red).setGreenScale(green).setBlueScale(blue).build())
        }
        return effects
    }
}
