package com.uvp.sim.ui.model

import com.uvp.sim.gb28181.FrontOsdState

/**
 * 一块画面遮挡（GB/T 28181-2022 A.2.1.17 `PictureMask` 的 `RegionList/Item`）。
 *
 * ⭐ **保留协议原始像素**（不是归一化值）。
 * 2026-09-19 起遮挡不再画在 Compose 画布上（改为烧进真实视频流），而设备屏幕上要回答的
 * 问题变成了「平台把哪几块挡上了、坐标是多少」—— 那必须是**协议里的那几个数**。
 * 归一化过一次的值既不是平台发的数、又不是画布坐标，是最没用的一种中间态。
 * （画布要归一化的话在渲染端自己除 —— 但现在没有画布消费者了。）
 */
data class MaskRectDto(
    /** `Seq`，区域编号 1~4。 */
    val seq: Int,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    /** 人读串：`左x,左y → 右x,右y`。 */
    val displayLabel: String get() = "$left,$top → $right,$bottom"
}

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
 *   - 出厂默认的 OSD（时间戳 + 通道名）**已经由本机三层 OSD 烧进视频流**了，
 *     画布上再叠一份就是同一行字重合显示两遍；
 *   - 遮挡的出厂默认是"无区域"，回退出来也是空的，没有意义；
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
     * 画面翻转 `FrameMirror` 的整数取值 0~3；**`null` = 平台一次都没配过**。
     *
     * ⛔ 保留 `null` 而不是兜成 0：`null`（平台没配过）与 `0`（平台配了"不启用"）
     * 在画面上确实一样，但设备屏幕上该说不同的话 —— 「平台没配过」和「平台把它关掉了」
     * 对操作员是两条信息（前者不用管，后者要去平台确认是不是有意为之）。
     * 这与 [PictureMaskViewDto.configured] 是同一条规矩。
     *
     * 渲染层不直接消费这个整数：码值 → 方向的换算走 `FrameMirrorTransform.of()`
     * （唯一映射，Android GL 与 iOS CoreImage 共用），这里只负责把"配过没有"带出来。
     */
    val frameMirror: Int? = null,
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
) {
    /**
     * 有没有"要画在画布上的画面内容"。渲染层用它决定整层是否挂载。
     *
     * ⛔ **2026-09-19 起不再包含遮挡**（详见 [PictureMaskViewDto]）。
     * 也**不含 `frameMirror`** —— 镜像是**真流**的变换（`com.uvp.sim.osd.FrameMirrorTransform`
     * 走采集链路，2026-09-20 补齐），**画布一侧明确不参与**，本叠层更不该有它。
     */
    val hasCanvasContent: Boolean
        get() = osdLines.isNotEmpty()
}

/**
 * 画面遮挡的视图态（GB-2022 A.2.1.17）。
 *
 * ⛔ **2026-09-19 起不再画在画布上**：遮挡改为**烧进真实视频流**
 * （Android = `OsdRenderer` 的 GL FBO 段 `MaskPass`；iOS = `IosFrameProcessor` 的 CoreImage 组合）。
 * 理由：模拟器推给平台的是**手机真实摄像头**画面，而「模拟中心」那块 3D 画布是**另一条链路** ——
 * 黑块画在画布上，平台点播到的画面一点遮挡都没有，等于"遮挡做在了唯一不上传的那条链路上"。
 *
 * 本类型现在的用途是 **HUD「图像」页的遮挡区域列表**：设备屏幕上要能回答
 * 「平台把哪几块挡上了、坐标是多少、现在是开还是关」。
 */
data class PictureMaskViewDto(
    val on: Boolean = false,
    val rects: List<MaskRectDto> = emptyList(),
    /**
     * 平台**到底配过没有**。⛔ 与 [on] 是两个独立的事实，别合并：
     * 「平台从没配过遮挡」与「平台配过、现在把遮挡关了」在设备屏幕上该说不同的话
     * （前者＝设备出厂就是这样，后者＝有人刚把它关了），而两者的 [on] 都是 false。
     * 这与本仓「没查过 / 查了但为空」必须分两态是同一条规矩。
     */
    val configured: Boolean = false,
)
