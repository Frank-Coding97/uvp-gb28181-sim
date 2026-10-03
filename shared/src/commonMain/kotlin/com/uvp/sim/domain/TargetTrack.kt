package com.uvp.sim.domain

import com.uvp.sim.gb28181.TargetArea

/**
 * 目标跟踪（GB/T 28181-2022 **9.3.1 d)** / 附录 A.2.3.1.14）在**设备侧**的当前状态。
 *
 * ## 为什么设备侧要单独存一份"跟踪态"
 *
 * 标准原文（9.3.1 d)）：
 * > 源设备向目标设备发送摄像机云台控制、远程启动、强制关键帧、拉框放大、拉框缩小、PTZ 精准控制、
 * > 存储卡格式化、**目标跟踪**命令后，**目标设备不发送应答命令**……
 *
 * 表 1 序号 13 的"对应应答命令章节"栏写的是 **（无）**。⇒ 平台**没有任何回执**可依赖，
 * 也**没有任何查询命令**能把"设备现在在跟踪什么"读回去（附录 A 的查询族里没有目标跟踪那一项）。
 *
 * 所以设备屏幕上"看得到自己在跟踪"这件事，是这条命令**唯一**的可见面 ——
 * 只写一条 `lastCommand` 的话，平台点下"手动跟踪"之后，两侧都没有任何东西能证明它到过设备：
 * 平台没有回执、设备屏幕纹丝不动，现象与"平台根本没发"完全一样。
 *
 * ⛔ 这份状态描述的是「**平台让设备跟踪什么**」，不是「设备看到了什么」——
 * 后者只有真机的 AI 能回答，模拟器答不了（见 [SIMULATED_AUTO_BOX]）。
 *
 * ## 与「拉框放大」的区别（别把两者合并）
 *
 * 两者的报文框参数**元素名一字不差相同**（`Length`/`Width`/`MidPointX`/`MidPointY`/
 * `LengthX`/`LengthY`，见 [com.uvp.sim.gb28181.TargetArea] 的注释），换算规则也一样是"除以报文中
 * 那两把尺子"。但语义完全不同：拉框是"把这个区域**放大**到整个窗口"（驱动的是
 * [DeviceControlModel.dragZoomViewport] 那个**真流视窗**），目标跟踪是"**盯住**这个区域里的目标"
 * （球机要不要转、转到哪，由设备自己的跟踪算法决定）。所以：
 *   - 跟踪框**不进** `dragZoomViewport`，绝不参与真流裁剪；
 *   - 两者的归一化各做各的一份（同 `VideoMaskOverlay.of` 的"归一化只做一次、且在自己那一层做"）。
 */
