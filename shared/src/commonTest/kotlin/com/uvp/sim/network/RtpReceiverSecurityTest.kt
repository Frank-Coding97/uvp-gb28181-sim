package com.uvp.sim.network

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * M-1 (audit §3) — RTP 源验证单元测,验证 [RtpSourceGuard] 的过滤行为。
 *
 * RtpReceiver expect/actual 平台门面在 jvmTest 的 RtpReceiverIntegrationTest 里跑真 socket
 * 集成测;这里只跑 commonMain 内的过滤逻辑,跨平台同款行为靠 guard 共享保证。
 */
class RtpReceiverSecurityTest {

    private fun rtp(ssrc: Long, seq: Int = 0): RtpPacket {
        // 用 parse 构造避免暴露大量内部字段
        val h = ByteArray(12)
        h[0] = 0x80.toByte()
        h[1] = 8
        h[2] = ((seq ushr 8) and 0xFF).toByte()
        h[3] = (seq and 0xFF).toByte()
        h[8] = ((ssrc ushr 24) and 0xFF).toByte()
        h[9] = ((ssrc ushr 16) and 0xFF).toByte()
        h[10] = ((ssrc ushr 8) and 0xFF).toByte()
        h[11] = (ssrc and 0xFF).toByte()
        return RtpPacket.parse(h)!!
    }

    @Test
    fun nullExpectedHostBypassesAllChecksOnFirstPacket() {
        // 旧行为兼容:不配 expectedSource,任何 IP 第一包都放行
        val guard = RtpSourceGuard(expectedSourceHost = null)
        assertTrue(guard.accept(rtp(0xCAFE), "10.0.0.1"))
        assertTrue(guard.accept(rtp(0xCAFE), "192.168.99.1"))  // host 变更不丢
    }

    @Test
    fun whitelistedSourceAccepted() {
        val guard = RtpSourceGuard(expectedSourceHost = "10.0.0.5")
        assertTrue(guard.accept(rtp(0x1234), "10.0.0.5"))
    }

    @Test
    fun nonWhitelistedSourceDropped() {
        val guard = RtpSourceGuard(expectedSourceHost = "10.0.0.5")
        assertFalse(
            guard.accept(rtp(0x1234), "10.0.0.99"),
            "异常源 IP 必须丢弃"
        )
    }

    @Test
    fun ssrcLockedAfterFirstPacket() {
        val guard = RtpSourceGuard(expectedSourceHost = null)
        assertTrue(guard.accept(rtp(0xAAAA), "10.0.0.5"))  // 锁 0xAAAA
        assertFalse(
            guard.accept(rtp(0xBBBB), "10.0.0.5"),
            "SSRC 漂移必须丢弃,即使源 IP 合法"
        )
        assertTrue(
            guard.accept(rtp(0xAAAA), "10.0.0.5"),
            "锁定 SSRC 重现继续放行"
        )
    }

    @Test
    fun ipCheckHappensBeforeSsrcLock() {
        // 异常源 IP 的第一包不应该污染 SSRC 锁 — 后续合法源还要能锁定自己的 SSRC
        val guard = RtpSourceGuard(expectedSourceHost = "10.0.0.5")
        assertFalse(guard.accept(rtp(0xDEAD), "192.168.1.1"))
        assertTrue(
            guard.accept(rtp(0xBEEF), "10.0.0.5"),
            "异常源被丢后 SSRC 锁应该还是空的"
        )
        assertFalse(
            guard.accept(rtp(0xCAFE), "10.0.0.5"),
            "现在 SSRC 已锁 0xBEEF,0xCAFE 应该丢"
        )
    }

    @Test
    fun unknownObservedHostFallsThroughIpCheck() {
        // 平台底层无法拿到对端 IP(observedHost=null)时,不应误判为异常源 — 信任已经
        // 建立的 socket(TCP 主动场景 connect 后对端固定,observedHost 已绑定)。
        val guard = RtpSourceGuard(expectedSourceHost = "10.0.0.5")
        assertTrue(guard.accept(rtp(0x9999), observedHost = null))
    }
}
