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

/** 相机四边形的 uv 个数（4 顶点 × 2）。 */
internal const val CAMERA_QUAD_UV_COUNT = 8

/**
 * 相机四边形 uv 的默认值 —— 顺序与顶点一一对应：
 * 左下、右下、左上、右上。**v 已翻转**（左下是 v=1）是因为 FBO 原点在左下、
 * 而纹理来自上原点为 0 的相机帧，靠这一层反过来抵消 blit 的 Y 翻转。
 */
internal val DEFAULT_CAMERA_QUAD_UV = floatArrayOf(
    0f, 1f,
    1f, 1f,
    0f, 0f,
    1f, 0f,
)

/**
 * 生成相机会制四边形**交错的顶点缓冲**（`x, y, u, v` × 4，TRIANGLE_STRIP）。
 *
 * 抽到 commonMain 而不是留在 `CameraTexturePass` 里，是为了让"画面翻转（A.2.1.23）
 * 只改位置、不动 uv"这条**能被单测钉住** —— 它是本功能唯一容易做错又不报错的地方
 * （见 [FrameMirrorTransformTest]）：
 *  - ⛔ 翻 **uv** 会与 SurfaceTexture 的 `getTransformMatrix()` 互相抵消或叠成旋转，
 *    取决于厂商 HAL 给的矩阵 —— 那种错只在部分机型上出现。
 *  - ✅ 翻**位置**是纯粹的显示变换，与纹理矩阵无关。
 *
 * [mirrorX] / [mirrorY] 为 `true` 时把对应轴的位置整体取反（即画面镜像）。
 */
internal fun cameraQuadVertices(
    frameScaleX: Float,
    frameScaleY: Float,
    textureCoordinates: FloatArray,
    mirrorX: Boolean = false,
    mirrorY: Boolean = false,
): FloatArray {
    val uvs = if (textureCoordinates.size >= CAMERA_QUAD_UV_COUNT) {
        textureCoordinates
    } else {
        DEFAULT_CAMERA_QUAD_UV
    }
    val mx = if (mirrorX) -1f else 1f
    val my = if (mirrorY) -1f else 1f
    return floatArrayOf(
        -frameScaleX * mx, -frameScaleY * my, uvs[0], uvs[1],
         frameScaleX * mx, -frameScaleY * my, uvs[2], uvs[3],
        -frameScaleX * mx,  frameScaleY * my, uvs[4], uvs[5],
         frameScaleX * mx,  frameScaleY * my, uvs[6], uvs[7],
    )
}
