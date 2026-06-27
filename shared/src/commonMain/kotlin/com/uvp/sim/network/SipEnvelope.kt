package com.uvp.sim.network

import com.uvp.sim.sip.SipMessage

/**
 * 入栈 SIP 消息封装 — 携带 transport 层真实来源 endpoint(P0-1,Wave 7B 大手术)。
 *
 * 在 [SipTransport.incoming] 上抛 [SipMessage] 的基础上,额外携带 datagram / TCP 连接
 * 拿到的 source IP / port,让 Coordinator 层做基于网络层的来源校验
 * (而不是只看 SIP 头里的 From URI / Via received,后者可任意伪造)。
 *
 * 引用 codex 第二轮 audit:
 *   "SipTransport.incoming 只上抛 SipMessage,没有携带 remote endpoint,
 *    导致上层无法做可靠来源校验"
 *
 * 用法:
 *  - UDP transport:从 `Datagram.address` 拿 host/port
 *  - TCP transport:从 connected socket 的 `remoteAddress` 拿 host/port
 *  - Mock transport(测试):构造时传入,默认走 remote endpoint
 *
 * @property message 已解析的 SIP 请求 / 响应
 * @property sourceIp 实际报文来源 IPv4 / IPv6 字符串(不是 SIP 头里的 IP)
 * @property sourcePort 实际报文来源端口
 * @property transport 报文实际走的传输层
 */
data class SipEnvelope(
    val message: SipMessage,
    val sourceIp: String,
    val sourcePort: Int,
    val transport: TransportType,
)
