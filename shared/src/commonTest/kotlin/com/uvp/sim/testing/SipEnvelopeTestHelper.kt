package com.uvp.sim.testing

import com.uvp.sim.network.SipEnvelope
import com.uvp.sim.network.TransportType
import com.uvp.sim.sip.SipMessage

/**
 * Wave 7B P0-1 测试 helper:把 [SipMessage] 包成 [SipEnvelope] 喂给 Coordinator.onIncoming。
 *
 * 默认 sourceIp/port 模拟 GB28181 平台典型 LAN 上级:192.168.10.222:5060 UDP。
 * 各测试需要伪造来源(P0-2 / P1-3 来源校验测)时,显式传 sourceIp 覆盖。
 */
internal fun SipMessage.asEnvelope(
    sourceIp: String = "192.168.10.222",
    sourcePort: Int = 5060,
    transport: TransportType = TransportType.UDP,
): SipEnvelope = SipEnvelope(
    message = this,
    sourceIp = sourceIp,
    sourcePort = sourcePort,
    transport = transport,
)
