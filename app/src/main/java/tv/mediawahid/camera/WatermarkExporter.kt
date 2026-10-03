package tv.mediawahid.camera

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.media3.common.MediaItem
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

        val logo = BitmapFactory.decodeResource(
            context.resources,
            R.drawable.media_wahid_logo
        ) ?: run {
            onError(IllegalStateException("Logo MEDIA WAHID TV tidak dapat dibaca"))
            return
        }

        // Anchor (1,1) = pojok kanan atas untuk overlay dan background.
        // Logo asli berukuran 300x186 px, cukup kecil untuk portrait dan landscape.
        val settings = StaticOverlaySettings.Builder()
            .setOverlayFrameAnchor(1f, 1f)
            .setBackgroundFrameAnchor(0.96f, 0.93f)
            .setAlphaScale(0.92f)
            .build()

        val logoOverlay = BitmapOverlay.createStaticBitmapOverlay(logo, settings)
        val overlayEffect = OverlayEffect(listOf(logoOverlay))

        val editedMediaItem = EditedMediaItem.Builder(
            MediaItem.fromUri(Uri.fromFile(input))
        )
            .setEffects(Effects(emptyList(), listOf(overlayEffect)))
            .build()

        Transformer.Builder(context)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(
                    composition: Composition,
                    exportResult: ExportResult,
                ) {
                    if (output.exists() && output.length() > 0L) {
                        onCompleted()
                    } else {
                        onError(IllegalStateException("Hasil watermark kosong"))
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
}
