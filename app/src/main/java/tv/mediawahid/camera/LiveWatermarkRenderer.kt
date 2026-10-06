package tv.mediawahid.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RectF
import androidx.camera.effects.Frame
import androidx.camera.view.PreviewView
import kotlin.math.roundToInt

class LiveWatermarkRenderer(
    context: Context,
    private val previewView: PreviewView,
) : AutoCloseable {

    private val mediaLogo: Bitmap = decodeRequired(
        context,
        R.drawable.media_wahid_logo_original,
        "Logo MEDIA WAHID TV"
    )

    private val masjidLogo: Bitmap = decodeRequired(
        context,
        R.drawable.masjid_raya_logo,
        "Logo Masjid"
    )

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        alpha = 255
    }

    private val uiToSensor = Matrix()
    private val uiToBuffer = Matrix()
    private val destination = RectF()

    @Volatile
    var template: WatermarkTemplate = WatermarkTemplate.DUAL

    /**
     * Returns false until the preview transform is valid. OverlayEffect drops those
     * frames, which is intentional: a frame must never be emitted without watermark.
     */
    fun draw(frame: Frame): Boolean {
        val canvas = frame.overlayCanvas
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

        val sensorToView = previewView.sensorToViewTransform ?: return false
        val viewWidth = previewView.width.toFloat()
        val viewHeight = previewView.height.toFloat()
        if (viewWidth <= 0f || viewHeight <= 0f) return false

        uiToSensor.reset()
        if (!sensorToView.invert(uiToSensor)) return false

        uiToBuffer.set(uiToSensor)
        uiToBuffer.postConcat(frame.sensorToBufferTransform)

        val checkpoint = canvas.save()
        canvas.setMatrix(uiToBuffer)

        drawTemplate(
            viewWidth = viewWidth,
            viewHeight = viewHeight,
            canvas = canvas,
            activeTemplate = template
        )

        canvas.restoreToCount(checkpoint)
        return true
    }

    private fun drawTemplate(
        viewWidth: Float,
        viewHeight: Float,
        canvas: android.graphics.Canvas,
        activeTemplate: WatermarkTemplate,
    ) {
        val shortEdge = minOf(viewWidth, viewHeight).coerceAtLeast(1f)
        val targetWidth = (shortEdge * 0.20f)
            .roundToInt()
            .coerceIn(150, 620)
            .toFloat()
        val margin = (shortEdge * 0.035f).coerceAtLeast(18f)

        if (activeTemplate == WatermarkTemplate.DUAL) {
            drawLogo(
                canvas = canvas,
                logo = masjidLogo,
                left = margin,
                top = margin,
                targetWidth = targetWidth
            )
        }

        drawLogo(
            canvas = canvas,
            logo = mediaLogo,
            left = (viewWidth - margin - targetWidth).coerceAtLeast(0f),
            top = margin,
            targetWidth = targetWidth
        )
    }

    private fun drawLogo(
        canvas: android.graphics.Canvas,
        logo: Bitmap,
        left: Float,
        top: Float,
        targetWidth: Float,
    ) {
        val targetHeight = targetWidth * logo.height.toFloat() / logo.width.toFloat()
        destination.set(
            left,
            top,
            left + targetWidth,
            top + targetHeight
        )
        canvas.drawBitmap(logo, null, destination, paint)
    }

    override fun close() {
        if (!mediaLogo.isRecycled) mediaLogo.recycle()
        if (!masjidLogo.isRecycled) masjidLogo.recycle()
    }

    companion object {
        private fun decodeRequired(
            context: Context,
            resourceId: Int,
            label: String,
        ): Bitmap {
            return BitmapFactory.decodeResource(
                context.resources,
                resourceId,
                BitmapFactory.Options().apply {
                    inScaled = false
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
            ) ?: error(label + " tidak dapat dibaca")
        }
    }
}