data class TargetTrackState(
    /** 跟踪模式。**没有 `Stop`** —— 停止的语义是"没有跟踪态"（[DeviceControlModel.targetTrack] 为 `null`）。 */
    val mode: TargetTrackMode,
    /**
     * 设备手上**可画**的跟踪框（归一化 0~1，原点 = 画面左上角，见 [TargetTrackBox]）。
     *
     * `null` = 设备手上没有能用来说话的框，三种成因**在设备侧不区分**：
     *   1. `Manual` 报文没带 `<TargetArea>`（标准里它是 `minOccurs=0`，合法）；
     *   2. 带了但六个子元素不全 / 非数字（半份坐标比没有更糟，见 [TargetArea.parse]）；
     *   3. 框算出来了但**整块在画面之外**（交不出面积）。
     *
     * ⛔ 这时**不能**退回一个自造的框（比如居中 1/4 宽）：那会让"平台没框选"在屏幕上看成
     * "平台框选了画面正中"，而排障方向会整个偏掉。UI 该说的是"手动跟踪 · 无框选区域"。
     */
    val box: TargetTrackBox?,
    /**
     * A.2.3.1.14 的 `<DeviceID2>` —— **全景相机中的全景通道 ID**（可选）。
     *
     * 注意与报文里 SN 之后那个**必选**的 `DeviceID` 不是同一件东西：那个是"全景相机中的球机通道"，
     * 这个是被跟踪目标所在的那路全景通道。平台没带就为 `null`（**不回落成设备自己的编码** ——
     * 那会把"平台没指定"谎报成"平台指定了本机"）。
     */
    val deviceId2: String?,
    /** 收到这条跟踪指令的时刻(ms)。UI 用它显示"已跟踪多久"。 */
    val startedAtMs: Long,
) {
    /** 自动跟踪 —— 目标由设备自己找，模拟器只能给一个**声明过的**假目标（见 [SIMULATED_AUTO_BOX]）。 */
    val isAuto: Boolean get() = mode == TargetTrackMode.Auto

    companion object {
        /**
         * `Auto` 模式下的"**模拟目标框**"：画面正中偏左上、约占 30%×40%。
         *
         * ⛔ **这是一个写死的假目标，不是设备 AI 的输出**。标准里 `Auto` 的语义是"设备自动
         * 搜索并跟踪目标"，而模拟器没有视觉算法 —— 但"设备屏幕上要能看到跟踪状态"这条
         * 演示需求是真的（backlog D-1）。所以取一个**确定性的**常量：
         *
         *   - **确定性**是硬要求。掷骰子的话，同一条命令两次演示画出来的框不一样，
         *     截图/回归测试都没法用；而且没人能从画面上判断"这是设备在变"还是"模拟器在掷骰子"
         *     （同 [VirtualStorageCards] 把"物理属性稳定、读数抖动"分开的那条理由）。
         *   - 取值刻意**不是正中对称**（不是 `0.5/0.5` 中心）：正中的框看起来像"准星"或
         *     "画面中心标记"，而真实跟踪框永远偏在目标实际所在的位置。
         */
        val SIMULATED_AUTO_BOX = TargetTrackBox(left = 0.34f, top = 0.28f, width = 0.30f, height = 0.40f)

        /**
         * 由一条 `TargetTrack` 指令造出设备侧跟踪态。
         *
         * @param mode 已过白名单的 `Auto` / `Manual`（`Stop` 由调用方翻译成"置 null"，不进本函数）
         * @param area 报文里的 `<TargetArea>`；`null` = 没带或不成立
         * @param deviceId2 报文里的 `<DeviceID2>`；空白按"没带"处理
         * @param atMs 收到指令的时刻
         */
        fun of(
            mode: TargetTrackMode,
            area: TargetArea?,
            deviceId2: String?,
            atMs: Long,
        ): TargetTrackState = TargetTrackState(
            mode = mode,
            box = when (mode) {
                // ⛔ 手动跟踪的框**只能**来自平台报文。算不出来就是没有（见 [box] 的三种成因），
                //    绝不 fallback 到 [SIMULATED_AUTO_BOX] —— 那会把"平台没框选"画成"框选成功"。
                TargetTrackMode.Manual -> area?.let { TargetTrackBox.of(it) }
                TargetTrackMode.Auto -> SIMULATED_AUTO_BOX
            },
            deviceId2 = deviceId2?.takeIf { it.isNotBlank() },
            startedAtMs = atMs,
        )
    }
}

/** 跟踪模式。取值域 = A.2.3.1.14 `TargetTrack` 元素的 `Auto` / `Manual`（`Stop` 不在状态里，见 [TargetTrackState]）。 */
enum class TargetTrackMode { Auto, Manual }

