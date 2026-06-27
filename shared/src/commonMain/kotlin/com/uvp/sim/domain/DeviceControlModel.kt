package com.uvp.sim.domain

import com.uvp.sim.gb28181.PtzCommand

/**
 * Wave 3 PR-DC-DECOUPLE(2026-06-26):「业务 Model」+ 「UI RenderState」两层架构。
 *
 * **DeviceControlModel** 承载所有「业务 / 协议层」运行状态:
 *  - PTZ 姿态(panAngle/tiltAngle/zoomLevel) — preset SET 读、PreciseCtrl Query 回包用
 *  - PTZ 速率 + iris/focus 累计量 — Handler 写,UI 每帧消费
 *  - 状态灯(isRecording/isGuarded/isAlarming/isRebooting) — Query 回包用
 *  - preset CRUD / homePosition / cruiseTracks / auxStates / lastPreciseCtrl / upgradeProgress —
 *    各 §9.5.3 Query 回包用
 *  - lastCommand / pendingEffect — Handler 写,Coord 读出转 SimEvent / UI 消费一次性效果
 *
 * **DeviceControlRenderState**(独立文件)承载「纯渲染派生」字段(rawHex 文本 / 时间戳显示 / icon 提示等),
 * 由 [deriveRenderState] 从 Model 纯函数推导,**不独立 hold 状态**。
 *
 * 拆分原则(测试驱动反推):凡是 Handler / Dispatcher / Coord / 业务单测**写或读**的字段进 Model;
 * 仅 UI Mapper / Compose 视图**消费**且可由 Model 推导的字段进 RenderState。
 *
 * Handler / Dispatcher / Coord 全部用 `MutableStateFlow<DeviceControlModel>`;
 * UI 层(AppEngine.deviceControlState / SimulatorEngine.deviceControlState)直接暴露
 * [DeviceControlModel] StateFlow,UI Mapper 在边界处调 [deriveRenderState] 派生
 * 渲染层语义字段(含 [DeviceCommandCategory]),协议字符串解析压在派生函数里,不漏到 UI。
 */
data class DeviceControlModel(
    // PTZ 当前姿态(累积量,UI 层维护)
    val panAngle: Float = 0f,
    val tiltAngle: Float = 0f,
    val zoomLevel: Float = 1f,
    val irisLevel: Float = 0.5f,
    val focusLevel: Float = 0.5f,

    // PTZ 实时速率(由 PTZCmd 写入,UI 层每帧消费)
    val panSpeed: Float = 0f,
    val tiltSpeed: Float = 0f,
    val zoomSpeed: Float = 0f,

    // 状态灯
    val isRecording: Boolean = false,
    val isGuarded: Boolean = false,
    val isAlarming: Boolean = false,
    val isRebooting: Boolean = false,

    // DragZoom 区域(平台拉框聚焦)
    val dragZoomRect: DragZoomRect? = null,

    // 预置位 (HomePosition)
    val presets: Map<Int, PtzPose> = emptyMap(),
    val currentPresetIndex: Int? = null,

    // GB-2022 看守位 — 设备唯一一个"无人时回到的默认位置",跟预置位是不同概念
    val homePosition: PtzPose? = null,
    val homePositionEnabled: Boolean = true,

    // GB-2022 §9.5.3 巡航轨迹 — 一组预置位序列 + 停留时长 + 速度
    // key = 轨迹号 1-N,value = 该轨迹的有序预置位编号列表
    val cruiseTracks: Map<Int, List<Int>> = emptyMap(),
    /** 当前正在执行的巡航轨迹号(null 表示未巡航) */
    val activeCruiseTrack: Int? = null,

    // 辅助控制状态(GB-2022 §F.3 byte3=0x89/0x8A Aux On/Off)
    // key = AuxFunction.index(1=雨刷 / 2=红外灯 / 3=加热 / 4=除雾 / 5=制冷)
    // value = true=ON / false=OFF
    val auxStates: Map<Int, Boolean> = emptyMap(),
    /** 辅助开关最近一次状态变更的时间戳(ms),供 UI 显示运行时长. */
    val auxTimestamps: Map<Int, Long> = emptyMap(),

    // 最近一次平台控制命令(Coord 读它转 SimEvent / UI HUD 显示)
    val lastCommand: LastDeviceCommand? = null,

    // GB-2022 §9.3.4 PTZPreciseCtrl 收到的最近一次精确控制指令,
    // 供 §9.5.3 PTZ 精准状态查询(A.2.4.13)回包用。null 表示从未收过。
    val lastPreciseCtrl: PtzPose? = null,

    // GB-2022 §9.13 设备升级进度(in-progress/success/failure + percent),
    // null 表示当前没有升级任务。
    val upgradeProgress: UpgradeProgress? = null,

    // 一次性效果触发器,UI 消费后置 null
    val pendingEffect: DeviceEffect? = null,
)

