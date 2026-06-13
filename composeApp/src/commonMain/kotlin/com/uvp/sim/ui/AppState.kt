package com.uvp.sim.ui

import com.uvp.sim.config.CatalogNode
import com.uvp.sim.config.SimConfig
import com.uvp.sim.domain.SimEvent
import com.uvp.sim.observability.SessionMarker
import com.uvp.sim.observability.SystemLog
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
     * 当前生效的目录树。SimulatorEngine.catalogTree 投影,
     * 「能力」Tab 的目录管理界面读这个 list 做编辑入口的初始 draft。
     */
    val catalogTree: List<CatalogNode> = emptyList()
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
 * Actions the UI can request. The platform shell binds these to the engine
 * + ViewModel; commonMain stays platform-free.
 */
interface AppActions {
    fun onConnect()
    fun onCancelConnect()
    fun onDisconnect()
    fun onSnapshot()
    fun onConfigSave(updated: SimConfig)
    /**
     * 用户在目录管理界面点保存,把新树写回 engine + 持久化。
     * 若有活跃 Catalog 订阅,engine 会立即推一次完整 NOTIFY(spec Q6)。
     */
    fun onCatalogTreeSave(tree: List<CatalogNode>) {}
}

enum class AppTab(val label: String) {
    Home("主页"),
    Capability("能力"),
    Recording("录像"),
    Log("日志"),
    Settings("设置");
}
