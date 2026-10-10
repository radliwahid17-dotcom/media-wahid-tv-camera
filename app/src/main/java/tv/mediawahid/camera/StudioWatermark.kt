package tv.mediawahid.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RectF
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.CanvasOverlay
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.TextureOverlay

/**
 * The EXACT approved original artwork is shared by the photo and video paths.
 * Drawing into frame-sized coordinates avoids incorrect logo size/orientation
 * from hard-coded output dimensions or inconsistent overlay scaling.
 */
class StudioWatermark(context: Context) : AutoCloseable {
    private val mediaLogo: Bitmap = load(context, R.drawable.media_wahid_logo_original)
    private val masjidLogo: Bitmap = load(context, R.drawable.masjid_raya_logo)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    fun drawOnPhoto(bitmap: Bitmap, template: WatermarkTemplate) {
        draw(Canvas(bitmap), template)
    }

    private fun draw(canvas: Canvas, template: WatermarkTemplate) {
        val width = canvas.width.toFloat()
        val height = canvas.height.toFloat()
        if (width <= 0f || height <= 0f) return
        val shortEdge = minOf(width, height)
        val margin = shortEdge * 0.035f
        val maxWidth = shortEdge * 0.20f
        val targetWidth = if (template == WatermarkTemplate.DUAL) {
            minOf(maxWidth, (width - margin * 3f) / 2f)
        } else maxWidth
        if (template == WatermarkTemplate.DUAL) {
            drawLogo(canvas, masjidLogo, margin, margin, targetWidth)
        }
        drawLogo(canvas, mediaLogo, width - margin - targetWidth, margin, targetWidth)
    }

    private fun drawLogo(canvas: Canvas, logo: Bitmap, x: Float, y: Float, targetWidth: Float) {
        val targetHeight = targetWidth * logo.height.toFloat() / logo.width.toFloat()
        canvas.drawBitmap(logo, null, RectF(x, y, x + targetWidth, y + targetHeight), paint)
    }

    /**
     * Media3 overlay is drawn in its actual per-frame pixel coordinates.
     * No virtual 1080p fixed-size canvas and no incorrectly stretched logos.
     */
    @OptIn(UnstableApi::class)
    fun videoEffects(template: WatermarkTemplate): List<Effect> {
        val single = object : CanvasOverlay(true) {
            override fun onDraw(canvas: Canvas, presentationTimeUs: Long) {
                canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                draw(canvas, template)
            }
        }
        return listOf(OverlayEffect(listOf<TextureOverlay>(single)))
    }

    override fun close() {
        if (!mediaLogo.isRecycled) mediaLogo.recycle()
        if (!masjidLogo.isRecycled) masjidLogo.recycle()
    }

    companion object {
        private fun load(context: Context, id: Int): Bitmap =
            BitmapFactory.decodeResource(context.resources, id, BitmapFactory.Options().apply {
                inScaled = false
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }) ?: error("Aset watermark tidak tersedia")
    }
}
