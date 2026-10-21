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
    /**
     * MESSAGE / SUBSCRIBE **请求**的 UDP 重发去重器。
     *
     * ⭐ 2026-10-20 客户现场实锤:平台因设备应答慢(忙于抓图)按 UDP 事务超时**重发同一
     * DeviceConfig**(同 Call-ID 同 CSeq,现场发了 3 次)。不去重的后果:业务层
     * (如 [com.uvp.sim.snapshot.SnapshotUploadEngine])每收到一次就起一个新任务,
     * 重复任务抢不到摄像头 → 0 张成功 → 按标准发**空清单**完成通知,把一次正常抓拍
     * 污染成"整批失败"。
     *
     * 键 = `Call-ID + "\u0000" + CSeq 原文`,对"同一事务的重复发送"按 RFC 3261 §17.1.1.1
     * 是同键;不同事务(平台新指令)的 CSeq 必然递增,不会误伤。
     * 只去重 MESSAGE / SUBSCRIBE 请求:INVITE/BYE/INFO 有自己的会话状态机兜底,
     * 且 BYE 重发若被吞会打断正常的拆链重试,不去动。
     */
    private val requestDedup: SipRequestDeduper = SipRequestDeduper(),
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
            SipMethod.MESSAGE, SipMethod.SUBSCRIBE -> {
                // ⛔ 去重必须在路由**之前**:让业务层(ManscdpRouter)完全感知不到重发,
                // 而不是"收到了自己再判一次"——各业务(SnapShotConfig / DeviceUpgrade /
                // 订阅刷新…)各自去判等于把同一份事务知识复制 N 份,必有漏网的。
                // 重复报文按已处理吞掉(仍返回 Handled:method 已知,Engine 无需再问)。
                if (requestDedup.isDuplicate(req)) return RoutingResult.Handled
                manscdp.onIncoming(envelope)
            }
            SipMethod.OPTIONS -> registration.onIncoming(envelope)
            else -> Unit
        }
        return RoutingResult.Handled
    }

    private suspend fun routeResponse(envelope: SipEnvelope): RoutingResult {
        val resp = envelope.message as SipResponse
        val cseqHeader = resp.cseqRaw() ?: return RoutingResult.Skip
        val cseqMethod = cseqHeader.trim().split(Regex("\\s+")).getOrNull(1)?.let { SipMethod.fromString(it) }
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
            SipMethod.NOTIFY -> manscdp.onIncoming(envelope)
            else -> Unit
        }
        return RoutingResult.Handled
    }
}

/**
 * SIP **请求**重发去重器(2026-10-20 客户现场实锚,背景见 [SipMessageRouterImpl.requestDedup])。
 *
 * 键 = `Call-ID \u0000 CSeq 原文`;只记 MESSAGE / SUBSCRIBE(调用方保证)。
 *
 * ⛔ 不是通用 LRU 缓存,容量语义是"**最近 64 个已见事务键**":
 *   - 平台并发在途的事务数远小于 64,正常指令绝不会被误吞;
 *   - 上限只是防泄漏兜底(万一遇到用 CSeq 不递增的破平台,键会单调增长)。
 *
 * ⛔ 没有按"事务超时就过期":UDP 重发窗口是平台的 Timer F(默认 ~16s 量级)与若干重传,
 * 指令级业务(SnapShotConfig / DeviceUpgrade / 订阅)在分钟级不可能出现"同键的新语义"——
 * 平台要重发同一事务,恰恰说明它在等设备的应答,吞掉重复正是设备侧该做的事。
 */
internal class SipRequestDeduper(
    private val capacity: Int = 64,
) {
    private val seen = object : LinkedHashMap<String, Boolean>(16, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>): Boolean =
            size > capacity
    }

    /** 首见返回 false 并记账;重复见返回 true(调用方应吞掉)。 */
    fun isDuplicate(request: SipRequest): Boolean {
        val key = buildString {
            append(request.callId() ?: "")
            append('\u0000')
            append(request.cseqRaw() ?: "")
        }
        synchronized(seen) {
            if (seen.remove(key) != null) {
                seen[key] = true // 重新插入以维持"最近使用"淘汰顺序
                return true
            }
            seen[key] = true
            return false
        }
    }
}
