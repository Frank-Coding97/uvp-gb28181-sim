package com.uvp.sim.domain

import com.uvp.sim.config.CatalogNode
import com.uvp.sim.domain.coord.BroadcastCoordinatorImpl
import com.uvp.sim.domain.coord.InviteCoordinatorImpl
import com.uvp.sim.domain.coord.ManscdpRouterImpl
import com.uvp.sim.domain.coord.PlaybackCoordinatorImpl
import com.uvp.sim.domain.coord.RegistrationCoordinatorImpl
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
    val deviceControlState: MutableStateFlow<DeviceControlState>,
    val catalogTree: MutableStateFlow<List<CatalogNode>>,
    val clockOffset: MutableStateFlow<ClockOffset>,
    val alarmHistoryStore: AlarmHistoryStore,
    val subscriptionRegistry: SubscriptionRegistry,
    val mockGps: MockGpsSource,
    val snPool: SipSnPool,
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
 * Engine 全局 SIP SN 池 + dialog identity。5 Coord 通过 lambda 共享读写,
 * AppEngine 装配 Coord 时从本对象取 provider/setter 注入。
 *
 * 详见 wiki/projects/uvp-gb28181-sim/research/2026-06-23-cseq-sn-pool-coupling.md
 */
internal class SipSnPool {
    private var cseqValue: Int = 0
    private var callIdValue: String? = null
    private var fromTagValue: String? = null

    val cseqProvider: () -> Int = { cseqValue }
    val cseqIncrementer: () -> Int = { cseqValue += 1; cseqValue }
    val callIdProvider: () -> String? = { callIdValue }
    val callIdSetter: (String) -> Unit = { callIdValue = it }
    val fromTagProvider: () -> String? = { fromTagValue }
    val fromTagSetter: (String) -> Unit = { fromTagValue = it }
}
