package com.uvp.sim.osd

import com.uvp.sim.config.OsdPosition
import com.uvp.sim.config.OsdSize
import kotlin.math.sqrt

data class IosOsdPoint(
    val x: Double,
    val y: Double,
)

data class IosOsdColor(
    val red: Int,
    val green: Int,
    val blue: Int,
) {
    companion object {
        val WHITE = IosOsdColor(255, 255, 255)
        val BLACK = IosOsdColor(0, 0, 0)
    }
}

data class IosOsdWatermarkTile(
    val center: IosOsdPoint,
    val row: Int,
    val column: Int,
)

data class IosOsdWatermarkLayout(
    val stepX: Double,
    val stepY: Double,
    val angleDegrees: Double,
    val alpha: Double,
    val tiles: List<IosOsdWatermarkTile>,
)

/** Pure layout shared by the encoded OSD and the local preview overlay. */
object IosOsdLayout {
    const val MARGIN_PX = 6.0
    const val WATERMARK_ANGLE_DEGREES = -30.0
    const val WATERMARK_ALPHA = 0.28
    private const val WATERMARK_HORIZONTAL_STEP_MULTIPLIER = 2.5
    private const val WATERMARK_VERTICAL_STEP_MULTIPLIER = 4.0

    fun pixelSize(size: OsdSize, viewportHeight: Int): Int {
        val ratio = when (size) {
            OsdSize.SMALL -> 0.035f
            OsdSize.MEDIUM -> 0.050f
            OsdSize.LARGE -> 0.070f
        }
        return (viewportHeight * ratio).toInt().coerceAtLeast(16)
    }

    @Suppress("UNUSED_PARAMETER")
    fun timestampOrigin(text: String, persistedPosition: OsdPosition): IosOsdPoint? =
        if (text.isEmpty()) null else IosOsdPoint(MARGIN_PX, MARGIN_PX)

    @Suppress("UNUSED_PARAMETER")
    fun channelNameOrigin(
        text: String,
        viewportWidth: Double,
        textWidth: Double,
        persistedPosition: OsdPosition,
    ): IosOsdPoint? = if (text.isEmpty()) {
        null
    } else {
        IosOsdPoint(viewportWidth - MARGIN_PX - textWidth, MARGIN_PX)
    }

    fun parseFillColor(value: String): IosOsdColor = parseColor(value) ?: IosOsdColor.WHITE

    fun parseOutlineColor(value: String): IosOsdColor = parseColor(value) ?: IosOsdColor.BLACK

    fun watermarkLayout(
        text: String,
        viewportWidth: Double,
        viewportHeight: Double,
        textWidth: Double,
        textHeight: Double,
        angleDegrees: Double = WATERMARK_ANGLE_DEGREES,
    ): IosOsdWatermarkLayout {
        val stepX = textWidth * WATERMARK_HORIZONTAL_STEP_MULTIPLIER
        val stepY = textHeight * WATERMARK_VERTICAL_STEP_MULTIPLIER
        if (text.isEmpty() || viewportWidth <= 0.0 || viewportHeight <= 0.0 ||
            stepX <= 0.0 || stepY <= 0.0
        ) {
            return IosOsdWatermarkLayout(
                stepX = stepX,
                stepY = stepY,
                angleDegrees = angleDegrees,
                alpha = WATERMARK_ALPHA,
                tiles = emptyList(),
            )
        }

        val centerX = viewportWidth / 2.0
        val centerY = viewportHeight / 2.0
        val diagonal = sqrt(viewportWidth * viewportWidth + viewportHeight * viewportHeight)
        val half = diagonal / 2.0 + maxOf(stepX, stepY)
        val columns = (half / stepX).toInt() + 1
        val rows = (half / stepY).toInt() + 1
        val tiles = ArrayList<IosOsdWatermarkTile>((columns * 2 + 1) * (rows * 2 + 1))

        for (row in -rows..rows) {
            val rowOffsetX = if (row % 2 == 0) 0.0 else stepX / 2.0
            for (column in -columns..columns) {
                val localX = column * stepX + rowOffsetX
                val localY = row * stepY
                tiles += IosOsdWatermarkTile(
                    center = IosOsdPoint(
                        x = centerX + localX,
                        y = centerY + localY,
                    ),
                    row = row,
                    column = column,
                )
            }
        }

        return IosOsdWatermarkLayout(
            stepX = stepX,
            stepY = stepY,
            angleDegrees = angleDegrees,
            alpha = WATERMARK_ALPHA,
            tiles = tiles,
        )
    }

    private fun parseColor(value: String): IosOsdColor? {
        if (value.length != 7 || value[0] != '#') return null
        val rgb = value.substring(1).toIntOrNull(16) ?: return null
        return IosOsdColor(
            red = (rgb ushr 16) and 0xFF,
            green = (rgb ushr 8) and 0xFF,
            blue = rgb and 0xFF,
        )
    }
}
