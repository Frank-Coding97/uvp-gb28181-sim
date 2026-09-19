package com.uvp.sim.domain.coord

import com.uvp.sim.config.GbVersion
import com.uvp.sim.domain.ClockOffset
import com.uvp.sim.sip.RportObservation
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

    /**
     * 平台在 REGISTER 响应(含 401/4xx 等失败响应,附录 I 要求"无论成功或失败"都带)里
     * 声明的协议版本;null 表示未声明或不可识别(此时后续仍按本机声明版本交互)。
     *
     * 注销后清空 —— 协商结论只对一次注册会话成立,下轮注册要重新"在注册过程中得知"。
     */
    val platformVersion: StateFlow<GbVersion?>

    /**
     * 平台视角的我方端点:从注册响应 Via 回填的 `received` / `rport` 得到,附带「是否在 NAT 后」的判定。
     *
     * ## 为什么设备需要知道这件事
     * §9.1.1 f) 要求「处于 NAT 内侧的 SIP 代理宜用 TCP 注册并保持连接」。设备自己**看不到**自己
     * 在不在 NAT 后面 —— 唯一的信息来源就是平台把「我看到的你是什么地址」回填进响应。
     * 不知道这一点,现场只能看到「注册成功但所有下行命令都超时」,而这现象在 UDP 与
     * 防火墙丢包下长得一模一样。
     *
     * ## ⛔ 它不是合规项,解析失败是正常的
     * `rport` / `received` 在 GB/T 28181-2022 全文**零出现**(那是 RFC 3581 的手段,标准给 NAT
     * 的答案是 TCP 长连接复用)。平台不回填时这里就是 `null`,**不要**把它当错误告警。
     *
     * 与 [platformVersion] 一样,**注销后清空**:观察值只对一次注册会话成立。
     */
    val rportObservation: StateFlow<RportObservation?>

    suspend fun register()
    suspend fun cancelRegister()
    suspend fun unregister()

    /**
     * 传输层**被动**断开:本连接上的一切注册事务作废,且**不发任何报文**。
     *
     * 为什么必须单独有这个方法,而不是复用 [unregister]:
     * - [unregister] 要发一条 `Expires=0` 的 REGISTER 并等平台确认(最长 3 秒)。连接已经断了,
     *   这条报文只可能落到死 socket 上,等来的必然是超时 —— 白白拖慢自愈,平台也收不到
     *   (它那边是按 TCP 连接断了在清理),纯属自欺。
     * - 更关键的是 [register] 开头的守卫:`_state == Registered` 时**直接 return**。断链后状态
     *   仍停在 Registered(心跳还在跑,只是发不出去),不先把状态作废,[register] 就是个空操作 ——
     *   表现是"重连流程完整跑完、TCP 也重新连上了,但设备再也没注册过",而日志上看不出来。
     *
     * 清掉的东西(与 [unregister] 的收尾口径一致):
     * 注册超时 / 退避重试 / Expires 续约 / 心跳、pendingRegister、未决注销事务、
     * 以及**本次注册会话的版本协商结论**([platformVersion])—— 协商结论只对一次注册会话成立,
     * 新连接要重新在注册过程中得知。
     */
    suspend fun onConnectionLost()
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
    /**
     * Transport 层失败(socket 发送 / 网络不通),**不是**鉴权失败。UI 应展示"网络不通"
     * 而非"用户名密码错误"。
     */
    data class TransportFailed(val reason: String) : RegistrationEvent()
    data class NetworkSwitchedReregister(val newIp: String) : RegistrationEvent()

    /**
     * 心跳连续超时触发的自动重注册。Engine façade 收到后应顺序关闭其他域的活跃流
     * (invite.stopStream / playback.stop / broadcast.stop)。
     * Reg 自己继续 unregister + register 循环。
     */
    data class AutoReregisterTriggered(val reason: String) : RegistrationEvent()
}
