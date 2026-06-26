package com.uvp.sim.sip

import com.uvp.sim.domain.SimEvent
import com.uvp.sim.network.SipTransport

/**
 * SIP 报文出站抽象(saga #5)。所有 SIP 发送经此,内部自动 emit [SimEvent.MessageSent]。
 *
 * 设计决策(详 wiki/projects/uvp-gb28181-sim/research/2026-06-26-sip-outbox-decisions):
 *  - 不实现 RFC3261 §17 重传(D1)— 模拟器依赖底层 UDP + 对端应用层重试
 *  - 不实现速率限制(D2)— 业务层 delay() 已足够
 *  - 接口形态 `suspend send(): Result<Unit>`(D3)— 失败回 failure,**不** 自动 emit TransportError
 *  - 成功自动 emit `MessageSent`(D4)— 修复 Registration 域 8 处漏发 bug
 */
interface SipOutbox {
    suspend fun send(message: SipMessage): Result<Unit>
}

internal class SipOutboxImpl(
    private val transport: SipTransport,
    private val simEventEmit: suspend (SimEvent) -> Unit,
) : SipOutbox {
    override suspend fun send(message: SipMessage): Result<Unit> = runCatching {
        transport.send(message)
    }.onSuccess {
        simEventEmit(SimEvent.MessageSent(message))
    }
}