/**
 * 平台控制命令的语义分类(GB-2022 §9.3.4 / §F.3 业务大类)。
 *
 * UI 用它派 Tab,不再 parse [LastDeviceCommand.rawHex] 字符串(轨 ④ PR-UI-PROTOCOL-FIX)。
 * 派生逻辑集中在 [deriveCommandCategory],输入 [LastDeviceCommand.type]/[LastDeviceCommand.rawHex],
 * 输出语义枚举;协议字符串泄露被压缩到这一个函数,不再散落到 Compose 视图里。
 */
enum class DeviceCommandCategory {
    /** PTZ 运动 / 预置位 / 巡航 / 看守位 / 精确定位 / 三维拉框 */
    Ptz,
    /** 录像 / 布防 / 报警 / 远程重启 */
    Status,
    /** 强制 I 帧 / 抓拍 / 拉框聚焦 / 设备配置 / 在线升级 / 格式化 SD / 目标跟踪 */
    Image,
    /** 辅助开关(GB-2022 §F.3 byte3=0x89/0x8A):雨刷 / 红外灯 / 加热 / 除雾 / 制冷 */
    Aux,
}

/**
 * UI 渲染派生层 — 仅含「显示用文本 / 时间戳 / 一次性 effect 标志」,由 [deriveRenderState] 从
 * [DeviceControlModel] 纯函数推导(single source of truth = Model)。
 *
 * 设计:UI Mapper / Compose 视图只读 RenderState 里的字段,不再读 Model 内部细节如 lastCommand?.rawHex,
 * 这样 Model 字段调整不破 UI 契约;同时 RenderState 不持有独立可变状态,Model 变 → RenderState 自动跟。
 */
data class DeviceControlRenderState(
    /** 最近一次设备控制命令的类型(显示用),null = 从未收过. */
    val lastCommandType: String? = null,
    /** 最近一次设备控制命令的原文 hex / 简述(显示用). */
    val lastCommandHex: String? = null,
    /** 最近一次设备控制命令接收时间戳(ms,显示运行时长 / 高亮 fade). */
    val lastRecvAtMs: Long? = null,
    /** 最近一次 PTZ 命令解码后的方向 / 速度(HUD 显示用). */
    val lastCommandPtz: PtzCommand? = null,
    /**
     * 最近一次设备控制命令的语义分类(UI Tab 派发用)。null = 从未收过 / 未识别。
     * **轨 ④ PR-UI-PROTOCOL-FIX**:UI 不再 parse rawHex,直接读它分流 Tab。
     */
    val lastCommandCategory: DeviceCommandCategory? = null,
    /** 是否有一次性 effect 待 UI 消费(用于驱动 Snackbar/Flash 动画). */
    val hasPendingEffect: Boolean = false,
    /** 当前一次性 effect(=Model.pendingEffect,UI LaunchedEffect 直接消费). */
    val pendingEffect: DeviceEffect? = null,
    /** 辅助开关最近一次状态变更时间戳(ms),供 UI 显示运行时长. */
    val auxTimestamps: Map<Int, Long> = emptyMap(),
)

/**
 * Model → RenderState 纯函数推导。**禁止**在此函数里读取任何外部状态(no time / no random),
 * 必须保证 `deriveRenderState(m) == deriveRenderState(m)`。
 */
fun deriveRenderState(model: DeviceControlModel): DeviceControlRenderState =
    DeviceControlRenderState(
        lastCommandType = model.lastCommand?.type,
        lastCommandHex = model.lastCommand?.rawHex,
        lastRecvAtMs = model.lastCommand?.timestampMs,
        lastCommandPtz = model.lastCommand?.ptz,
        lastCommandCategory = model.lastCommand?.let { deriveCommandCategory(it) },
        hasPendingEffect = model.pendingEffect != null,
        pendingEffect = model.pendingEffect,
        auxTimestamps = model.auxTimestamps,
    )

