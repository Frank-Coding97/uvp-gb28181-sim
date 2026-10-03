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
 *
 * ## [crop] —— 拉框放大/缩小（A.2.3.1.8/.9）的视窗
 *
 * 语义是「把选定框内的图像放大到整个输出画面」，等价于**只采样那一块**，所以：
 *  - 四边形仍然铺满画框（±1），**改的是 uv**（采样窗缩到裁剪块里）；
 *  - 缩放的活由 uv 干了，`frameScale` 那套 center-crop 不再体现在顶点上 ——
 *    但它仍然是「画框看到的是相机画面的哪一块」的尺子，所以换算 uv 时必须带上它
 *    （见 [quadParamX] / [quadParamY]：`frameScale = 1` 时退化成朴素插值）。
 *
 * ⛔ [VideoDragZoomViewport.IDENTITY] **走原路径回原样**（顶点 ±frameScale、uv 原样透传），
 * 而不是"用新公式算一遍恒等值" —— 这样"平台没下发过拉框时逐帧与加本功能之前完全一致"
 * 是**字节级**成立的，不必依赖浮点运算顺序。
 *
 * ⛔ 镜像与裁剪的组合（本函数最容易做错的一处）：`mirror` 改的是**位置**、`crop` 改的是 **uv**，
 * 而裁剪框是操作员在**平台上看到的（已经镜像过的）画面**里框的 ⇒ "屏幕左沿该采样哪一块"
 * 必须先把显示坐标按镜像折回去（`1 - u`）、**再**按 `frameScale` 换算成四边形参数。
 * 两步合成一步或顺序颠倒，单开镜像、单开裁剪都看不出问题，
 * **一叠加就朝镜像轴对称的另一侧放大**（有专门的用例钉住）。
 *
 * ⛔⛔ 纵向还比横向多一层：本 pass 之后 blit 时**上下会再翻一次**，所以渲染帧上沿的 uv 最终落在
 * 编码帧的**下**沿。实现上体现为"两个边的取值交叉装配"（见函数体内注释）——
 * 代码看似"把上下沿写反了"，那正是翻转的兑现点，**不要按横向的对称性把它改回来**。
 * 判定口径是"编码帧顶行该看到显示框上沿"，有真机锚点用例钉住（s=16:9、偏心框）。
 */
