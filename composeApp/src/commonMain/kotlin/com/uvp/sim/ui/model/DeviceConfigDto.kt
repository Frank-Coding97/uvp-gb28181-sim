package com.uvp.sim.ui.model

import com.uvp.sim.gb28181.FrontOsdState

/**
 * 画布上的一块遮挡矩形（GB/T 28181-2022 A.2.1.17 `PictureMask` 的 `RegionList/Item/Point`）。
 *
 * 坐标**已归一化到 0~1**（除以视频帧的像素宽高），渲染层直接乘画布尺寸即可：
 * ```kotlin
 * val x = left * canvasWidthPx
 * val w = (right - left) * canvasWidthPx
 * ```
 *
 * ⛔ 千万别在 UI 层再从原始像素换算一次 —— 归一化只做一次，位置在 Mapper
 * （`DeviceConfigMapper`）。两处各换算一次，改一处就会静默错位。
 */
data class MaskRectDto(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

/** 画布上的一行「前端 OSD」文本（A.2.1.12 `OSDCfgType/Item`），坐标同样已归一化。 */
data class OsdTextLineDto(
    val text: String,
    val x: Float,
    val y: Float,
)

/**
 * 「设备配置族」的只读视图态（GB/T 28181-2022 A.2.3.2）。
 *
 * ⭐ **只承载"平台真的下发过"的那部分**，不回退出厂默认。理由：
 *   - 出厂默认的 OSD（时间戳 + 通道名）在画布上**已经由本机三层 OSD 画了一遍**，
 *     再叠一份就是同一行字重合显示两遍；
 *   - 遮挡的出厂默认是"无区域"，回退出来也是空的，没有意义。
 *   - 而"平台推送过什么"是**新增信息**，画出来不会与本机叠加重复。
 *   ⇒ 协议侧「回读永远有值」是**平台判据**的要求；画布上「只画平台推送的」是**展示**的选择。
 *   两者不矛盾，但也不能互相套用。
 */
data class DeviceConfigDto(
    /** 画面遮挡（仅平台下发过时非空）。 */
    val pictureMask: PictureMaskViewDto = PictureMaskViewDto(),
    /** 前端 OSD 文本行（仅平台下发过时非空）。 */
    val osdLines: List<OsdTextLineDto> = emptyList(),
    /**
     * 画面翻转 `FrameMirror` 的整数取值 0~3。**只有平台下发过才非 0**，
     * 渲染层直接拿它做镜像（0 = 不动，其余按位理解：1 水平 / 2 上下 / 3 中心）。
     */
    val frameMirror: Int = 0,
    /**
     * 平台下发过的**原始**「前端 OSD」（A.2.1.12）。`null` = 平台一次都没配过。
     *
     * ⭐ 这是设置页「GB/T 28181 前端 OSD」区块的**编辑对象**，也是回显判据 ——
     * 回显走 `frontOsd ?: FrontOsdConfig.defaultFor(config)`，两者别用反。
     *
     * ⛔ 不要拿上面已归一化的 [osdLines] 去回写协议字段：那是投影给画布用的视图态，
     * 坐标已经除过、且只保留平台配过的那部分 —— 从它反推不出一份可回读的 `OSDConfig`。
     */
    val frontOsd: FrontOsdState? = null,
    /** HUD「图像」页的只读摘要行。 */
    val rows: List<DeviceConfigRowDto> = emptyList(),
) {
    /** 有没有任何"平台下发的配置"要在画布上体现。渲染层用它决定整层是否挂载。 */
    val hasCanvasContent: Boolean
        get() = pictureMask.rects.isNotEmpty() || osdLines.isNotEmpty() || frameMirror != 0
}

/** 画面遮挡的视图态。 */
data class PictureMaskViewDto(
    val on: Boolean = false,
    val rects: List<MaskRectDto> = emptyList(),
)

/**
 * 摘要行。[fromPlatform] == true 表示这项是**平台下发过**的，false = 设备出厂默认。
 *
 * ⭐ 这一位是给操作员看的：面板上"这条值是平台配的"还是"设备本来就这样"，
 * 决定了他该去平台改还是该查设备。
 *
 * [alwaysVisible] 是**常驻位** —— 不论平台配没配过都占一个显示名额。
 *
 * ⛔ 别把它当成 `fromPlatform` 的别名。两者的区别是本族里**唯一一个"永远有值"的项**
 * （视频参数属性）造成的：
 *   - 其余 8 项在平台没下发时**没有值**，"未配置"是一个真实且正确的状态，
 *     所以它们的行在平台没配过时**不该出现**（显示了等于在编答案）；
 *   - `VideoParamAttribute` 的当前值是 `平台写入 ?: 出厂派生`，**永远存在** ——
 *     平台一次都没配过时，设备照样有一组生效的主/子码流参数。
 *     若让它跟着 `fromPlatform` 过滤，"设备现在主/子码流各是什么"这件事
 *     在设备屏幕上就**永远看不见**，而这恰恰是这一项存在的意义。
 */
data class DeviceConfigRowDto(
    val label: String,
    val value: String,
    val fromPlatform: Boolean,
    val alwaysVisible: Boolean = false,
)
