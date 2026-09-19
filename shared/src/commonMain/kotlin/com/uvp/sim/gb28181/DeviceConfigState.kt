package com.uvp.sim.gb28181

import com.uvp.sim.config.SimConfig
import kotlinx.serialization.Serializable

/**
 * **设备配置族（GB/T 28181-2022 A.2.3.2）平台写入值的汇总** —— 设备侧对整族配置的记账。
 *
 * ## 为什么收在一个聚合里，而不是给 Model 加 8 个平铺字段
 *
 * 这一族的字段有**完全相同的生命周期与语义**：都是「平台下发过的那份配置」，
 * 都随进程重启保留、都不参与运行时状态机。平铺 8 个字段的代价是 8 处 `copy()`
 * 分散在 handler / 存档 / Mapper 三层，改一次口径要动十几处；
 * 收成聚合之后**只有一个字段进 Model**、一次 `copy()` 落盘、一处 Mapper。
 *
 * 协议层该有的"每个类型一个文件"仍然保留（`PictureMaskConfig` / `VideoRecordPlanConfig` /
 * …），聚合只负责**持有**，不掺业务判断。
 *
 * ## ⛔ `null` 的语义：**平台从未下发过**，不是"配置为空"
 *
 * 每个子状态为 `null` ⇒ 回读时用该类型的**出厂默认**（`defaultFor(config)` 或常量）。
 * 这条是为了让**回读永远有值** —— 报空会被平台判成 `type_absent`（"设备不支持该配置类型"），
 * 把"这台设备支持、只是没人配过"误报成"设备不支持"，正是 A-5 判据链里最忌讳的假阴性。
 * 唯一的例外是 [snapShot]：抓拍配置没有"出厂默认上传地址"可言，见 `SnapShotReport`。
 *
 * ⚠️ **没被配过的类型与"配过之后再没被改过"在设备侧无法区分**，这是刻意的：
 * 设备只关心"我现在的配置是什么"，不关心"这份配置是谁什么时候放上去的"。
 * UI 上想区分"平台已配 / 出厂默认"，看的就是这里的 `null`。
 *
 * ⚠️ **`VideoParamAttribute`（A.2.1.13）刻意不并进本聚合**：它早在 2026-09-18 就已落成
 * `DeviceControlModel.videoParams`（按 `StreamNumber` 索引的 map），且已进存档、已接 UI。
 * 搬家只会制造一次无收益的改动风险 —— 聚合与它并列存在，回读侧由
 * `ConfigDownloadResponse.build` 统一拼装。将来若这一族再扩类型，**新类型进聚合**。
 */
@Serializable
data class DeviceConfigState(
    /** A.2.1.19 `BasicParam`。 */
    val basicParam: BasicParamState? = null,
    /** A.2.1.17 `PictureMask`（画面遮挡）。 */
    val pictureMask: PictureMaskState? = null,
    /** A.2.1.12 `OSDConfig`（前端 OSD）。 */
    val frontOsd: FrontOsdState? = null,
    /** A.2.1.23 `FrameMirror`（画面翻转）。 */
    val frameMirror: FrameMirrorState? = null,
    /** A.2.1.18 `AlarmReport`（报警上报开关）。 */
    val alarmReport: AlarmReportState? = null,
    /** A.2.1.15 `VideoRecordPlan`（录像计划）。 */
    val videoRecordPlan: VideoRecordPlanState? = null,
    /** A.2.1.16 `VideoAlarmRecord`（报警录像）。 */
    val videoAlarmRecord: VideoAlarmRecordState? = null,
    /** A.2.1.24 `SnapShotConfig`（图像抓拍）。null = 平台从未下发过 → **回读不回这一块**。 */
    val snapShot: SnapShotState? = null,
) {

    /**
     * 平台**真的下发过**的类型集合 —— 回读日志与设备屏幕上的「平台已配 / 出厂默认」都读它。
     *
     * ⭐ 抽成一个集合而不是让调用方到处 `!= null`：设备屏幕上要显示的那行字、
     * 回读日志要打的那句"哪些是平台配的"，必须是**同一个判断**，否则两处会各自漂移
     * （本仓为"日志与实现各写一遍判断"踩过一次）。`VideoParamAttribute` 不在这里
     * （它有自己的 `videoParams`），由调用方单独判。
     */
    val configuredBlocks: Set<DeviceConfigBlock>
        get() = buildSet {
            if (basicParam != null) add(DeviceConfigBlock.BasicParam)
            if (pictureMask != null) add(DeviceConfigBlock.PictureMask)
            if (frontOsd != null) add(DeviceConfigBlock.OsdConfig)
            if (frameMirror != null) add(DeviceConfigBlock.FrameMirror)
            if (alarmReport != null) add(DeviceConfigBlock.AlarmReport)
            if (videoRecordPlan != null) add(DeviceConfigBlock.VideoRecordPlan)
            if (videoAlarmRecord != null) add(DeviceConfigBlock.VideoAlarmRecord)
            if (snapShot != null) add(DeviceConfigBlock.SnapShot)
        }

    // ---- 回读取值：平台配过的用配过的，没配过的退回出厂默认（"回读永远有值"） ----

    fun effectivePictureMask(config: SimConfig): PictureMaskState =
        pictureMask ?: PictureMaskConfig.defaultFor(config)

    fun effectiveFrontOsd(config: SimConfig): FrontOsdState =
        frontOsd ?: FrontOsdConfig.defaultFor(config)

    fun effectiveFrameMirror(): FrameMirrorState = frameMirror ?: FrameMirrorConfig.DEFAULT

    fun effectiveAlarmReport(): AlarmReportState = alarmReport ?: AlarmReportConfig.DEFAULT

    fun effectiveVideoRecordPlan(config: SimConfig): VideoRecordPlanState =
        videoRecordPlan ?: VideoRecordPlanConfig.defaultFor(config)

    fun effectiveVideoAlarmRecord(config: SimConfig): VideoAlarmRecordState =
        videoAlarmRecord ?: VideoAlarmRecordConfig.defaultFor(config)
}
