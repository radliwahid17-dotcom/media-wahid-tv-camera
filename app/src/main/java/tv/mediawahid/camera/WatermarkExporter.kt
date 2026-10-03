package tv.mediawahid.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.DrawableOverlay
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.StaticOverlaySettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import java.io.File

object BrandPainter {
    private const val BLUE = 0xFF34398F.toInt()
    private const val RED = 0xFFED1C24.toInt()

    fun draw(canvas: Canvas, width: Int, height: Int) {
        val w = width.toFloat()
        val h = height.toFloat()
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(238, 255, 255, 255) }
        canvas.drawRoundRect(0f, 0f, w, h, h * 0.12f, h * 0.12f, bg)

        val left = w * 0.035f
        val top = h * 0.16f
        val markW = w * 0.28f
        val markH = h * 0.68f
        val blue = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BLUE }
        val red = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = RED }

        fun poly(paint: Paint, vararg pts: Float) {
            val p = Path()
            p.moveTo(left + pts[0] * markW, top + pts[1] * markH)
            var i = 2
            while (i < pts.size) {
                p.lineTo(left + pts[i] * markW, top + pts[i + 1] * markH)
                i += 2
            }
            p.close()
            canvas.drawPath(p, paint)
        }

        poly(blue, 0.00f,0.62f, 0.18f,0.48f, 0.18f,0.87f, 0.00f,0.76f)
        poly(blue, 0.22f,0.44f, 0.39f,0.28f, 0.39f,0.91f, 0.22f,0.84f)
        poly(blue, 0.43f,0.26f, 0.57f,0.10f, 0.57f,0.89f, 0.43f,0.92f)
        poly(red,  0.61f,0.11f, 0.74f,0.24f, 0.74f,0.82f, 0.61f,0.91f)
        poly(red,  0.78f,0.28f, 0.92f,0.42f, 0.92f,0.70f, 0.78f,0.81f)
        poly(red,  0.95f,0.45f, 1.00f,0.52f, 0.95f,0.60f)
        poly(red,  0.00f,0.79f, 0.58f,0.96f, 0.58f,1.00f, 0.00f,0.88f)
        poly(blue, 0.58f,0.96f, 1.00f,0.67f, 1.00f,0.75f, 0.58f,1.00f)

        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = BLUE
            textSize = h * 0.26f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val tv = Paint(title).apply { color = RED }
        val x = w * 0.34f
        val baseline = h * 0.52f
        canvas.drawText("MEDIA WAHID", x, baseline, title)
        canvas.drawText("TV", w * 0.82f, baseline, tv)

        val sub = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.DKGRAY
            textSize = h * 0.15f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        canvas.drawText("OFFICIAL VIDEO", x, h * 0.76f, sub)
    }
}

@UnstableApi
class WatermarkExporter(private val context: Context) {
    fun export(input: File, output: File, onCompleted: () -> Unit, onError: (Throwable) -> Unit) {
        if (output.exists()) output.delete()

        val bitmap = Bitmap.createBitmap(600, 170, Bitmap.Config.ARGB_8888)
        BrandPainter.draw(Canvas(bitmap), bitmap.width, bitmap.height)
        val drawable = BitmapDrawable(context.resources, bitmap)

        val settings = StaticOverlaySettings.Builder()
            .setOverlayFrameAnchor(1f, 1f)
            .setBackgroundFrameAnchor(0.95f, 0.92f)
            .setAlphaScale(0.92f)
            .build()

        val overlay = DrawableOverlay.createStaticDrawableOverlay(drawable, settings)
        val effect = OverlayEffect(listOf(overlay))
        val edited = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(input)))
            .setEffects(Effects(emptyList(), listOf(effect)))
            .build()

        Transformer.Builder(context)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    onCompleted()
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
            .start(edited, output.absolutePath)
    }
}
