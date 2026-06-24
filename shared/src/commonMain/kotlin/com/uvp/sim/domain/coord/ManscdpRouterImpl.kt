package com.uvp.sim.domain.coord

import com.uvp.sim.config.CatalogNode
import com.uvp.sim.config.SimConfig
import com.uvp.sim.domain.AlarmHistoryStore
import com.uvp.sim.domain.DeviceControlDispatcher
import com.uvp.sim.domain.DeviceControlState
import com.uvp.sim.domain.SubscriptionRegistry
import com.uvp.sim.gb28181.AlarmPayload
import com.uvp.sim.network.SipTransport
import com.uvp.sim.sip.SipMessage
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * [ManscdpRouter] 真实现(PR3 T3.2 GREEN)。
 *
 * 接管 Engine 的 MANSCDP 路由 + 8 路径主动业务发起。
 *
 * 跨域决策(plans/refactor-pr3-manscdp-router.md):
 *   - 决策 1:SubscriptionRegistry / CatalogTreeStore 现有实例 / AlarmHistoryStore 由 Engine 持有
 *     注入(只有 Router 写,Engine 仅 cancelAll 不构成双写)
 *   - 决策 2:fan-out 路径完成后通过 [ManscdpEvent] 类目把"业务侧后置动作"
 *     桥接到 Engine,保证事件顺序
 *   - SN 池跨域共享:6 个 lambda 注入,跟 PR2 RegistrationCoordinator 同模式
 *
 * **当前状态**:T3.1 RED — 仅接口骨架,所有方法 TODO,等 T3.2 GREEN 填实现。
 */
internal class ManscdpRouterImpl(
    private val config: SimConfig,
    private val transport: SipTransport,
    private val scope: CoroutineScope,
    private val localIpProvider: () -> String = { "0.0.0.0" },
    private val localPortProvider: () -> Int = { 5060 },
    private val subscriptionRegistry: SubscriptionRegistry,
    private val catalogTreeStore: List<CatalogNode>,
    private val alarmHistoryStore: AlarmHistoryStore,
    private val mutableDeviceControlState: MutableStateFlow<DeviceControlState>,
    private val deviceControlDispatcher: DeviceControlDispatcher,
    private val broadcastInvoker: BroadcastInvoker,
    cseqProvider: (() -> Int)? = null,
    cseqIncrementer: (() -> Int)? = null,
    callIdProvider: (() -> String?)? = null,
    callIdSetter: ((String) -> Unit)? = null,
    fromTagProvider: (() -> String?)? = null,
    fromTagSetter: ((String) -> Unit)? = null,
) : ManscdpRouter {

    private val _events = MutableSharedFlow<ManscdpEvent>(extraBufferCapacity = 32)
    override val events: SharedFlow<ManscdpEvent> = _events.asSharedFlow()

    override val deviceControlState: StateFlow<DeviceControlState> = mutableDeviceControlState

    // SN 池 provider 适配(跟 PR2 RegistrationCoordinator 同模式)
    private var internalCseq = 0
    private var internalCallId: String? = null
    private var internalFromTag: String? = null

    private val cseqRead: () -> Int = cseqProvider ?: { internalCseq }
    private val cseqIncAndRead: () -> Int = cseqIncrementer ?: {
        internalCseq += 1
        internalCseq
    }
    private val callIdRead: () -> String? = callIdProvider ?: { internalCallId }
    private val callIdWrite: (String) -> Unit = callIdSetter ?: { internalCallId = it }
    private val fromTagRead: () -> String? = fromTagProvider ?: { internalFromTag }
    private val fromTagWrite: (String) -> Unit = fromTagSetter ?: { internalFromTag = it }

    override suspend fun onIncoming(msg: SipMessage): RoutingResult {
        TODO("T3.2 GREEN — handleMessage / handleSubscribe / handleInfo 路由分发")
    }

    override suspend fun shutdown() {
        TODO("T3.2 GREEN — 收掉本域协程")
    }

    override suspend fun reportAlarm(payload: AlarmPayload) {
        TODO("T3.2 GREEN — 迁移 SimulatorEngine.reportAlarm")
    }

    override suspend fun localResetAlarm() {
        TODO("T3.2 GREEN — 迁移 SimulatorEngine.localResetAlarm")
    }

    override suspend fun reportSnapshot() {
        TODO("T3.2 GREEN — 迁移 SimulatorEngine.reportSnapshot")
    }

    override suspend fun triggerMediaStatusAbnormal(notifyType: Int) {
        TODO("T3.2 GREEN — 迁移 SimulatorEngine.triggerMediaStatusAbnormal")
    }

    override fun attachSnapshotPipeline(
        capture: com.uvp.sim.snapshot.SnapshotCapture,
        cache: com.uvp.sim.snapshot.JpegLocalCache,
        httpClient: HttpClient,
    ) {
        TODO("T3.2 GREEN — 迁移 SimulatorEngine.attachSnapshotPipeline")
    }
}
