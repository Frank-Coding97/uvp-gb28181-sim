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

    @Test
    fun isContentLengthHeaderLine_recognizes_full_and_compact_forms() {
        // cross-review R1 #1 followup:TCP 分帧过去只硬匹配 "content-length:",
        // 漏了 SIP 紧凑头 `l:`(RFC 3261 §7.3.3),用紧凑头的合规消息 body 不被读取 → 流错位。
        assertTrue(TcpSipTransport.isContentLengthHeaderLine("Content-Length: 128"))
        assertTrue(TcpSipTransport.isContentLengthHeaderLine("content-length: 128"))
        assertTrue(TcpSipTransport.isContentLengthHeaderLine("Content-Length : 128"))
        assertTrue(TcpSipTransport.isContentLengthHeaderLine("l: 128"), "紧凑头 l: 必须被识别")
        assertTrue(TcpSipTransport.isContentLengthHeaderLine("L: 128"), "紧凑头大小写不敏感")
        // 不能误命中其它头
        assertTrue(!TcpSipTransport.isContentLengthHeaderLine("Contact: <sip:x@y>"))
        assertTrue(!TcpSipTransport.isContentLengthHeaderLine("Call-ID: abc"))
        assertTrue(!TcpSipTransport.isContentLengthHeaderLine("no-colon-line"))
    }

    @Test
    fun detectContentLength_picks_full_and_compact_headers() {
        // 正常 header 列表:取到 Content-Length / 紧凑头 l: 的值
        assertEquals(42, TcpSipTransport.detectContentLength(listOf("Via: x", "Content-Length: 42")))
        assertEquals(42, TcpSipTransport.detectContentLength(listOf("Via: x", "l: 42")))
        assertEquals(0, TcpSipTransport.detectContentLength(listOf("Via: x", "Call-ID: y")))
    }

    @Test
    fun detectContentLength_first_value_wins_matching_SipParser() {
        // cross-review R1 #1 根治:SipParser 用 firstOrNull 取第一个 Content-Length。
        // detectContentLength 必须对齐(首值优先),否则分帧读的字节数 != parser 认的 body。
        assertEquals(
            5,
            TcpSipTransport.detectContentLength(listOf("Content-Length: 5", "Content-Length: 5")),
            "重复但值相同 → 取首值 5"
        )
        assertEquals(
            7,
            TcpSipTransport.detectContentLength(listOf("Content-Length: 7", "Subject: x")),
            "首个 Content-Length 即为准"
        )
    }

    @Test
    fun detectContentLength_conflicting_duplicates_throw() {
        // 冲突的重复 Content-Length(如 `Content-Length: 5` 后跟 `l: 10`):分帧与 parser
        // 会分歧致流错位 → fail-closed 抛异常关连接。
        assertFailsWith<SipParseException> {
            TcpSipTransport.detectContentLength(listOf("Content-Length: 5", "l: 10"))
        }
        assertFailsWith<SipParseException> {
            TcpSipTransport.detectContentLength(listOf("l: 10", "Content-Length: 5"))
        }
    }

    @Test
    fun detectContentLength_ignores_folded_continuation_lines() {
        // cross-review R1 #1 折叠根治:RFC 3261 §7.3.1 以 SP/HTAB 开头的续行是上一个
        // header 值的折叠,不是新 header。过去 TCP 分帧逐物理行判断,会把续行 " l: 5"
        // 误当真 Content-Length → 用未声明的长度读 body → 流错位。
        // 这里:Subject 折叠续行恰好长得像 l:/Content-Length:,必须被忽略(返回 0)。
        assertEquals(
            0,
            TcpSipTransport.detectContentLength(listOf("Subject: x", " l: 5")),
            "折叠续行 ' l: 5' 不是真 Content-Length,应忽略"
        )
        assertEquals(
            0,
            TcpSipTransport.detectContentLength(listOf("Subject: x", "\tContent-Length: 5")),
            "TAB 折叠续行也应忽略"
        )
        // 真 Content-Length 仍取到,即便后面跟一个折叠续行
        assertEquals(
            10,
            TcpSipTransport.detectContentLength(listOf("Content-Length: 10", " continuation")),
            "真 Content-Length 头后跟折叠续行,仍取真值"
        )
    }
}
