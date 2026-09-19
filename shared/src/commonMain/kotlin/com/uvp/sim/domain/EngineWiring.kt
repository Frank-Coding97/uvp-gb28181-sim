package com.uvp.sim.domain

import com.uvp.sim.config.CatalogNode
import com.uvp.sim.config.GbVersion
import com.uvp.sim.domain.coord.BroadcastCoordinatorImpl
import com.uvp.sim.domain.location.LocationProvider
import com.uvp.sim.domain.coord.InviteCoordinatorImpl
import com.uvp.sim.domain.coord.ManscdpRouterImpl
import com.uvp.sim.domain.coord.PlaybackCoordinatorImpl
import com.uvp.sim.domain.coord.RegistrationCoordinatorImpl
import com.uvp.sim.sip.DefaultSipDialogIdentityService
import com.uvp.sim.sip.RportObservation
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
    /**
     * 附录 I 协商结果:平台在注册响应里声明的协议版本(null = 未声明/不可识别)。
     * 注册协调器是唯一写者,Engine 桥接到此供 UI 与出站报文版决策读取。
     */
    val platformVersion: MutableStateFlow<GbVersion?>,
    /**
     * SIP 长连接自愈状态(`null` = 未在重连)。见 [ReconnectAttempt]。
     *
     * 放在 holders(而非 Engine)的理由跟 [deviceControlState] 一样:engine 会随
     * "改配置 → disconnect/connect" 反复重建,而"我正在重连"是**会话级**事实,
     * 重建那一瞬不该在 UI 上闪一下"未连接"。
     */
    val reconnect: MutableStateFlow<ReconnectAttempt?>,
    /**
     * 平台视角的我方端点 + 是否处于地址转换之后(§9.1.1 f 的适用性判据)。
     * 同 [platformVersion]:注册协调器是唯一写者,Engine 桥接到此供 UI 与排障读取。
     */
    val rportObservation: MutableStateFlow<RportObservation?>,
    val alarmHistoryStore: AlarmHistoryStore,
    val subscriptionRegistry: SubscriptionRegistry,
    val mockGps: LocationProvider,
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
