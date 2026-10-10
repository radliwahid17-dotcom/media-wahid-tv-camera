package tv.mediawahid.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.StaticOverlaySettings
import androidx.media3.effect.TextureOverlay

/** Keeps the real assets from the existing Camera app. No placeholder branding. */
class StudioWatermark(private val context: Context) {
    fun drawOnPhoto(bitmap: Bitmap, template: WatermarkTemplate) {
        val canvas = Canvas(bitmap)
        val shortSide = minOf(bitmap.width, bitmap.height).toFloat()
        val margin = shortSide * 0.034f
        val logoWidth = shortSide * 0.20f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        if (template == WatermarkTemplate.DUAL) {
            drawImage(canvas, R.drawable.masjid_raya_logo, margin, margin, logoWidth, paint)
        }
        drawImage(
            canvas, R.drawable.media_wahid_logo_original,
            bitmap.width - margin - logoWidth, margin, logoWidth, paint
        )
    }

    private fun drawImage(
        canvas: Canvas, resource: Int, left: Float, top: Float,
        width: Float, paint: Paint
    ) {
        val logo = BitmapFactory.decodeResource(context.resources, resource)
            ?: error("Logo wajib tidak dapat dibaca")
        try {
            val height = width * logo.height / logo.width
            canvas.drawBitmap(logo, null, RectF(left, top, left + width, top + height), paint)
        } finally {
            logo.recycle()
        }
    }

    /**
     * Relative corners; layout is resolution-independent.
     * Each overlay is GPU-composited on every output video frame.
     */
    @OptIn(UnstableApi::class)
    fun videoEffects(template: WatermarkTemplate): List<Effect> {
        val overlays = mutableListOf<TextureOverlay>()
        if (template == WatermarkTemplate.DUAL) {
            overlays.add(videoLogo(R.drawable.masjid_raya_logo, left = true))
        }
        overlays.add(videoLogo(R.drawable.media_wahid_logo_original, left = false))
        return listOf(OverlayEffect(overlays))
    }

    @OptIn(UnstableApi::class)
    private fun videoLogo(resource: Int, left: Boolean): BitmapOverlay {
        val image = BitmapFactory.decodeResource(context.resources, resource)
            ?: error("Logo wajib tidak dapat dibaca")
        val anchorX = if (left) -0.94f else 0.94f
        val logoAnchorX = if (left) -1f else 1f
        val settings = StaticOverlaySettings.Builder()
            .setBackgroundFrameAnchor(anchorX, 0.94f)
            .setOverlayFrameAnchor(logoAnchorX, 1f)
            .setScale(0.20f, 0.20f)
            .build()
        return BitmapOverlay.createStaticBitmapOverlay(image, settings)
    }
}
