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
 * GB/T 28181-2022 §F.3 DeviceControl 命令分发器(router).
 *
 * **PR-E3 后**:本类退化为按命令类别路由,所有真正的命令逻辑放在
 * [com.uvp.sim.domain.devicecontrol] 子包的 4 个 handler:
 *
 *  - [PtzHandler]    — PTZCmd motion / cruise + PTZPreciseCtrl + DragZoom
 *  - [PresetHandler] — preset CRUD + HomePosition
 *  - [AuxHandler]    — Aux 雨刷 / 红外灯 / 加热等
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
     * 主入口.根据 XML 中第一个匹配的子命令标签分发.
     * 一个 DeviceControl 体里通常只含一个子命令,这里按已知顺序逐个尝试,
     * 命中即停;避免一次处理多个语义冲突的命令.
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
            xml.contains("<BasicParam>") -> {
                systemHandler.handleDeviceConfig(xml); DeviceControlAck()
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
            xml.contains("<SnapShotConfig>") -> {
                systemHandler.handleSnapShotConfig(xml); DeviceControlAck()
            }
            ManscdpParser.tagValue(xml, "SnapShotCmd") != null -> {
                systemHandler.handleSnapshot(xml); DeviceControlAck()
            }
            else -> DeviceControlAck(needSipResponse = false)
        }
    }
}
