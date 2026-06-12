package com.uvp.sim.ui

import com.uvp.sim.config.SimConfig
import com.uvp.sim.domain.SimEvent
import com.uvp.sim.observability.SessionMarker
import com.uvp.sim.observability.SystemLog
import com.uvp.sim.recording.RecordSource
import com.uvp.sim.recording.RecordingFile
import com.uvp.sim.recording.RecordingFilter
import com.uvp.sim.sip.SipState

/**
 * Read-only view of the simulator state for the UI layer.
 *
 * The platform-specific shell (Android MainActivity, iOS UIViewController)
 * collects [SimulatorEngine.state] / [SimulatorEngine.events] / its config
 * and bundles them into this snapshot for Compose to render.
 */
data class AppUiState(
    val sip: SipState,
    val config: SimConfig,
    val events: List<SimEvent>,
    val systemEvents: List<SystemLog> = emptyList(),
    val sessionMarker: SessionMarker? = null,
    /**
     * 上级订阅状态快照。M2 接通真实 SUBSCRIBE 应答后由 SimulatorEngine 推。
     * 主屏「位置订阅」「目录订阅」状态卡读这个 map 做活/灰判定。
     */
    val subscriptions: Map<SubscriptionKind, SubscriptionStatus> = emptyMap(),
    /**
     * 录像状态快照。HomeScreen 录像 ActionTile 读这个判红点 / 计时器,
     * RecordingScreen 列表 读 files。M2 默认空,引擎接通后实时刷。
     */
    val recording: RecordingStatus = RecordingStatus(),
    /**
     * 平台 PLAYBACK 回放状态。M2 D 块接通后实时刷,M2 默认空。
     */
    val playback: PlaybackStatus = PlaybackStatus()
)

/**
 * GB/T 28181 上级订阅类型。M1 列出 UI 关心的两种,M2 接信令时按需扩展。
 */
enum class SubscriptionKind {
    /** MobilePosition — 平台 SUBSCRIBE 后设备周期 NOTIFY GPS. */
    MobilePosition,
    /** Catalog — 平台 SUBSCRIBE 后设备 NOTIFY 目录变更. */
    Catalog
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
    val source: RecordSource? = null,
    val startMs: Long? = null,
    val segmentIndex: Int = 0,
    val lastError: String? = null,
    val files: List<RecordingFile> = emptyList()
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
 * Actions the UI can request. The platform shell binds these to the engine
 * + ViewModel; commonMain stays platform-free.
 *
 * 录像 4 个动作全部带默认空实现,别的 worktree 拿 main 后零改动编译过。
 */
interface AppActions {
    fun onConnect()
    fun onCancelConnect()
    fun onDisconnect()
    fun onSnapshot()
    fun onConfigSave(updated: SimConfig)

    fun onRecordingStart() {}
    fun onRecordingStop() {}
    fun onRecordingDelete(id: String) {}
    fun onRecordingFilterApply(filter: RecordingFilter) {}
}

enum class AppTab(val label: String) {
    Home("主页"),
    Settings("设置"),
    Recording("录像"),
    Log("日志");
}

