package com.uvp.sim.osd

import com.uvp.sim.domain.DragZoomRect

/**
 * 拉框放大/缩小（GB/T 28181-2022 A.2.3.1.8 / A.2.3.1.9）的**平台拉框**，在画面归一化空间里。
 *
 * ## 坐标口径（标准原文，别照抄"0~1000"那种说法）
 * 附录 A.2.3.1.8/.9 的注：
 *
 * > 注：拉框放大命令将播放窗口选定框内的图像放大到整个播放窗口；拉框缩小命令将整个播放窗口的
 * > 图像缩小到播放窗口选定框内；**命令中的坐标系以播放窗口的左上角原点，各坐标取值以像素单位**。
 *
 * ⇒ 单位是**播放窗口像素**，不是千分比。所以 [of] 必须拿 `Length` / `Width` 当尺子做**比值**换算 ——
 * 与 `TargetArea.kt` 那组同名字段（元素名完全相同）是同一条规矩。
 *
 * ⛔ 比值换算**不需要知道播放窗口到底多大**：`MidPointX / Length` 与窗口尺寸无关。
 * 平台播放器是 `stretch: true`（拉伸铺满、不留黑边）⇒「窗口归一化坐标」＝「画面归一化坐标」，
 * 不存在 letterbox 补偿问题。
 */
data class DragZoomBox(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
) {
    val centerX: Float get() = left + width / 2f
    val centerY: Float get() = top + height / 2f

    /** 退化框（宽/高非正）**整条命令丢弃**，别拿它去裁一次画面。 */
    val hasArea: Boolean get() = width > 0f && height > 0f

    /** 与源画面求交。交完没有面积（框完全在画面外）⇒ null。 */
    fun clampedToSource(): DragZoomBox? {
        val l = max(0f, left)
        val t = max(0f, top)
        val r = min(1f, left + width)
        val b = min(1f, top + height)
        if (r - l <= 0f || b - t <= 0f) return null
        return DragZoomBox(l, t, r - l, b - t)
    }

    private fun max(a: Float, b: Float): Float = if (a > b) a else b
    private fun min(a: Float, b: Float): Float = if (a < b) a else b

    companion object {
        /**
         * 平台拉框报文 → 归一化框。
         *
         * 只做**比值**换算，所以不需要知道播放窗口尺寸：
         * `u = MidPointX / Length`、`v = MidPointY / Width`、`w = LengthX / Length`、`h = LengthY / Width`。
         * ⛔ 归一化**只在这里做一次**（同 `VideoMaskOverlay.of` 的规矩）：渲染端只做
         * 「[0,1] → 自己的绘制坐标系」，各做一份换算必然有一天不一致，而矩形**照样画得出来**。
         *
         * @return null = 报文字段不可用（缺参考帧 / 退化框 / 框完全在画面外）。
         */
        fun of(rect: DragZoomRect): DragZoomBox? {
            // ⛔ Length 是**播放窗口长度**（横向尺子）、Width 是**宽度**（纵向尺子）。
            //    两个名字都像"宽"，用反了不会有任何报错 —— 只会在非正方形窗口下横向错位。
            val refX = rect.frameLength
            val refY = rect.frameWidth
            if (refX <= 0 || refY <= 0) return null
            if (rect.lengthX <= 0 || rect.lengthY <= 0) return null
            val w = rect.lengthX.toFloat() / refX
            val h = rect.lengthY.toFloat() / refY
            val cx = rect.midX.toFloat() / refX
            val cy = rect.midY.toFloat() / refY
            return DragZoomBox(cx - w / 2f, cy - h / 2f, w, h).clampedToSource()
        }
    }
}

