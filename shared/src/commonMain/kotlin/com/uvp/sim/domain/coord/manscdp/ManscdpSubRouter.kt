package com.uvp.sim.domain.coord.manscdp

import com.uvp.sim.config.CatalogNode
import com.uvp.sim.config.SimConfig
import com.uvp.sim.domain.ClockOffset
import com.uvp.sim.domain.DeviceControlModel
import com.uvp.sim.domain.SimEvent
import com.uvp.sim.domain.SubscriptionRegistry
import com.uvp.sim.domain.location.LocationProvider
import com.uvp.sim.sip.SipDialogIdentityService
import com.uvp.sim.sip.SipOutbox
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 按 GB28181 业务大类拆出的 MANSCDP 子路由(Wave 4 PR-D / P2-1)。
 *
 * 一个 SubRouter 只负责一组业务相关的 CmdType:
 *  - [CatalogSubRouter]:Catalog / DeviceInfo / DeviceStatus / ConfigDownload / RecordInfo / MobilePosition / 设备查询类
 *  - [AlarmSubRouter]:AlarmStatus(查询)+ 主动报警上报路径(reportAlarm / localResetAlarm)
 *  - [DeviceControlSubRouter]:DeviceControl(PTZ / Preset / Aux / System / Upgrade)/ PresetQuery / CruiseTrack
 *  - [BroadcastSubRouter]:Broadcast / 媒体上传通知 / triggerMediaStatusAbnormal
 *
 * 派发规则在 [ManscdpDispatcher]:按 CmdType 查表 → 第一个 [accepts] 返回 true 的子路由吃下 →
 * [handle] 返回 true 表示处理完成,后续 SubRouter 不再尝试。
 *
 * SubRouter 不直接 own MANSCDP 全局状态(SubscriptionRegistry / catalogTree / deviceControlState),
 * 共享依赖通过 [ManscdpContext] 注入,避免 4 个子路由各自重复持构造期 snapshot。
 */
internal interface ManscdpSubRouter {
    /** 是否该 SubRouter 负责处理这条 MANSCDP 命令(按 CmdType 大写比较)。 */
    fun accepts(cmdType: String): Boolean

    /**
     * 真正处理一条 MANSCDP MESSAGE 请求。
     * 返回 true:已处理完毕,Dispatcher 不再 fallthrough;false:不识别,继续下一个。
     */
    suspend fun handle(cmdType: String, xml: String, fromUri: String?): Boolean
}

/**
 * SubRouter 间共享的运行期上下文 + 装配依赖。
 *
 * Manscdp 域有 9 个跨子路由共享的依赖(SimConfig / outbox / identityService / subscriptionRegistry /
 * deviceControlState / catalogTree / 时钟 / 状态 guard / event emit),全收口到此结构,
 * 避免每个 SubRouter 构造器都来一长串相同参数。
 *
 * Wave 3 PR-DC-DECOUPLE 起 deviceControlState 已是 [DeviceControlModel] 流;
 * SubRouter 通过 [deviceControlState] 直读 + update 写,Dispatcher 在背后保证唯一写者语义。
 */
internal class ManscdpContext(
    val config: SimConfig,
    val outbox: SipOutbox,
    val identityService: SipDialogIdentityService,
    val subscriptionRegistry: SubscriptionRegistry,
    val deviceControlState: MutableStateFlow<DeviceControlModel>,
    val catalogTree: MutableStateFlow<List<CatalogNode>>,
    val mockGps: LocationProvider,
    val localIpProvider: () -> String,
    val localPortProvider: () -> Int,
    val clockOffsetProvider: () -> ClockOffset,
    val stateRegisteredOrInCall: () -> Boolean,
    val simEventEmit: suspend (SimEvent) -> Unit,
) {
    val localIp: String get() = localIpProvider()
    val localPort: Int get() = localPortProvider()
}
