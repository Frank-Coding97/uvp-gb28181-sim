package com.uvp.sim.ui.model

import com.uvp.sim.gb28181.FrontOsdState
import com.uvp.sim.gb28181.RecordSchedule

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
    /**
     * 录像计划（A.2.1.15 `VideoRecordPlan`）。
     *
     * ⛔ 与本 DTO 其它字段的口径**刻意不同**：这里回退到生效值（含出厂默认），
     * 而不是"只映射配过的部分"。理由：这条数据的消费者是**配置编辑器**，页面必须
     * 显示"现在生效的是什么"；若只在配过时才有值，页面打开就是一片空白，
     * 用户没法在出厂默认的基础上改。是否有配置过由 [VideoRecordPlanViewDto.configured] 单独表达。
     */
    val videoRecordPlan: VideoRecordPlanViewDto = VideoRecordPlanViewDto(),
    /**
     * 报警录像（A.2.1.16 `VideoAlarmRecord`）。
     *
     * 与 [videoRecordPlan] 同一口径：**回退到生效值**（含出厂默认），因为它同样是
     * 配置编辑器的数据源，页面打开时必须能看到"现在生效的是什么"。
     * "配过没有"由 [VideoAlarmRecordViewDto.configured] 单独表达。
     */
    val videoAlarmRecord: VideoAlarmRecordViewDto = VideoAlarmRecordViewDto(),
    /**
     * 报警上报开关（A.2.1.18 `AlarmReport`）。
     *
     * 与上面两条同一口径：**回退到生效值**，页面必须回答"现在到底发不发"。
     * 出厂默认是移动侦测开 / 区域入侵关 —— 与设备真实能力一致，不是随手填的。
     */
    val alarmReport: AlarmReportViewDto = AlarmReportViewDto(),
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

/**
 * 录像计划（GB/T 28181-2022 A.2.1.15 `VideoRecordPlan`）的视图态。
 *
 * 字段与协议一一对应，**不做业务解释**：
 *  - [schedules] 里没有的那天 = 那天没有计划（标准「如当天无录像计划可缺少」）；
 *  - 每个 [RecordSchedule] 最多 8 段（标准 `TimeSegment maxOccurs="8"`）；
 *  - `Stop < Start` 是否算跨零点**不由设备侧判定**（见 `RecordTimeSegment` 注释）。
 *
 * ⛔ 不带"来源"字段（平台下发 / 本机编辑）—— 与 `DeviceConfigState` 同一条口径：
 * 设备只关心"我现在的配置是什么"，谁写的、什么时候写的它不关心，界面上也不该
 * 假装分得清（本仓分不清这两件事是刻意的，见 `DeviceConfigState` 类注释）。
 */
data class VideoRecordPlanViewDto(
    /** 平台或本机配过没有。`false` ⇒ 以下全是出厂默认（不启用 / 无计划 / 主码流）。 */
    val configured: Boolean = false,
    /** `RecordEnable` 0/1 → Boolean（界面是 Switch）。 */
    val enabled: Boolean = false,
    /** `StreamNumber`：0-主码流，1-子码流1… */
    val streamNumber: Int = 0,
    /** 按 `WeekDayNum` 升序的计划。 */
    val schedules: List<RecordSchedule> = emptyList(),
) {
    /** 有计划的周几数量（= 回读报文里的 `RecordScheduleSumNum`）。 */
    val dayCount: Int get() = schedules.size

    /** 时段总数，界面角标用（协议里没有这个总计数，按天各有一个）。 */
    val segmentCount: Int get() = schedules.sumOf { it.segments.size }
}

/**
 * 报警录像（GB/T 28181-2022 A.2.1.16 `VideoAlarmRecord`）的视图态。
 *
 * 字段与协议一一对应，**不做业务解释**：
 *  - [recordTimeSeconds] / [preRecordTimeSeconds] 的 `null` 表示**报文里没有这个元素**
 *    （与"配了 0 秒"是两件事，见 `VideoAlarmRecordState`）；
 *  - [streamNumber] 0-主码流 / 1-子码流1 …
 *
 * ⚠️ 与录像计划一样，本仓的这份配置**真的会驱动行为**（报警触发录像），不是纯记账 ——
 * 但 `PreRecordTime` 是例外：手机端没有环形缓存，只回读、不预录。
 */
data class VideoAlarmRecordViewDto(
    /** 平台或本机配过没有。`false` ⇒ 以下全是出厂默认（不启用 / 主码流 / 两个时间字段缺席）。 */
    val configured: Boolean = false,
    /** `RecordEnable` 0/1 → Boolean。 */
    val enabled: Boolean = false,
    /** `RecordTime`（秒）：报警后录多久。`null` = 没配过。 */
    val recordTimeSeconds: Int? = null,
    /** `PreRecordTime`（秒）：报警前预录多久。`null` = 没配过。 */
    val preRecordTimeSeconds: Int? = null,
    /** `StreamNumber`：0-主码流，1-子码流1… */
    val streamNumber: Int = 0,
)

/**
 * 报警上报开关（GB-2022 A.2.1.18 `AlarmReport`）的视图态。
 *
 * ⭐ 与录像计划/报警录像同一口径：**给的是生效值**（含出厂默认），因为页面要显示
 * "现在到底发不发"；"配过没有"由 [configured] 单独表达。
 *
 * ⛔ 这两个值不只是展示 —— 它们是上报门控的真源：为 `false` 时对应类别的报警
 * **一条报文都不会发出去**（拦在 `ManscdpRouterImpl.reportAlarm`）。
 */
data class AlarmReportViewDto(
    /** 平台或本机配过没有。`false` ⇒ 以下为出厂默认（移动侦测开、区域入侵关）。 */
    val configured: Boolean = false,
    /** `MotionDetection`：移动侦测事件是否上报。 */
    val motionDetection: Boolean = true,
    /** `FieldDetection`：区域入侵事件是否上报。 */
    val fieldDetection: Boolean = false,
)