/**
 * 跟踪框在**画面归一化空间**里的位置（0~1，原点 = 画面左上角）。
 *
 * ## 坐标口径（标准原文，别照抄"归一化坐标"那种含糊说法）
 *
 * A.2.3.1.14 的 `TargetArea` 六个值全是**像素**，且标准正文专门交代了为什么要给两把尺子：
 * > 由于平台与设备画面比例大小不同，需要进行比例关系转化。因此，平台应提供画面大小：
 * > 播放窗口长度像素值和播放窗口宽度像素值。
 *
 * ⇒ 换算就是**比值**这一件事：`u = MidPointX / Length`、`v = MidPointY / Width`、
 * `w = LengthX / Length`、`h = LengthY / Width`。
 *
 * ⛔ `Length` 是"播放窗口**长度**"（横向尺子）、`Width` 是"播放窗口**宽度**"（纵向尺子）——
 * 两个名字都像"宽"，用反了不会有任何报错，只会在非正方形画面上横向错位（同 `DragZoomBox.of` 的坑）。
 *
 * ⛔ 比值换算**不需要知道播放窗口到底多大**：`MidPointX / Length` 与窗口尺寸无关。
 * 平台播放器是 `stretch: true`（拉伸铺满、不留黑边）⇒「窗口归一化坐标」＝「画面归一化坐标」。
 *
 * ⚠️ 本仓这个基准与**遮挡**（`PictureMask`）那条路**刻意不同**：遮挡用的是
 * "设备 OSD 声明的图像尺寸"，因为遮挡是**设备自己烧进画面**的；而这里平台明确要求按
 * **播放窗口**换算、由**平台**提供尺寸。两条命令的尺子由标准分别指定，不能互相借。
 */
data class TargetTrackBox(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
) {
    val centerX: Float get() = left + width / 2f
    val centerY: Float get() = top + height / 2f

    companion object {
        /**
         * 平台报文里的 `<TargetArea>`（像素）→ 归一化跟踪框。
         *
         * @return `null` = 这份报文画不出框：缺尺子（`Length`/`Width` 非正）、
         *   退化框（`LengthX`/`LengthY` 非正）、或框**整块**落在画面之外（求交后没有面积）。
         *   ⛔ 返回 `null` **不代表**这条命令无效 —— 设备仍然记住了"平台要跟踪"，
         *   只是手上没有可画的框（见 [TargetTrackState.box]）。
         */
        fun of(area: TargetArea): TargetTrackBox? {
            // ⛔ Length = 播放窗口长度（横向尺子）、Width = 宽度（纵向尺子）。用反了不报错，只错位。
            val refX = area.length
            val refY = area.width
            if (refX <= 0 || refY <= 0) return null
            if (area.lengthX <= 0 || area.lengthY <= 0) return null
            val w = area.lengthX.toFloat() / refX
            val h = area.lengthY.toFloat() / refY
            val cx = area.midPointX.toFloat() / refX
            val cy = area.midPointY.toFloat() / refY
            return TargetTrackBox(cx - w / 2f, cy - h / 2f, w, h).clampedToFrame()
        }

        /**
         * 整幅画面 —— `Auto` 模式下"设备还没锁定目标"的占位。
         *
         * ⚠️ 与 [TargetTrackState.SIMULATED_AUTO_BOX] 不是一回事：那是**假目标**、这是**没有目标**。
         * 目前没有调用点（模拟器不模拟"搜索中"这个中间态），留着是为了让"要不要表达搜索态"
         * 这个决定有一个明确的类型可用，而不是到时候随手编一个框。
         */
        val WHOLE_FRAME = TargetTrackBox(0f, 0f, 1f, 1f)
    }

    /**
     * 与画面求交，保证框始终落在画面内（渲染端不必再夹一次）。
     *
     * ⚠️ 夹取会**同时改变框的中心**（与 `DragZoomBox.clampedToSource` 同一条规矩）：
     * 平台给的框有一半在画面外时，屏幕上看到的是一个贴边的、更小的框，而**不是**
     * 一个中心正确但画到画面外的框。两害相权：画到画面外的那部分根本不存在，
     * 与其留一个"看起来框在那边"的假象，不如如实缩小。
     *
     * @return `null` = 求交后没有面积（框整块在画面之外）。
     */
    fun clampedToFrame(): TargetTrackBox? {
        val l = coerce(left, 0f, 1f)
        val t = coerce(top, 0f, 1f)
        val r = coerce(left + width, 0f, 1f)
        val b = coerce(top + height, 0f, 1f)
        if (r - l <= 0f || b - t <= 0f) return null
        return TargetTrackBox(l, t, r - l, b - t)
    }

    private fun coerce(v: Float, lo: Float, hi: Float): Float =
        if (v < lo) lo else if (v > hi) hi else v
}
