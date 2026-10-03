package com.uvp.sim.domain

import com.uvp.sim.config.SimConfig
import com.uvp.sim.domain.devicecontrol.AuxHandler
import com.uvp.sim.domain.devicecontrol.DefaultAuxHandler
import com.uvp.sim.domain.devicecontrol.DefaultPresetHandler
import com.uvp.sim.domain.devicecontrol.DefaultPtzHandler
import com.uvp.sim.domain.devicecontrol.DefaultSystemHandler
import com.uvp.sim.domain.devicecontrol.PresetHandler
import com.uvp.sim.domain.devicecontrol.PtzHandler
import com.uvp.sim.domain.devicecontrol.SystemHandler
import com.uvp.sim.gb28181.ManscdpParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 引擎层副作用接口,Dispatcher 通过它向上层(SimulatorEngine)请求执行
 * commonMain 无法独立完成的动作:重启注册、抓拍上报、强制 IDR 等.
 *
 * 测试可注入 fake 实现,无需真实 engine / camera.
 */
interface DeviceControlActions {
    /** TeleBoot — 重启设备(Engine 端会 unregister + delay + register). */
    suspend fun reboot()
    /** SnapShotCmd — 触发抓拍 + 上报(走已有 reportSnapshot 流程,7.4 旧路径). */
    suspend fun snapshot()
    /** IFameCmd — 强制下一帧出 IDR. */
    fun requestKeyFrame()
    /** SnapShotConfig — GB-2022 §9.5 平台下发的图像抓拍配置(7.5 新路径,委托 SnapshotUploadEngine). */
    suspend fun triggerSnapshotConfig(cfg: com.uvp.sim.gb28181.SnapShotConfig)
    /** GB-2022 §9.13 DeviceUpgrade 在线升级 — 启动假进度协程,5s 内推 4 条 NOTIFY (0/30/60/100). */
    fun startUpgrade(sessionId: String, firmware: String, fileUrl: String)
}

/**
 * Dispatcher 处理 DeviceControl 后返回给 SimulatorEngine 的应答指引。
 *
 * - [needSipResponse] 是否需要回 SIP 200 OK(DeviceControl 一律 true,即使解析失败,
 *   避免平台重试)
 * - [alarmReset] 本次命令是否触发了报警复位(AlarmCmd 0/2/ResetAlarm)
 * - [by] 复位来源(平台 fromUri),供 emit AlarmReset(Remote) 用
 */
data class DeviceControlAck(
    val needSipResponse: Boolean = true,
    val alarmReset: Boolean = false,
    val by: String? = null
)

/**
 * GB/T 28181-2022 附录 A.3 DeviceControl 命令分发器(router).
 *
 * **PR-E3 后**:本类退化为按命令类别路由,所有真正的命令逻辑放在
 * [com.uvp.sim.domain.devicecontrol] 子包的 4 个 handler:
 *
 *  - [PtzHandler]    — PTZCmd motion / cruise + PTZPreciseCtrl + DragZoom
 *  - [PresetHandler] — preset CRUD + HomePosition
 *  - [AuxHandler]    — Aux 辅助开关(标准只定义编号 1 = 雨刷)
 *  - [SystemHandler] — Reboot / IFrame / Record / Guard / Alarm / DeviceConfig /
 *                      DeviceUpgrade / FormatSDCard / TargetTrack / SnapShot
 *
 * 公开 API(构造参数 + [dispatch] / [MAX_PRESET_INDEX])与重构前**完全一致**,
 * Wave 2 的 ManscdpRouterImpl 不需要任何改动。
 *
 * 输入:平台 SIP MESSAGE 中的 MANSCDP XML 体.
 * 输出:更新 [DeviceControlModel],并通过 [DeviceControlActions] 触发副作用.
 */
