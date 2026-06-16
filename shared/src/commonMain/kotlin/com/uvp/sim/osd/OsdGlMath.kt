package com.uvp.sim.osd

/**
 * "#RRGGBB" 或 "#AARRGGBB" → ARGB int。
 *
 * 解析失败默认返回不透明白色,渲染层不会因为颜色字段崩。放在 commonMain 让单测无依赖跑。
 */
internal fun parseHexColorArgb(hex: String): Int {
    val s = hex.trim().removePrefix("#")
    return try {
        when (s.length) {
            6 -> 0xFF000000.toInt() or s.toInt(16)
            8 -> s.toLong(16).toInt()
            else -> 0xFFFFFFFF.toInt()
        }
    } catch (_: NumberFormatException) {
        0xFFFFFFFF.toInt()
    }
}

/** 屏幕像素 X(0..width)→ NDC(-1..1)。 */
internal fun pixelToNdcX(px: Float, viewportWidth: Int): Float =
    if (viewportWidth <= 0) 0f else (px / viewportWidth) * 2f - 1f

/** 屏幕像素 Y(0..height,Y 朝下)→ NDC(-1..1,Y 朝上)。 */
internal fun pixelToNdcY(py: Float, viewportHeight: Int): Float =
    if (viewportHeight <= 0) 0f else 1f - (py / viewportHeight) * 2f
