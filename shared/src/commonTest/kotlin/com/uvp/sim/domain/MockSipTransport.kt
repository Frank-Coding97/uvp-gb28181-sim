package com.uvp.sim.domain

import com.uvp.sim.config.SimConfig
import com.uvp.sim.network.ConnectionLost
import com.uvp.sim.network.ConnectionLostReason
import com.uvp.sim.network.RemoteEndpoint
import com.uvp.sim.network.SipEnvelope
import com.uvp.sim.network.SipTransport
import com.uvp.sim.network.TransportType
import com.uvp.sim.sip.SipMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * In-memory SipTransport — captures sent messages, lets tests inject responses.
 *
 * **Wave 7B P0-1**:incoming 类型改 `Flow<SipEnvelope>`,deliver 时用 remote 构造 envelope。
 *
 * **Wave 7B P0-2**:[deliver] 增加 `sourceIp` 可选覆盖,让来源校验测试能伪造攻击者 IP;
 * 默认 sourceIp = [remote.host],仿真"消息来自当前 RemoteEndpoint(典型 GB28181 平台)"。
 *
 * 构造选项:
 *  - 无参:默认走 RemoteEndpoint("127.0.0.1", 5060)
 *  - [remote]:旧路径,显式给 RemoteEndpoint
 *  - [config]:**P0-2 推荐**,直接从 SimConfig.server 派生 remote — 测试不用手动同步 IP
 */
class MockSipTransport(
    val remote: RemoteEndpoint = RemoteEndpoint("192.168.10.222", 5060, TransportType.UDP)
) : SipTransport {

    /** P0-2 便捷构造:从 [SimConfig.server] 派生 remote(避免测试手动同步 IP 配置)。 */
    constructor(config: SimConfig) : this(
        RemoteEndpoint(
            host = config.server.ip,
            port = config.server.port,
            transport = config.transport,
            allowList = config.server.allowList,
        )
    )

    val sent = mutableListOf<SipMessage>()
    private var connected = false
    /** [connect] 被调了几次(含失败的尝试)—— 自愈测试用它断言"到底重建过没有、重建了几次"。 */
    var connectCalls: Int = 0
        private set

    /**
     * Test helper:让接下来 [failConnectRemaining] 次 [connect] 抛错,仿真"平台还没起来 / 网络仍不通"。
     *
     * 需要它是因为"重连失败继续退避"是本模块最核心的行为之一 —— 只在成功路径上测,
     * 退避逻辑里最容易写错的那半边(失败后 attempt 有没有继续涨、delay 有没有继续翻倍、
     * 会不会把连接层的失败误报成恢复)就完全没被执行过。
     */
    var failConnectRemaining: Int = 0

    /**
     * Test helper:让接下来 [failCloseRemaining] 次 [close] 抛错。
     *
     * 上一代连接往往已经是死 fd,"关不掉"是预期内的正常情况而不是重连失败 ——
     * 若监管器把它当失败就会一直在退避,现象是"明明连得上却迟迟不注册"。
     */
    var failCloseRemaining: Int = 0
    private val _incoming = MutableSharedFlow<SipEnvelope>(replay = 0, extraBufferCapacity = 64)
    override val incoming: Flow<SipEnvelope> = _incoming.asSharedFlow()
    override val localPort: Int = 5060

    /** 跟 [connected] 联动,而不是走基类的 `localPort > 0` —— 否则永远报"已连接"。 */
    override val isConnected: Boolean get() = connected

    override suspend fun connect() {
        connectCalls += 1
        if (failConnectRemaining > 0) {
            failConnectRemaining -= 1
            throw IllegalStateException("MockSipTransport: connect refused (仿真平台不可达)")
        }
        connected = true
    }

    override suspend fun close() {
        if (failCloseRemaining > 0) {
            failCloseRemaining -= 1
            throw IllegalStateException("MockSipTransport: close failed (仿真已失效的 fd)")
        }
        connected = false
    }

    override suspend fun send(message: SipMessage) {
        check(connected) { "MockSipTransport not connected" }
        sent += message
    }

    private val _connectionLost = MutableSharedFlow<ConnectionLost>(extraBufferCapacity = 8)
    override val connectionLost: Flow<ConnectionLost> = _connectionLost.asSharedFlow()

    /**
     * Test helper:仿真一次**被动**断连(对端 FIN / 帧错误 / 读错误)。
     *
     * 同时把 [connected] 置 false —— 真实 TCP 侧被动断连会**同步**置空读写通道,
     * 若测试里只发事件不改状态,[isConnected] 就与真实行为不符,监管器"别白折腾"的
     * 判断(localPort/read loop 判据)会在测试里失效,守住的东西和线上不是一回事。
     */
    suspend fun simulateConnectionLoss(
        reason: ConnectionLostReason = ConnectionLostReason.PeerClosed,
        detail: String = "test peer closed",
    ) {
        connected = false
        _connectionLost.emit(ConnectionLost(reason, detail))
    }

    /**
     * Test helper: inject an incoming message (wraps in SipEnvelope with remote endpoint).
     *
     * @param sourceIp 默认 remote.host,P0-2 / P1-3 测试可传攻击者 IP 校验拒绝路径。
     */
    suspend fun deliver(message: SipMessage, sourceIp: String = remote.host, sourcePort: Int = remote.port) {
        _incoming.emit(
            SipEnvelope(
                message = message,
                sourceIp = sourceIp,
                sourcePort = sourcePort,
                transport = remote.transport,
            )
        )
    }
}