internal fun cameraQuadVertices(
    frameScaleX: Float,
    frameScaleY: Float,
    textureCoordinates: FloatArray,
    mirrorX: Boolean = false,
    mirrorY: Boolean = false,
    crop: VideoDragZoomViewport = VideoDragZoomViewport.IDENTITY,
): FloatArray {
    val uvs = if (textureCoordinates.size >= CAMERA_QUAD_UV_COUNT) {
        textureCoordinates
    } else {
        DEFAULT_CAMERA_QUAD_UV
    }
    val mx = if (mirrorX) -1f else 1f
    val my = if (mirrorY) -1f else 1f
    if (crop.isIdentity) {
        return floatArrayOf(
            -frameScaleX * mx, -frameScaleY * my, uvs[0], uvs[1],
             frameScaleX * mx, -frameScaleY * my, uvs[2], uvs[3],
            -frameScaleX * mx,  frameScaleY * my, uvs[4], uvs[5],
             frameScaleX * mx,  frameScaleY * my, uvs[6], uvs[7],
        )
    }
    // 裁剪窗在"整幅相机画面的四边形参数"里的范围（0 = 四边形一端，1 = 另一端）。
    //
    // ⛔ 镜像必须先于 `frameScale` 换算施加：`crop` 是操作员在**平台上看到的（已镜像）画面**
    //    里框的，所以"屏幕上沿/左沿"对应的**未经裁剪的显示坐标**要先镜像回四边形那一侧
    //    （`1 - u`），再按 `frameScale` 换算成四边形参数。两步合成一步、或顺序颠倒，
    //    单开镜像、单开裁剪都看不出来，**一叠加就朝镜像轴对称的另一侧放大**。
    //
    // ⛔⛔ 纵向有两处与横向**不同**（2026-09-20 真机实测补上，漏一处就"横向对、纵向错"）：
    //
    //  1) **显示坐标要折回**：相机纹理的 `v` 在**最终输出**上是**反**的 ——
    //     `DEFAULT_CAMERA_QUAD_UV` 把左下写成 `v=1`、左上写成 `v=0` 正是这个反向留下的痕迹；
    //     恒等分支"uv 原样透传"连同下游把它一起抵消掉了，所以从恒等分支上完全看不出来。
    //     于是「显示行的上沿」对应的是 `1 - crop.top` 那一侧（横向没有这一步）。
    //  2) **两个边要换位**：本 pass 之后 blit 到编码帧时**又上下翻一次**（见 `CameraTexturePass.init`
    //     里"补偿 blit 的 Y 翻转"那段），所以**渲染帧上沿的 uv 会落到编码帧的下沿**。
    //     即：编码帧顶行看到的是 `dyBottom`、底行看到的是 `dyTop` —— 变量名保持"渲染帧的上/下沿"
    //     语义（与顶点顺序一一对应），但取值时必须交叉。
    //
    // ⛔ 只做 1) 不做 2)：框的位置对了、**画面内容整体上下颠倒**；
    //    只做 2) 不做 1)：位置错到镜像侧、内容也倒；
    //    两处都不做（原实现）：**横向完全正确、纵向搬来镜像侧的同一块**（框上半 → 放大出下半）。
    //    ⛔ 而框在画面**正中**时上下对称 —— 上面任何一种错法都**一点异常都看不出来**，
    //    只有**偏心框**才暴露（真机首次验证就是这么漏掉的）。
    //
    // 校验口径（真机可复现）：设 sy=16/9、平台框 top=1/8、h=1/3，编码帧顶行应采到
    // 「未镜像显示行 = crop.top」处的 v；用未裁剪路径的映射 `v(行d) = (1 - (1-2d)/sy)/2` 反解回行号，
    // 应当恰好是 1/8（旧代码给出的是 0.5417 = 1 - 1/8 - 1/3 + … 即镜像位）。
    val dxLeft = quadParamX(if (mx > 0f) crop.left else 1f - crop.left, frameScaleX)
    val dxRight = quadParamX(if (mx > 0f) crop.right else 1f - crop.right, frameScaleX)
    // 编码帧**顶行**该看到的 v：即显示框上沿在源画面里的样子（镜像时先折回源行）。
    val vAtDisplayTop = quadParamY(if (my > 0f) 1f - crop.top else crop.top, frameScaleY)
    // 编码帧**底行**该看到的 v：显示框下沿。
    val vAtDisplayBottom = quadParamY(if (my > 0f) 1f - crop.bottom else crop.bottom, frameScaleY)
    // ⛔ 交叉装配：blit 的上下翻在这里兑现（见上面第 2) 条）。
    val dyTop = vAtDisplayBottom
    val dyBottom = vAtDisplayTop
    val out = FloatArray(16)
    // 顶点顺序与 uv 角点一一对应：0=左下 1=右下 2=左上 3=右上。
    val baseX = floatArrayOf(-1f, 1f, -1f, 1f)
    val baseY = floatArrayOf(-1f, -1f, 1f, 1f)
    for (i in 0 until 4) {
        val px = baseX[i] * mx
        val py = baseY[i] * my
        // 屏幕左沿采样裁剪窗左沿、右沿采样右沿（上/下同理）—— 与顶点原始位置无关，
        // 只与它**最终落在屏幕哪一侧**有关。
        val dx = if (px < 0f) dxLeft else dxRight
        val dy = if (py > 0f) dyTop else dyBottom
        out[i * 4] = px
        out[i * 4 + 1] = py
        out[i * 4 + 2] = interpolateU(uvs, dx, dy)
        out[i * 4 + 3] = interpolateV(uvs, dx, dy)
    }
    return out
}

/**
 * 显示横向归一化 `u`（0 = 画框左沿）→ 相机四边形参数 `dx`（0 = 四边形左沿）。
 *
 * 四边形在 NDC 上铺到 ±`frameScale`（`setFrameSize(cropToFill = true)` 的 center-crop），
 * 所以 `frameScale > 1` 时画框只看到中央 `1/frameScale` 宽的一条 ⇒ 必须带上这个尺度，
 * 否则裁剪窗会按"整幅相机画面"解释，平台框右半边而设备放大左半边。
 */
internal fun quadParamX(u: Float, frameScaleX: Float): Float =
    if (frameScaleX <= 0f) u else ((2f * u - 1f) / frameScaleX + 1f) / 2f

/** 显示纵向归一化 `v`（0 = 画框**上**沿，与 uv 的 v 方向一致）→ 四边形参数 `dy`（0 = 四边形上沿）。 */
internal fun quadParamY(v: Float, frameScaleY: Float): Float =
    if (frameScaleY <= 0f) v else (1f - (1f - 2f * v) / frameScaleY) / 2f

/** 四边形内按参数 `(dx, dy)` 双线性插值取 u。 */
internal fun interpolateU(uvs: FloatArray, dx: Float, dy: Float): Float =
    (1f - dy) * ((1f - dx) * uvs[4] + dx * uvs[6]) +
        dy * ((1f - dx) * uvs[0] + dx * uvs[2])

/** 四边形内按参数 `(dx, dy)` 双线性插值取 v。 */
internal fun interpolateV(uvs: FloatArray, dx: Float, dy: Float): Float =
    (1f - dy) * ((1f - dx) * uvs[5] + dx * uvs[7]) +
        dy * ((1f - dx) * uvs[1] + dx * uvs[3])
