package com.uvp.sim.domain

import com.uvp.sim.gb28181.PtzCommand
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.StateFlow

/**
 * Wave 3 PR-DC-DECOUPLE(2026-06-26):把原 [DeviceControlState] 拆成「业务 Model」+ 「UI RenderState」两层。
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
 * Handler / Dispatcher / Coord 全部改用 `MutableStateFlow<DeviceControlModel>`;
 * UI 层(AppEngine 出口、SimulatorEngine.deviceControlState 公开 StateFlow)仍暴露
 * [DeviceControlState] 兼容包装(见同名文件),让 PR-UI-PROTOCOL-FIX(轨 ③)逐步迁。
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
 * UI 渲染派生层 — 仅含「显示用文本 / 时间戳 / 一次性 effect 标志」,由 [deriveRenderState] 从
 * [DeviceControlModel] 纯函数推导(single source of truth = Model)。
 *
 * 设计:UI Mapper / Compose 视图只读 RenderState 里的字段,不再读 Model 内部细节如 lastCommand?.rawHex,
 * 这样 Model 字段调整不破 UI 契约;同时 RenderState 不持有独立可变状态,Model 变 → RenderState 自动跟。
 *
 * **当前过渡阶段**:轨 ② 仅引入 RenderState 类型 + deriveRenderState 函数,UI Mapper 暂仍读
 * [DeviceControlState] 兼容 wrapper 内的旧 shape;轨 ③(PR-UI-PROTOCOL-FIX)把 Mapper 切到读
 * `state.render.xxx` / `state.model.xxx` 干净两段。
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
        hasPendingEffect = model.pendingEffect != null,
        pendingEffect = model.pendingEffect,
        auxTimestamps = model.auxTimestamps,
    )

/**
 * Wave 3 PR-DC-DECOUPLE:同步派生 [StateFlow] 包装器。
 *
 * 用途:把 `StateFlow<DeviceControlModel>` 映射成 `StateFlow<DeviceControlState>` 兼容 wrapper,
 * **不启动独立 collect 协程**(避免 `stateIn(Eagerly)` 在 `runTest` 环境里触发
 * `UncompletedCoroutinesError`)。
 *
 * 实现:`.value` 取值时同步包一层 wrapper;`.collect` 透传给底层 model flow 的 collect,
 * 边界处即时映射成 wrapper 发出。零额外协程,零额外内存。
 */
@Suppress("DEPRECATION")
internal class DerivedDeviceControlStateFlow(
    private val source: StateFlow<DeviceControlModel>,
) : StateFlow<DeviceControlState> {
    override val value: DeviceControlState get() = DeviceControlState(model = source.value)
    override val replayCache: List<DeviceControlState> get() = listOf(value)

    override suspend fun collect(collector: FlowCollector<DeviceControlState>): Nothing {
        source.collect { collector.emit(DeviceControlState(model = it)) }
    }
}
