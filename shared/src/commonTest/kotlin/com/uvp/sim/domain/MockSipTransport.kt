package com.uvp.sim.domain

import com.uvp.sim.config.SimConfig
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
    private val _incoming = MutableSharedFlow<SipEnvelope>(replay = 0, extraBufferCapacity = 64)
    override val incoming: Flow<SipEnvelope> = _incoming.asSharedFlow()
    override val localPort: Int = 5060

    override suspend fun connect() { connected = true }
    override suspend fun close() { connected = false }
    override suspend fun send(message: SipMessage) {
        check(connected) { "MockSipTransport not connected" }
        sent += message
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
