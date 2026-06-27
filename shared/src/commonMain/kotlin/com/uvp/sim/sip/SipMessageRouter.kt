package com.uvp.sim.sip

import com.uvp.sim.domain.coord.BroadcastCoordinator
import com.uvp.sim.domain.coord.InviteCoordinator
import com.uvp.sim.domain.coord.ManscdpRouter
import com.uvp.sim.domain.coord.PlaybackCoordinator
import com.uvp.sim.domain.coord.RegistrationCoordinator
import com.uvp.sim.domain.coord.RoutingResult
import com.uvp.sim.network.SipEnvelope

/**
 * SIP 消息中央路由(Wave 4 PR-D / P2-2)。
 *
 * 把原 [com.uvp.sim.domain.SimulatorEngine] 里按 SIP method 大型 switch 的
 * `handleRequest` / `handleResponse` 下放到这里,Engine 真退化成 lifecycle + holder
 * bridge,不再 own method-switch。
 *
 * 路由规则按 SIP method:
 *  - REGISTER  response → RegistrationCoordinator
 *  - INVITE    request  → InviteCoordinator,Skip 后试 PlaybackCoordinator
 *              response → BroadcastCoordinator
 *  - ACK / CANCEL request → InviteCoordinator
 *  - BYE       request → Broadcast → Invite → Playback fallthrough
 *  - INFO      request → Playback → Manscdp fallthrough
 *  - MESSAGE   request → Manscdp
 *              response → RegistrationCoordinator(心跳 ACK 路径)
 *  - SUBSCRIBE request → Manscdp
 *  - OPTIONS   request → Registration
 *  - 其它 → Unit
 *
 * Engine 在 handleIncoming 里只调一次 [route],拿到 Skip/Handled/Error 不再做二次判断。
 *
 * **Wave 7B P0-1**:入参从 `SipMessage` 改为 [SipEnvelope],携带 transport 层来源 endpoint,
 * 传给 Coordinator.onIncoming。
 */
internal interface SipMessageRouter {
    /**
     * 路由一条 SIP 消息封装(请求或响应 + 网络层来源)。
     *
     * 返回值:
     *  - [RoutingResult.Handled]:某个 Coord 已处理(或所有 fallthrough 都 Skip 也算 Handled,
     *    因为 method 已知,Engine 不再尝试别的)
     *  - [RoutingResult.Skip]:不识别的 method(理论上 SipMethod 枚举完备,不会触发)
     */
    suspend fun route(envelope: SipEnvelope): RoutingResult
}

/**
 * 默认实现:5 Coord 引用 + method-switch。原 Engine.handleRequest / handleResponse 的完整
 * 行为搬到这里,Engine 自身的 `when (req.method)` 全删。
 *
 * **注意**:路由完成后的状态同步(syncStateFromRegistration / holders.state 写入 InCall 等)
 * 仍由 caller(Engine)负责;本路由只关心"消息送达对应 Coord",状态变更走 bridge / Coord
 * 内部 stateflow。
 *
 * **Wave 7B P0-1**:入参改 [SipEnvelope],内部解包后按原逻辑 dispatch。
 */
internal class SipMessageRouterImpl(
    private val registration: RegistrationCoordinator,
    private val invite: InviteCoordinator,
    private val broadcast: BroadcastCoordinator,
    private val playback: PlaybackCoordinator,
    private val manscdp: ManscdpRouter,
    /** REGISTER / MESSAGE 200 应答后,Caller 同步 holders.state(避免 bridge 协程时序滞后)。 */
    private val onRegistrationStateChanged: () -> Unit = {},
    /**
     * MESSAGE 2xx 心跳 ACK 抵达后 emit HeartbeatAcknowledged 事件。
     * Engine 端原本在 handleResponse 里 inline emit,迁到 router 后用 callback 注入避免
     * router 直接拿 holders.events 写,污染 SipMessageRouter 单一职责。
     */
    private val onMessage2xxAck: suspend () -> Unit = {},
) : SipMessageRouter {

    override suspend fun route(envelope: SipEnvelope): RoutingResult = when (envelope.message) {
        is SipRequest -> routeRequest(envelope)
        is SipResponse -> routeResponse(envelope)
    }

    private suspend fun routeRequest(envelope: SipEnvelope): RoutingResult {
        val req = envelope.message as SipRequest
        val skip = RoutingResult.Skip
        when (req.method) {
            SipMethod.INVITE -> if (invite.onIncoming(envelope) == skip) playback.onIncoming(envelope)
            SipMethod.ACK, SipMethod.CANCEL -> invite.onIncoming(envelope)
            SipMethod.BYE -> {
                if (broadcast.onIncoming(envelope) == skip &&
                    invite.onIncoming(envelope) == skip
                ) {
                    playback.onIncoming(envelope)
                }
            }
            SipMethod.INFO -> if (playback.onIncoming(envelope) == skip) manscdp.onIncoming(envelope)
            SipMethod.MESSAGE, SipMethod.SUBSCRIBE -> manscdp.onIncoming(envelope)
            SipMethod.OPTIONS -> registration.onIncoming(envelope)
            else -> Unit
        }
        return RoutingResult.Handled
    }

    private suspend fun routeResponse(envelope: SipEnvelope): RoutingResult {
        val resp = envelope.message as SipResponse
        val cseqHeader = resp.cseqRaw() ?: return RoutingResult.Skip
        val cseqMethod = cseqHeader.split(" ").getOrNull(1)?.let { SipMethod.fromString(it) }
            ?: return RoutingResult.Skip
        when (cseqMethod) {
            SipMethod.REGISTER -> {
                registration.onIncoming(envelope)
                onRegistrationStateChanged()
            }
            SipMethod.INVITE -> broadcast.onIncoming(envelope)
            SipMethod.MESSAGE -> if (resp.statusCode in 200..299) {
                registration.onIncoming(envelope)
                onMessage2xxAck()
            }
            else -> Unit
        }
        return RoutingResult.Handled
    }
}
