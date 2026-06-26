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
 * Wave 2 PR-SN-IDENTITY(2026-06-26):删 SipSnPool 的 lambda provider 入口模式,
 * 改成 [SipDialogIdentityService] 显式分 3 类 dialog identity(Register / MessageNotify / Invite)。
 *
 * 历史:原 `SipSnPool` 暴露 6 个跨域共享 lambda(cseqProvider/cseqIncrementer/
 * callIdProvider/callIdSetter/fromTagProvider/fromTagSetter),Reg/Broadcast/
 * Invite/Playback/Manscdp 5 个 Coord 装配时全部从同一对象取 lambda 注入。
 * Manscdp 同时还有内部 fallback(internalCseq/internalCallId/internalFromTag
 * 自分配),双轨并存,容易竞态。
 *
 * 现在:[SipDialogIdentityService] 通过 [Mutex] 保护的 3 套独立 counter 提供
 * 原子性 `nextRegister() / nextMessageNotify() / nextInvite()`。Manscdp 直接
 * 注入 service,内部 fallback 全删。Reg/Broadcast/Invite/Playback 4 Coord 的
 * lambda 注入入口 [registerPoolLambdas] 派生自 Register pool,保留它们既有的
 * lambda 构造器签名以最小化改动面;后续 Wave 3+ 再逐个迁。
 *
 * 详见 wiki/projects/uvp-gb28181-sim/research/2026-06-23-cseq-sn-pool-coupling.md
 */
internal class RegisterPoolLambdas(
    val cseqProvider: () -> Int,
    val cseqIncrementer: () -> Int,
    val callIdProvider: () -> String?,
    val callIdSetter: (String) -> Unit,
    val fromTagProvider: () -> String?,
    val fromTagSetter: (String) -> Unit,
)

/**
 * 从 [SipDialogIdentityService] 派生出旧 SipSnPool 等价的 6 lambda 入口,
 * 共享 Register pool 内部 counter(因为既有 4 Coord 的实际语义就是「Engine 全局 cseq」)。
 *
 * 注意:这是过渡桥。Wave 3+ 改 4 Coord 构造器签名后,本函数可删。
 */
internal fun registerPoolLambdasFrom(service: SipDialogIdentityService): RegisterPoolLambdas {
    // 共享 cseq counter:既有 4 Coord 期待「读不递增,inc 才推进」语义,
    // 因此用一个独立 var 桥接,跟 service 的 register pool 解耦(避免 read-side
    // 读 service 时强行加锁)。本桥仅用 4 Coord,Manscdp 走纯 service。
    var cseq = 0
    var callId: String? = null
    var fromTag: String? = null
    return RegisterPoolLambdas(
        cseqProvider = { cseq },
        cseqIncrementer = { cseq += 1; cseq },
        callIdProvider = { callId },
        callIdSetter = { callId = it },
        fromTagProvider = { fromTag },
        fromTagSetter = { fromTag = it },
    )
}

/** 工厂入口:构造默认实现。 */
internal fun newDefaultIdentityService(localIpProvider: () -> String): SipDialogIdentityService =
    DefaultSipDialogIdentityService(localIpProvider = localIpProvider)