/**
 * 把协议层 [LastDeviceCommand] 分到 UI Tab 用的 [DeviceCommandCategory]。
 *
 * 历史:UI 层在 `PtzHudPanel.HudTab.fromCommand` 用 `rawHex.startsWith("雨刷")` 等中文字符串
 * 匹配判 Tab,导致协议层 dispatcher 写啥 UI 必须知道。轨 ④ PR-UI-PROTOCOL-FIX 把这段判别压到这里,
 * UI 只读语义枚举。
 *
 * 规则(同原 HudTab.fromCommand 行为):
 *   - PTZCmd:rawHex 以"雨刷/红外灯/加热/除雾/制冷/Aux"开头 → [DeviceCommandCategory.Aux];其余 → [Ptz]
 *   - PTZPreciseCtrl → [Ptz]
 *   - RecordCmd / GuardCmd / AlarmCmd / TeleBoot → [Status]
 *   - IFameCmd / SnapShotCmd / DeviceConfig / DeviceUpgrade / FormatSDCard / TargetTrack → [Image]
 *   - HomePosition → [Ptz](原 HudTab.fromCommand 未列,但 HomePosition 属云台范畴)
 *   - 其他未知 type → null
 */
fun deriveCommandCategory(cmd: LastDeviceCommand): DeviceCommandCategory? = when (cmd.type) {
    "PTZCmd" -> {
        val raw = cmd.rawHex
        val isAux = raw.startsWith("雨刷") || raw.startsWith("红外灯") ||
            raw.startsWith("加热") || raw.startsWith("除雾") ||
            raw.startsWith("制冷") || raw.startsWith("Aux")
        if (isAux) DeviceCommandCategory.Aux else DeviceCommandCategory.Ptz
    }
    "PTZPreciseCtrl", "HomePosition" -> DeviceCommandCategory.Ptz
    "RecordCmd", "GuardCmd", "AlarmCmd", "TeleBoot" -> DeviceCommandCategory.Status
    "IFameCmd", "SnapShotCmd", "DeviceConfig",
    "DeviceUpgrade", "FormatSDCard", "TargetTrack" -> DeviceCommandCategory.Image
    else -> null
}

// ----- Model 复用的领域类型(原放在 DeviceControlState.kt,wrapper 删除后挪到这里) -----

/** GB-2022 §9.13 设备升级进度状态. */
data class UpgradeProgress(
    val sessionId: String,
    val firmware: String,
    val percent: Int,
    val result: UpgradeResult,
)

enum class UpgradeResult { InProgress, Success, Failure }

data class PtzPose(val pan: Float, val tilt: Float, val zoom: Float)

data class DragZoomRect(
    val midX: Int,
    val midY: Int,
    val lengthX: Int,
    val lengthY: Int,
)

data class LastDeviceCommand(
    val type: String,
    val rawHex: String,
    val timestampMs: Long,
    val ptz: PtzCommand? = null,
)

sealed class DeviceEffect {
    data object IFrameFlash : DeviceEffect()
    data object Reboot : DeviceEffect()
    data object SnapshotFlash : DeviceEffect()
    data class HomePositionReturn(val targetPose: PtzPose) : DeviceEffect()
    /** 预置位调用 — 跟看守位 [HomePositionReturn] 区分,UI 同时高亮 chip */
    data class PresetRecall(val index: Int, val targetPose: PtzPose) : DeviceEffect()
    /** GB-2022 §9.3.4 PTZPreciseCtrl 触发的精确角度跳转 */
    data class PrecisePoseGoto(val targetPose: PtzPose) : DeviceEffect()
    data class ConfigChanged(val changedFields: List<String>) : DeviceEffect()
    /** GB-2022 §9.3.4 DeviceUpgrade — UI snackbar 提示,不真 OTA */
    data class DeviceUpgradeRequested(val firmware: String) : DeviceEffect()
    /** GB-2022 §9.3.4 FormatSDCard — UI snackbar 提示,不真格式化 */
    data class FormatSDCardRequested(val cardIndex: Int) : DeviceEffect()
}
