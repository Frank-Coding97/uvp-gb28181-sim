package com.uvp.sim.sip

import com.uvp.sim.domain.MockSipTransport
import com.uvp.sim.domain.SimEvent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SipOutboxTest {

    private fun probeRequest(method: SipMethod = SipMethod.MESSAGE): SipRequest =
        SipRequest(
            method = method,
            requestUri = "sip:34020000002000000001@3402000000",
            headers = listOf(SipMessage.Header(SipHeader.CALL_ID, "test-call-id")),
        )

    @Test
    fun send_success_emits_MessageSent_with_same_message() = runTest {
        val transport = MockSipTransport().also { it.connect() }
        val events = mutableListOf<SimEvent>()
        val outbox: SipOutbox = SipOutboxImpl(transport) { ev -> events += ev }
        val msg = probeRequest()

        val result = outbox.send(msg)

        assertTrue(result.isSuccess, "send 应成功")
        assertEquals(1, events.size, "成功路径应 emit 1 个 SimEvent")
        val sent = events.single() as SimEvent.MessageSent
        assertEquals(msg, sent.message, "MessageSent 应携带入参 message")
        assertEquals(1, transport.sent.size, "transport.send 应被调用 1 次")
        assertEquals(msg as SipMessage, transport.sent.single(), "transport 收到的应是入参 msg")
    }

    @Test
    fun send_failure_returns_failure_and_does_not_emit_MessageSent() = runTest {
        // transport 未 connect → MockSipTransport.send 抛 IllegalStateException
        val transport = MockSipTransport()
        val events = mutableListOf<SimEvent>()
        val outbox: SipOutbox = SipOutboxImpl(transport) { ev -> events += ev }
        val msg = probeRequest()

        val result = outbox.send(msg)

        assertTrue(result.isFailure, "未 connect 时 send 应失败")
        assertTrue(events.isEmpty(), "失败路径必须不 emit MessageSent(避免 UI 看到假发送)")
        assertTrue(transport.sent.isEmpty(), "transport.sent 列表应为空")
    }

    @Test
    fun send_payload_passthrough_carries_method_and_headers() = runTest {
        val transport = MockSipTransport().also { it.connect() }
        val events = mutableListOf<SimEvent>()
        val outbox: SipOutbox = SipOutboxImpl(transport) { ev -> events += ev }
        val msg = probeRequest(method = SipMethod.REGISTER)

        outbox.send(msg)

        val sent = (events.single() as SimEvent.MessageSent).message as SipRequest
        assertEquals(SipMethod.REGISTER, sent.method, "MessageSent.message 应保留 method")
        assertEquals("test-call-id", sent.firstHeader(SipHeader.CALL_ID), "header 应原样透传")
    }
}
