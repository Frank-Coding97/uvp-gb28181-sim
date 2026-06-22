package com.uvp.sim.domain.coord

import com.uvp.sim.domain.ClockOffset
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 注册对话域。
 *
 * 接管的 SIP 流程(plan 第 2.1 节):
 * - REGISTER / 401 challenge / 200 OK / Expires=0 注销
 * - OPTIONS 心跳探测
 * - 心跳计数 / 续约定时 / 重试退避
 * - 网络切换重注册
 *
 * 来自 SimulatorEngine 的方法迁移清单:register / cancelRegister / unregister /
 * armRegisterTimeout / cancelRegisterTimeout / scheduleRetryOrFail /
 * doRegisterInternal / triggerReregisterIfActive / handleRegisterResponse / handleOptions
 */
internal interface RegistrationCoordinator : Coordinator {
    /** 注册主状态。Engine 用这个聚合 SipState。 */
    val state: StateFlow<RegistrationState>

    /** 注册事件单向流。Engine 桥接到 SimEvent。 */
    val events: SharedFlow<RegistrationEvent>

    /** 200 OK Date 头解析的服务端时间偏移(M5 §4.15 校时能力)。 */
    val clockOffset: StateFlow<ClockOffset>

    suspend fun register()
    suspend fun cancelRegister()
    suspend fun unregister()
}

internal enum class RegistrationState {
    Disconnected,
    Registering,
    Registered,
    RetryBackoff,
    Failed,
}

internal sealed class RegistrationEvent {
    data object Registered : RegistrationEvent()
    data object Renewed : RegistrationEvent()
    data class AuthChallenged(val realm: String) : RegistrationEvent()
    data class Unauthorized(val statusCode: Int, val reason: String) : RegistrationEvent()
    data class NetworkSwitchedReregister(val newIp: String) : RegistrationEvent()
}
