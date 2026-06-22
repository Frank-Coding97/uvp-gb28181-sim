package com.uvp.sim.domain.coord

import com.uvp.sim.config.SimConfig
import com.uvp.sim.domain.ClockOffset
import com.uvp.sim.network.NetworkState
import com.uvp.sim.network.SipTransport
import com.uvp.sim.sip.SipMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [RegistrationCoordinator] 真实现。PR2 T2.1 阶段为空 stub,T2.2 时填实。
 *
 * 迁移来源(见 plan 第 2.1 节):
 *   SimulatorEngine.register / cancelRegister / unregister /
 *   handleRegisterResponse / armRegisterTimeout / cancelRegisterTimeout /
 *   scheduleRetryOrFail / doRegisterInternal / triggerReregisterIfActive / handleOptions
 *
 * 跨域决策(见 plan 第 2.1.1 节):
 *   - 自带 Mutex,不共享 Engine 大锁
 *   - 自管 _state / _events / _clockOffset,Engine façade 聚合
 *   - heartbeat 完全归本域
 */
internal class RegistrationCoordinatorImpl(
    private val config: SimConfig,
    private val transport: SipTransport,
    private val scope: CoroutineScope,
    private val localIpProvider: () -> String = { "0.0.0.0" },
    private val localPortProvider: () -> Int = { 5060 },
) : RegistrationCoordinator {

    private val _state = MutableStateFlow(RegistrationState.Disconnected)
    override val state: StateFlow<RegistrationState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<RegistrationEvent>(extraBufferCapacity = 32)
    override val events: SharedFlow<RegistrationEvent> = _events.asSharedFlow()

    private val _clockOffset = MutableStateFlow(ClockOffset.Empty)
    override val clockOffset: StateFlow<ClockOffset> = _clockOffset.asStateFlow()

    override suspend fun register() {
        TODO("PR2 T2.2: 迁移 SimulatorEngine.register()")
    }

    override suspend fun cancelRegister() {
        TODO("PR2 T2.2: 迁移 SimulatorEngine.cancelRegister()")
    }

    override suspend fun unregister() {
        TODO("PR2 T2.2: 迁移 SimulatorEngine.unregister()")
    }

    override suspend fun onIncoming(msg: SipMessage): RoutingResult {
        TODO("PR2 T2.2: 路由 REGISTER 响应 + OPTIONS 请求")
    }

    override suspend fun onNetworkChange(state: NetworkState) {
        TODO("PR2 T2.2: 迁移 SimulatorEngine.handleNetworkChange()")
    }

    override suspend fun shutdown() {
        TODO("PR2 T2.2: 释放心跳 + 重试 + 续约 job")
    }
}
