package tv.mediawahid.camera

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
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
    ) = export(
        inputUri = Uri.fromFile(input),
        output = output,
        onCompleted = onCompleted,
        onError = onError,
    )

    fun export(
        inputUri: Uri,
        output: File,
        onCompleted: () -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        if (output.exists()) output.delete()

        // Pakai satu asset PNG tetap untuk UI, foto, dan video.
        // Asset ini tidak ditimpa saat build, jadi hasil APK selalu konsisten.
        val mediaLogo = BitmapFactory.decodeResource(
            context.resources,
            R.drawable.media_wahid_logo,
            BitmapFactory.Options().apply { inScaled = false }
        ) ?: run {
            onError(IllegalStateException("Logo MEDIA WAHID TV tidak dapat dibaca"))
            return
        }

        val mediaSettings = StaticOverlaySettings.Builder()
            .setOverlayFrameAnchor(1f, 1f)
            .setBackgroundFrameAnchor(0.94f, 0.92f)
            .setScale(0.82f, 0.82f)
            .setAlphaScale(0.96f)
            .build()

        val mediaOverlay = BitmapOverlay.createStaticBitmapOverlay(mediaLogo, mediaSettings)
        val overlayEffect = OverlayEffect(listOf(mediaOverlay))

        val editedMediaItem = EditedMediaItem.Builder(
            MediaItem.fromUri(inputUri)
        )
            .setEffects(Effects(emptyList(), listOf(overlayEffect)))
            .build()

        // Wajib transcode agar efek watermark benar-benar dirender ke frame video.
        Transformer.Builder(context)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(
                    composition: Composition,
                    exportResult: ExportResult,
                ) {
                    if (!output.exists() || output.length() <= 0L) {
                        onError(IllegalStateException("Hasil video kosong"))
                        return
                    }

                    // Transformer hanya menyatakan selesai setelah seluruh efek,
                    // termasuk watermark, selesai dirender ke output.
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
            .start(editedMediaItem, output.absolutePath)
    }

}
