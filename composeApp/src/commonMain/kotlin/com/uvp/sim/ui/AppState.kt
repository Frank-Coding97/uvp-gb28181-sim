package com.uvp.sim.ui

import com.uvp.sim.config.CatalogNode
import com.uvp.sim.config.SimConfig
import com.uvp.sim.ui.actions.CapabilityActions
import com.uvp.sim.ui.actions.HomeActions
import com.uvp.sim.ui.actions.NetworkActions
import com.uvp.sim.ui.actions.RecordingActions
import com.uvp.sim.ui.model.AlarmPayloadDto
import com.uvp.sim.ui.model.AlarmRecordDto
import com.uvp.sim.ui.model.ClockOffsetDto
import com.uvp.sim.ui.model.DeviceControlDto
import com.uvp.sim.ui.model.NetworkStateDto
import com.uvp.sim.ui.model.RecordSourceDto
import com.uvp.sim.ui.model.RecordingFileDto
import com.uvp.sim.ui.model.SessionMarkerDto
import com.uvp.sim.ui.model.SimEventDto
import com.uvp.sim.ui.model.SipStateDto
import com.uvp.sim.ui.model.SystemLogDto

/**
 * Read-only view of the simulator state for the UI layer.
 *
 * The platform-specific shell (Android MainActivity, iOS UIViewController)
 * collects [AppEngine.state] / [AppEngine.events] / its config
 * and bundles them into this snapshot for Compose to render.
 */
data class AppUiState(
    val sip: SipStateDto,
    val config: SimConfig,
    val events: List<SimEventDto>,
    val systemEvents: List<SystemLogDto> = emptyList(),
    val sessionMarker: SessionMarkerDto? = null,
    /**
     * 上级订阅状态快照。M2 接通真实 SUBSCRIBE 应答后由 AppEngine 推。
     * 主屏「位置订阅」「目录订阅」状态卡读这个 map 做活/灰判定。
     */
    val subscriptions: Map<SubscriptionKind, SubscriptionStatus> = emptyMap(),
    /**
     * M2 设备控制运行时状态(AppEngine.deviceControlState 快照).
     * 由 SimulateScreen 的 PtzHudPanel + Camera3DView 订阅消费.
     */
    val deviceControl: DeviceControlDto = DeviceControlDto(),
    /**
     * 录像状态快照。HomeScreen 录像 ActionTile 读这个判红点 / 计时器,
     * RecordingScreen 列表 读 files。M2 默认空,引擎接通后实时刷。
     */
    val recording: RecordingStatus = RecordingStatus(),
    /**
     * 平台 PLAYBACK 回放状态。M2 D 块接通后实时刷,M2 默认空。
     */
    val playback: PlaybackStatus = PlaybackStatus(),
    /**
     * 当前生效的目录树。AppEngine.catalogTree 投影,
     * 「能力」Tab 的目录管理界面读这个 list 做编辑入口的初始 draft。
     */
    val catalogTree: List<CatalogNode> = emptyList(),
    /** 上一次保存目录树的 epoch ms,UI 显示「X 分钟前已保存」。 */
    val lastCatalogSavedAt: Long? = null,
    /**
     * 本会话已发报警历史(最近若干条,不持久化,重启清空)。
     * 能力页报警卡角标读 size 显示报警次数,子页历史折叠区读列表。
     */
    val alarmHistory: List<AlarmRecordDto> = emptyList(),
    /**
     * 报警发送模式(spec G2)。主页"一点即发"按此模式走。本会话内存。
     */
    val alarmFireMode: AlarmFireMode = AlarmFireMode.Random,
    /**
     * 指定模式下用户保存的固定报警单(null = 还没存,退化随机)。本会话内存。
     */
    val fixedAlarmTemplate: AlarmPayloadDto? = null,
    /**
     * M3 语音广播下行(平台喊话设备)状态。isReceiving=true 时主屏挂「对讲中」标签。
     */
    val broadcast: BroadcastState = BroadcastState(),
    /**
     * 当前网络运行时状态(NetworkController.state 投影)。
     * - Android: 反映 ConnectivityManager 实际绑定结果
     * - iOS / JVM: 永远是 Auto(no-op 实现)
     *
     * UI 用途:
     * - 设置 → 网络子页诊断区显示接口名 / IP
     * - 主屏顶 banner:Unavailable 时显示红 banner 提示老板
     */
    val networkRuntimeState: NetworkStateDto = NetworkStateDto.Auto,
    /**
     * M5 batch2 §4.15 — SIP Date 校时偏移快照(AppEngine.clockOffset 投影)。
     * 能力中心「设备校时」tile + ClockSyncScreen 读这个,
     * 显示平台基准时间 / 偏移 / 原始 Date 头。
     */
    val clockOffset: ClockOffsetDto = ClockOffsetDto.Empty
)

