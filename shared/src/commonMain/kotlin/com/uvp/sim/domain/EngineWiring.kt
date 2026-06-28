package com.uvp.sim.domain

import com.uvp.sim.config.CatalogNode
import com.uvp.sim.domain.coord.BroadcastCoordinatorImpl
import com.uvp.sim.domain.coord.InviteCoordinatorImpl
import com.uvp.sim.domain.coord.ManscdpRouterImpl
import com.uvp.sim.domain.coord.PlaybackCoordinatorImpl
import com.uvp.sim.domain.coord.RegistrationCoordinatorImpl
import com.uvp.sim.sip.DefaultSipDialogIdentityService
import com.uvp.sim.sip.SipDialogIdentityService
import com.uvp.sim.sip.SipState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Engine 装配契约(P1.5):AppEngine 装配后注入 Engine,Engine 不再 own。
 * 解耦「装配」与「编排」职责,Engine 退化为「5 Coord 引用 + 路由 + bridge」。
 *
 * holders 实例 SimConfig 变更时通过 AppEngine.rehydrateHolders 就地更新内部状态
 * (不替换实例引用 — 已有 Coord 持构造期 snapshot)。新增 Coord 在 reconnect 路径自然
 * 拿到 fresh 状态。
 */
internal class EngineHolders(
    val state: MutableStateFlow<SipState>,
    val events: MutableSharedFlow<SimEvent>,
    val deviceControlState: MutableStateFlow<DeviceControlModel>,
    val catalogTree: MutableStateFlow<List<CatalogNode>>,
    val clockOffset: MutableStateFlow<ClockOffset>,
    val alarmHistoryStore: AlarmHistoryStore,
    val subscriptionRegistry: SubscriptionRegistry,
    val mockGps: MockGpsSource,
    /** Wave 2 PR-SN-IDENTITY:3 类 dialog identity 显式分离。 */
    val identityService: SipDialogIdentityService,
)

/** 5 Coord 装配后由 AppEngine 注入 Engine。 */
internal class EngineCoordinators(
    val registration: RegistrationCoordinatorImpl,
    val broadcast: BroadcastCoordinatorImpl,
    val playback: PlaybackCoordinatorImpl,
    val invite: InviteCoordinatorImpl,
    val manscdp: ManscdpRouterImpl,
)

/**
 * Wave 2 PR-SN-IDENTITY(2026-06-26)起 [SipDialogIdentityService] 通过 [Mutex] 保护的 3 套独立
 * counter 提供原子性 `nextRegister() / nextMessageNotify() / nextInvite()`。Manscdp 直接注入 service,
 * 内部 fallback 全删。
 *
 * P2-3(2026-06-28):Reg/Broadcast/Invite/Playback 4 Coord 既有 6 lambda 入口(cseq/callId/fromTag
 * provider+setter)在 [com.uvp.sim.app.AppEngine.buildCoordinators] 装配段内联,
 * 不再走单独"派生自 identityService"的过渡桥(避免误导)。Coord 签名保留,Wave 3+ 真迁可清。
 *
 * 详见 wiki/projects/uvp-gb28181-sim/research/2026-06-23-cseq-sn-pool-coupling.md
 */

/** 工厂入口:构造默认实现。 */
internal fun newDefaultIdentityService(localIpProvider: () -> String): SipDialogIdentityService =
    DefaultSipDialogIdentityService(localIpProvider = localIpProvider)
