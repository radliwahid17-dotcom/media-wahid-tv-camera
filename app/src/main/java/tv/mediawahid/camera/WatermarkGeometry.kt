package tv.mediawahid.camera

import android.graphics.Rect
import kotlin.math.roundToInt

object WatermarkGeometry {

    private const val HORIZONTAL_ANCHOR = 0.94f
    private const val VERTICAL_ANCHOR = 0.92f

    fun targetWidth(displayWidth: Int, template: WatermarkTemplate): Int {
        val ratio = if (template == WatermarkTemplate.DUAL) 0.18f else 0.20f
        val maxWidth = if (template == WatermarkTemplate.DUAL) 520 else 620

        return (displayWidth * ratio)
            .roundToInt()
            .coerceIn(160, maxWidth)
            .coerceAtMost((displayWidth * 0.42f).roundToInt().coerceAtLeast(1))
    }

    fun topCornerRect(
        frameWidth: Int,
        frameHeight: Int,
        logoWidth: Int,
        logoHeight: Int,
        right: Boolean
    ): Rect {
        val top = (frameHeight * ((1f - VERTICAL_ANCHOR) / 2f))
            .roundToInt()
            .coerceAtLeast(0)

        return if (right) {
            val rightEdge = (frameWidth * ((HORIZONTAL_ANCHOR + 1f) / 2f))
                .roundToInt()
                .coerceAtMost(frameWidth)

            Rect(
                (rightEdge - logoWidth).coerceAtLeast(0),
                top,
                rightEdge,
                (top + logoHeight).coerceAtMost(frameHeight)
            )
        } else {
            val leftEdge = (frameWidth * ((1f - HORIZONTAL_ANCHOR) / 2f))
                .roundToInt()
                .coerceAtLeast(0)

            Rect(
                leftEdge,
                top,
                (leftEdge + logoWidth).coerceAtMost(frameWidth),
                (top + logoHeight).coerceAtMost(frameHeight)
            )
        }
    }
}
