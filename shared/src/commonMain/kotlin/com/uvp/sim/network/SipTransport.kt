package com.uvp.sim.network

import com.uvp.sim.sip.SipMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Abstraction over the UDP/TCP transport used to send and receive SIP messages.
 *
 * Lifecycle:
 *   1. `connect()` — open the underlying socket. Idempotent.
 *   2. `incoming` — collect this Flow to receive parsed SIP messages wrapped in [SipEnvelope].
 *   3. `send(...)` — emits a SIP message to the configured remote address.
 *   4. `close()` — release the socket. After close, [connect] may be called again.
 *
 * Implementations must be thread-safe / coroutine-safe — the simulator engine
 * may call `send` from any coroutine while `incoming` is being collected.
 *
 * **Wave 7B P0-1**: [incoming] 类型从 `Flow<SipMessage>` 改为 `Flow<SipEnvelope>`,
 * 携带网络层真实来源 IP/port,让上层 Coordinator 做基于 network 的来源校验。
 */
interface SipTransport {
    suspend fun connect()
    suspend fun send(message: SipMessage)
    val incoming: Flow<SipEnvelope>
    suspend fun close()

    /**
     * Local port the underlying socket is bound to.
     * Returns `-1` if not yet connected. Used by the engine to fill Via /
     * Contact headers so the platform can reach back at the right port.
     */
    val localPort: Int

    /**
     * 当前是否有一条**可用**的链路。
     *
     * 给自愈流程用的"别白折腾"判据:重连监管器在退避醒来后先问一句 —— 若链路已经好了
     * (比如操作员在退避期间手动点了注册),就**不要再 close + connect**。重开一次连接在
     * 平台侧等于重新绑定设备,刚注册好的会话会被打断,下一次心跳之前平台都认为设备离线。
     *
     * 默认实现按 [localPort] 判断:能拿到本地端口 = socket 已建立。对 UDP 这就是全部 ——
     * 对端在不在线它本地看不出来(这是 UDP 的固有局限,不是没实现)。TCP 侧要额外要求
     * read loop 还活着,因为"半死连接"(对端 FIN 之后)同样是 `localPort > 0`。
     */
    val isConnected: Boolean get() = localPort > 0

    /**
     * **被动断连**信号(GB/T 28181 §5.2 传输层自愈的观测入口)。
     *
     * 与 [close] 的区别是**谁发起的**:
     *  - [close] = 我方主动(用户注销 / 改配置 / 换网卡)→ 不发此信号,也不需要自愈;
     *  - 本信号 = 对端 FIN / 帧解析失败 / 读错误把长连接打死了 → **必须**自愈,否则设备
     *    从此静默失联,而平台侧只表现为"设备不再心跳",两边都看不出根因。
     *
     * 只有真正维护长连接的实现才需要发事件(TCP)。UDP 无连接可断,保持默认空流 ——
     * 这不是"还没实现":UDP 的 `connect()` 只是绑定 + 记地址,对端不在线时本地写仍然成功,
     * 传输层**没有任何东西可观测**。UDP 侧的失联只能由应用层心跳判定。
     *
     * 语义约定(实现方必须满足):
     *  - **每条连接最多发一次**;连接重建后才可能再发。
     *  - 事件到达时通道可能已经置空(见 [TcpSipTransport] 的同步置空),上层应把它当成
     *    "这条连接已经不可用",而不是"即将不可用"。
     */
    val connectionLost: Flow<ConnectionLost> get() = emptyFlow()
}

/**
 * 长连接被动终止的**归类**。
 *
 * 分这一刀是为了排障:三种原因对应的现场动作完全不同 —— 对端关闭要查平台为什么重启,
 * 帧错误要查对端发出的报文是否合规(或中间设备污染),读错误多半是本机网络切换。
 *
 * [label] 是**唯一**的人读措辞来源(系统日志 / SIP 事件日志 / 主页横幅都用它)。
 * 别在下游再写一遍:UI 在另一个模块里,两处措辞一定会在某次改文案时只剩一边被改。
 */
enum class ConnectionLostReason(val label: String) {
    /** 读到 EOF —— 对端发了 FIN(平台重启 / 中间设备按 idle 回收)。 */
    PeerClosed("对端关闭"),

    /** 帧解析失败(畸形 Content-Length / 超上限 / header 截断)—— 字节流已无法重新对齐。 */
    FramingError("帧解析失败"),

    /** 读操作抛错 —— 网络切换 / 对端 RST / 底层 fd 失效。 */
    ReadError("读取错误"),
}

/**
 * 一次被动断连的完整描述。
 *
 * [detail] 保留原始异常或解析错误文本(排障用),不要把归类信息挤进字符串 ——
 * UI 与日志按 [reason] 分支,[detail] 只做附加说明。
 */
data class ConnectionLost(
    val reason: ConnectionLostReason,
    val detail: String,
)

/** Information about the remote SIP server. */
data class RemoteEndpoint(
    val host: String,
    val port: Int,
    val transport: TransportType,
    /**
     * M-6 (audit §3) — 服务器 IP 白名单。空 list = 不强制。transport.connect()
     * 时通过 [ServerAllowList.enforce] 校验,不命中拒绝 connect。
     */
    val allowList: List<String> = emptyList(),
)

@kotlinx.serialization.Serializable
enum class TransportType { UDP, TCP }