/**
 * M3 语音广播下行 UI 状态。SipViewModel 从 engine.currentBroadcast 投影。
 */
data class BroadcastState(
    val isReceiving: Boolean = false,
    val sourceId: String? = null,
    val codec: String? = null,
    val localAudioPort: Int = -1,
    val remoteAudioHost: String? = null,
    val remoteAudioPort: Int = -1,
    val rxPackets: Long = 0L,
    val rxBytes: Long = 0L,
    val seqLost: Long = 0L,
    val decodeErrors: Long = 0L,
    /** 扬声器开关(false = 静音)。 */
    val speakerOn: Boolean = true
)

/**
 * 报警发送模式(spec G2)。
 * - Random:每次从预置模板池随机抽一个
 * - Fixed:固定发用户编好的 [AppUiState.fixedAlarmTemplate]
 */
enum class AlarmFireMode { Random, Fixed }

/**
 * GB/T 28181 上级订阅类型。M1 列出 UI 关心的两种,M2 接信令时按需扩展。
 */
enum class SubscriptionKind {
    /** MobilePosition — 平台 SUBSCRIBE 后设备周期 NOTIFY GPS. */
    MobilePosition,
    /** Catalog — 平台 SUBSCRIBE 后设备 NOTIFY 目录变更. */
    Catalog,
    /** Alarm — 平台 SUBSCRIBE Event:Alarm 后设备在每次报警时 NOTIFY. */
    Alarm
}

/**
 * 订阅快照。所有字段都可空,M1 mock 全 false / null。
 */
data class SubscriptionStatus(
    val active: Boolean = false,
    val subscriber: String? = null,
    val expiresSeconds: Int? = null,
    val remainingSeconds: Int? = null,
    val notifyCount: Int = 0
)

/**
 * 录像状态快照。
 *
 * - isRecording / source / startMs / segmentIndex 喂给主屏 ActionTile 渲染红点 + 计时器
 * - files 是当前已落盘的全量录像列表(本地索引),录像 tab 直接消费
 * - lastError 在状态机进 Failed 时填,UI toast 用
 */
data class RecordingStatus(
    val isRecording: Boolean = false,
    val source: RecordSourceDto? = null,
    val startMs: Long? = null,
    val segmentIndex: Int = 0,
    val lastError: String? = null,
    val files: List<RecordingFileDto> = emptyList()
)

/**
 * 平台 PLAYBACK 回放状态。
 *
 * - active 表示当前是否在推一段历史录像给平台
 * - currentSegmentId / progressMs / totalSpanMs 给录像列表上的"正在回放"高亮 + 进度
 */
data class PlaybackStatus(
    val active: Boolean = false,
    val callId: String? = null,
    val currentSegmentId: String? = null,
    val totalSegments: Int = 0,
    val progressMs: Long = 0,
    val totalSpanMs: Long = 0
)

/**
 * UI 层全部用户动作入口 — composite marker interface(PR-B 重构)。
 *
 * 27 个具体动作按业务域拆到 4 个 slice:
 *  - [HomeActions] 注册三件套 + 配置保存 + 日志清除 + effect consume
 *  - [CapabilityActions] 抓拍 / 报警 / 广播 / 目录 / 媒体异常 / PTZ pose
 *  - [RecordingActions] 录像启停 / 删除 / 筛选
 *  - [NetworkActions] 网络偏好切换
 *
 * UI 层仍按 `actions: AppActions` 单参数传入,`actions.onConnect()` 调用模式不变。
 * 新增动作直接加到对应 slice,不在 AppActions 加方法。
 */
interface AppActions : HomeActions, CapabilityActions, RecordingActions, NetworkActions

enum class AppTab(val label: String) {
    Home("主页"),
    Capability("能力"),
    Simulate("模拟"),
    Log("日志"),
    Settings("设置");
}