class DeviceControlDispatcher(
    private val state: MutableStateFlow<DeviceControlModel>,
    private val config: SimConfig,
    private val actions: DeviceControlActions,
    private val scope: CoroutineScope? = null,
) {

    companion object {
        /** 预置位上限(spec Q3:行业惯例 1-8;越界一律 200 OK 但忽略业务). */
        const val MAX_PRESET_INDEX = 8
    }

    // 4 个 handler 持有相同的 state(commonMain 单线程心智模型),独立处理各自命令类别。
    private val presetHandler: PresetHandler =
        DefaultPresetHandler(state, maxPresetIndex = MAX_PRESET_INDEX)
    private val auxHandler: AuxHandler = DefaultAuxHandler(state)
    private val ptzHandler: PtzHandler = DefaultPtzHandler(state)
    private val systemHandler: SystemHandler = DefaultSystemHandler(state, actions, scope)

    /**
     * 主入口.按 XML 里出现的子命令标签分发.
     *
     * ⭐⭐ **两类报文的分派规则不同**（2026-09-20 修正，真机踩过）：
     *  - **A.2.3.1 控制类**（`CmdType=DeviceControl`）：一条报文语义上就是一个命令，
     *    所以下面这条 `when` 链**命中即停**（避免一次执行多个语义冲突的动作）。
     *  - **A.2.3.2 设备配置族**（`CmdType=DeviceConfig`）：平台把**这一次要改的全部类型**
     *    合并在同一条 `<Control>` 里 ⇒ 必须**逐个块都处理**，走兜底分支
     *    [handleDeviceConfigBlocks]。⛔ 绝不能让排在前面的块把后面的块吃掉 ——
     *    那正是 2026-09-20「下发 FrameMirror 报 `值未生效(实际 0)`」的根因。
     *
     * 返回 [DeviceControlAck] 让 engine 决定是否回 200 OK / 是否联动报警复位。
     * [fromUri] 是平台 MESSAGE 的 From URI,仅 AlarmCmd 复位时透传给 ack.by。
     */
    fun dispatch(xml: String, fromUri: String? = null): DeviceControlAck {
        if (xml.isBlank()) return DeviceControlAck(needSipResponse = false)

        return when {
            ManscdpParser.tagValue(xml, "PTZCmd") != null -> {
                ptzHandler.handlePtz(xml, presetHandler, auxHandler); DeviceControlAck()
            }
            // GB-2022 §9.3.4 A.2.3.1.11 精确云台 — 优先于其他控制命令匹配
            xml.contains("<PTZPreciseCtrl>") -> {
                ptzHandler.handlePtzPrecise(xml); DeviceControlAck()
            }
            ManscdpParser.tagValue(xml, "IFrameCmd") != null ||
                ManscdpParser.tagValue(xml, "IFameCmd") != null -> {
                systemHandler.handleIFrame(xml); DeviceControlAck()
            }
            ManscdpParser.tagValue(xml, "TeleBoot") != null -> {
                systemHandler.handleTeleBoot(xml); DeviceControlAck()
            }
            ManscdpParser.tagValue(xml, "RecordCmd") != null -> {
                systemHandler.handleRecord(xml); DeviceControlAck()
            }
            ManscdpParser.tagValue(xml, "GuardCmd") != null -> {
                systemHandler.handleGuard(xml); DeviceControlAck()
            }
            ManscdpParser.tagValue(xml, "AlarmCmd") != null ->
                systemHandler.handleAlarm(xml, fromUri)
            xml.contains("<DragZoomIn>") || xml.contains("<DragZoomOut>") -> {
                ptzHandler.handleDragZoom(xml); DeviceControlAck()
            }
            xml.contains("<HomePosition>") -> {
                presetHandler.handleHomePosition(xml); DeviceControlAck()
            }
            // GB-2022 §9.3.4 新增项 — 200 OK + UI snackbar 提示,不真做业务
            xml.contains("<DeviceUpgrade>") -> {
                systemHandler.handleDeviceUpgrade(xml); DeviceControlAck()
            }
            xml.contains("<FormatSDCard>") -> {
                systemHandler.handleFormatSDCard(xml); DeviceControlAck()
            }
            xml.contains("<TargetTrack>") -> {
                systemHandler.handleTargetTrack(xml); DeviceControlAck()
            }
            // GB-2022 §9.5 图像抓拍 — 7.5 新路径,优先于 7.4 旧 SnapShotCmd 匹配
            hasBlock(xml, "SnapShotConfig") -> {
                systemHandler.handleSnapShotConfig(xml); DeviceControlAck()
            }
            ManscdpParser.tagValue(xml, "SnapShotCmd") != null -> {
                systemHandler.handleSnapshot(xml); DeviceControlAck()
            }
            // ⭐⭐ A.2.3.2 设备配置族走**兜底分支**（不再各自占一个 `when` 分支）——
            //    见 [handleDeviceConfigBlocks]。必须放在最后：上面那些分支都是
            //    `DeviceControl` 的单命令语义，命中即停是对的；配置族则相反。
            else -> DeviceControlAck(needSipResponse = handleDeviceConfigBlocks(xml))
        }
    }

    /**
     * A.2.3.2 设备配置族（`CmdType=DeviceConfig`）——**一条报文里可以并存多个块**，
     * 每一块都必须处理。
     *
     * ⛔⛔ **别把这里拆回各自的 `when` 分支**：平台的 apply 会把"这一次要改的全部类型"
     *    合并在同一条 `<Control>` 里下发。实测 payload
     *    `{"blocks":{"frameMirror":{"value":1},"pictureMask":{"on":0,"regions":[]}}}`
     *    ⇒ 报文是 `<PictureMask>…</PictureMask><FrameMirror>1</FrameMirror>` **并存**。
     *    原先它们各占一个 `when` 分支、而那条链**命中即停** ⇒
     *    **排在前面的块把后面的块整个吃掉**。
     *
     * 2026-09-20 真机定位（App 日志 + 设备存档双证）：
     * ```
     *   只发 FrameMirror          ⇒ 生效（存档 "frameMirror":{"value":3}）
     *   与 PictureMask 一起发      ⇒ App 日志只有一行「平台下发 DeviceConfig PictureMask → 已记 …」，
     *                              FrameMirror 连 handler 都没进，存档纹丝不动，
     *                              平台回读恒 `FrameMirror.value=1(实际 0)`
     * ```
     * ⚠️ 这类错**两侧都不报错**：设备回了 200 + `Result=OK`（因为至少认出了 PictureMask），
     * 平台只能靠回读对账才发现不一致 —— 现象上很像"设备不认这条配置"，实际是**分派吃掉了它**。
     *
     * ⛔ 匹配一律用 [hasBlock]（裸标签 / 带属性 / 自闭三种形态都认），**不要退回
     *    `contains("<X>")`** —— `VideoParamAttribute` 带 `Num` 那次已经踩过
     *    "永久失配且不报错"。
     *
     * ⛔ 这里**一个版本判断都不加**（与改造前一致）：被误登记成 2016 的真 2022 设备必须
     *    还有一次"试一下"的机会，版本门禁只关设备主动声明那一半，见
     *    `ConfigDownloadResponse` 的类注释。
     *
     * @return 是否**至少处理了一块** —— 决定应答 `Result` 是 `OK` 还是 `ERROR`
     *         （一块都不认识时回 ERROR，让平台显式失败，好过等一个永远 type_absent 的回读）。
     */
    private fun handleDeviceConfigBlocks(xml: String): Boolean {
        var handled = false

        // A.2.3.2 BasicParam（2016 就有）。
        if (xml.contains("<BasicParam>")) {
            systemHandler.handleDeviceConfig(xml); handled = true
        }
        // A.2.1.13 VideoParamAttribute（2022 新增）——元素**带 `Num` 属性**，
        // 所以这里刻意不带收尾 `>`（见 hasBlock 的注释）。
        if (xml.contains("<VideoParamAttribute")) {
            systemHandler.handleVideoParamAttribute(xml); handled = true
        }
        // 其余 2022 新增配置块（6 个）。
        if (hasBlock(xml, "PictureMask")) {
            systemHandler.handlePictureMask(xml); handled = true
        }
        if (hasBlock(xml, "OSDConfig")) {
            systemHandler.handleOsdConfig(xml); handled = true
        }
        if (hasBlock(xml, "FrameMirror")) {
            systemHandler.handleFrameMirror(xml); handled = true
        }
        if (hasBlock(xml, "AlarmReport")) {
            systemHandler.handleAlarmReport(xml); handled = true
        }
        if (hasBlock(xml, "VideoRecordPlan")) {
            systemHandler.handleVideoRecordPlan(xml); handled = true
        }
        if (hasBlock(xml, "VideoAlarmRecord")) {
            systemHandler.handleVideoAlarmRecord(xml); handled = true
        }

        return handled
    }

    /**
     * 块名匹配：同时接受**裸标签**（`<PictureMask>`）、**带属性**（`<PictureMask Num="1">`）
     * 与**自闭**（`<PictureMask/>`、`<PictureMask />`）三种形态。
     *
     * ⛔ 写成 `contains("<X>")` 只在"元素永远不带属性"时才安全 —— 而标准改版、或对端实现
     * 多写一个属性，就会**永久失配且不报错**。本仓为这一形态踩过一次
     * （`VideoParamAttribute` 带 `Num`，`contains("<VideoParamAttribute>")` 永不命中）。
     * 这里三种都认：`<X>` 精确匹配、`<X ` 匹配带属性的形态（含 `<X />`），
     * `<X/>` 匹配不自闭前导空格的形态，且都不会误命中 `<XFoo`。
     *
     * ⭐ 自闭形态**必须路由进去**（而不是当作"没发"忽略）：各类型的 `parse()` 会把它判成
     * `ConfigParse.Rejected` 并留一条 warn。若不路由，`<PictureMask/>` 这种
     * "元素在、内容不成立"的报文就会被静默丢掉 —— 现场只能看到"配了没生效"。
     */
    private fun hasBlock(xml: String, name: String): Boolean =
        xml.contains("<$name>") || xml.contains("<$name ") || xml.contains("<$name/>")
}