/**
 * 设备**当前的视窗**：源画面里「现在哪一块被铺满整个输出画面」。
 *
 * 归一化 0~1，原点画面左上角。[IDENTITY] = 没放大过（整幅都在）。
 *
 * ## 为什么是个"当前视窗"而不是"最近一次的框"
 * 标准把坐标系定义在**播放窗口**上，而播放窗口显示的是**当前**画面 ——
 * 所以第二次放大是"在已经放大的画面上接着裁"，不是"回到原始画面重新裁"。
 * 只存最近一次的矩形，用户连点两次「3D 放大」得到的会是同一个结果（而平台上是"又推近了一档"），
 * 框选的区域和实际放大的区域**系统性错开**，且画面上看不出是错的。
 */
data class VideoDragZoomViewport(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
) {
    val right: Float get() = left + width
    val bottom: Float get() = top + height

    /** 整幅都在 ⇒ 渲染端可以**完全不进裁剪分支**（与加本功能之前逐帧一致）。 */
    val isIdentity: Boolean
        get() = left == 0f && top == 0f && width == 1f && height == 1f

    /**
     * 拉框放大：把选定框内的图像放大到整个输出画面（A.2.3.1.8）。
     *
     * @return null = **拒绝**（会把视窗压到 [MIN_WINDOW] 以下）。调用方应留痕 ——
     *   标准里没有任何回读手段能发现"这次没执行"，设备侧不记就彻底不可观测。
     */
    fun zoomIn(box: DragZoomBox): VideoDragZoomViewport? {
        val w = width * box.width
        val h = height * box.height
        if (w < MIN_WINDOW || h < MIN_WINDOW) return null
        return VideoDragZoomViewport(left + box.left * width, top + box.top * height, w, h)
    }

    /**
     * 拉框缩小：把整个输出画面的图像缩小到选定框内（A.2.3.1.9）。
     *
     * 等价于"视窗反向放大"：新视窗 = 当前视窗 ÷ 框尺寸，且让**当前视窗**刚好落在框的位置上
     * （当前视窗的左上/右下分别对齐框的左上/右下）。中心对齐**不是**同一件事 ——
     * 框不在画面中心时会把画面挪走，用户看到的位置和他框的位置对不上。
     */
    fun zoomOut(box: DragZoomBox): VideoDragZoomViewport {
        val scaleX = width / box.width
        val scaleY = height / box.height
        // 新视窗尺寸；超过 1 就是"已经到最广了"，夹回整幅（不做黑边：铺满才与标准一致）。
        val w = scaleX.coerceAtMost(1f)
        val h = scaleY.coerceAtMost(1f)
        // 当前位置按**未夹取**的目标尺寸算（夹取发生的那个轴上位置本来也会被夹到 0）。
        val rawLeft = left - (box.centerX - box.width / 2f) * scaleX
        val rawTop = top - (box.centerY - box.height / 2f) * scaleY
        return VideoDragZoomViewport(
            left = rawLeft.coerceIn(0f, 1f - w),
            top = rawTop.coerceIn(0f, 1f - h),
            width = w,
            height = h,
        )
    }

    /** 与源画面求交，保证视窗始终是画面内的一块（渲染端不必再夹一次）。 */
    fun clampedToSource(): VideoDragZoomViewport {
        val l = left.coerceIn(0f, 1f)
        val t = top.coerceIn(0f, 1f)
        val w = width.coerceIn(0f, 1f - l)
        val h = height.coerceIn(0f, 1f - t)
        if (w <= 0f || h <= 0f) return IDENTITY
        return VideoDragZoomViewport(l, t, w, h)
    }

    companion object {
        /** 没放大过 —— 整幅铺满。渲染端的默认值，也是"平台没下发过"时的行为。 */
        val IDENTITY = VideoDragZoomViewport(0f, 0f, 1f, 1f)

        /**
         * 最小视窗 = 1/32 边长（约 32 倍数字变焦）。
         *
         * 再往下裁只是把同样的像素放得更大：平台看到的画面不会多出任何信息，
         * 却能让一次误操作把视野锁死在一个角上（标准里**没有复位命令**，
         * 放大过头只能靠反向拉框慢慢退回来）。
         */
        const val MIN_WINDOW = 1f / 32f
    }
}
