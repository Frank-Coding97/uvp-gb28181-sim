package com.uvp.sim.network

import com.uvp.sim.sip.SipParseException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * H-2 (PR-SEC-1):TCP Content-Length 上限单测。
 *
 * 不需要真实 TCP socket — 直接打 [TcpSipTransport.parseContentLengthOrThrow]。
 */
class TcpSipTransportTest {

    @Test
    fun contentLength_within_limit_ok() {
        // 1 KB 在 64 KiB 上限内,正常返回
        val cl = TcpSipTransport.parseContentLengthOrThrow("Content-Length: 1024")
        assertEquals(1024, cl)
    }

    @Test
    fun contentLength_at_max_ok() {
        val cl = TcpSipTransport.parseContentLengthOrThrow(
            "Content-Length: ${TcpSipTransport.MAX_SIP_BODY_BYTES}"
        )
        assertEquals(TcpSipTransport.MAX_SIP_BODY_BYTES, cl)
    }

    @Test
    fun contentLength_2gb_attack_throws() {
        // 攻击者发 INT_MAX → 一次性 alloc 2 GiB → OOM crash
        val ex = assertFailsWith<SipParseException> {
            TcpSipTransport.parseContentLengthOrThrow("Content-Length: 2147483647")
        }
        assertTrue(
            ex.message?.contains("exceeds max") == true,
            "异常 message 应说明超上限,实际: ${ex.message}"
        )
    }

    @Test
    fun contentLength_above_max_throws() {
        val tooBig = TcpSipTransport.MAX_SIP_BODY_BYTES + 1
        assertFailsWith<SipParseException> {
            TcpSipTransport.parseContentLengthOrThrow("Content-Length: $tooBig")
        }
    }

    @Test
    fun contentLength_negative_throws() {
        assertFailsWith<SipParseException> {
            TcpSipTransport.parseContentLengthOrThrow("Content-Length: -1")
        }
    }

    @Test
    fun contentLength_zero_ok() {
        // SIP 心跳 / ACK 类无 body,Content-Length: 0 必须放行
        val cl = TcpSipTransport.parseContentLengthOrThrow("Content-Length: 0")
        assertEquals(0, cl)
    }

    @Test
    fun contentLength_malformed_throws() {
        // cross-review R1 #1:非数字 Content-Length 头存在但值非法,过去 `toIntOrNull() ?: 0`
        // 静默当 0 → body 不被读取,TCP SIP 流错位(下一个 read 把 payload 当新 start-line)。
        // fail-closed:畸形 Content-Length 视为攻击/损坏,抛 SipParseException 关连接。
        assertFailsWith<SipParseException> {
            TcpSipTransport.parseContentLengthOrThrow("Content-Length: abc")
        }
    }
}
