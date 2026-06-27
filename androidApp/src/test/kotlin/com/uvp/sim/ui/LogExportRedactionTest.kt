package com.uvp.sim.ui

import com.uvp.sim.observability.SipHeaderRedactor
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P2-7 — LogExport.android.kt 分享路径脱敏测试。
 *
 * shareText 在 ACTION_SEND 前应调用 SipHeaderRedactor.redact(),兜底脱敏
 * 任何可能从 LogExport 格式化函数遗漏的 Authorization 行,避免敏感凭据
 * 通过系统分享面板泄露到剪贴板 / 邮件 / IM。
 *
 * 注意:shareText 本身是 Android Context 依赖的 actual fun,单元测试无法直接
 * 调用(需要真实 Context)。这里测试的是 SipHeaderRedactor 行为,确保它能正确
 * 脱敏 LogExport 可能产出的各种格式。
 */
class LogExportRedactionTest {

    @Test
    fun redactorHandlesSipListExportFormat() {
        val content = """
            === UVP GB28181 Sim — SIP 日志(列表视图)
            === 导出时间 2026-06-27 14:30:00
            → REGISTER  sip:34020000001320000001@10.0.0.1
            Authorization: Digest username="34020000001310000001", nonce="abc123", response="dead00beef"
            ← 200      OK
        """.trimIndent()

        val redacted = SipHeaderRedactor.redact(content)
        assertTrue(redacted, "Authorization: <redacted>" in redacted)
        assertFalse(redacted, "username=\"34020000001310000001\"" in redacted)
        assertFalse(redacted, "dead00beef" in redacted)
        assertFalse(redacted, "nonce=\"abc123\"" in redacted)
    }

    @Test
    fun redactorHandlesSipFlowExportFormat() {
        val content = """
            === UVP GB28181 Sim — SIP 时序图
            ----- Dialog: abc123@10.0.0.1 -----
            [14:30:00.123] sim → 平台  REGISTER
            REGISTER sip:34020000001320000001@10.0.0.1 SIP/2.0
            From: <sip:34020000001310000001@10.0.0.1>
            Authorization: Digest username="x", nonce="y", response="z"
            CSeq: 1 REGISTER
        """.trimIndent()

        val redacted = SipHeaderRedactor.redact(content)
        assertTrue(redacted, "Authorization: <redacted>" in redacted)
        assertFalse(redacted, "username=\"x\"" in redacted)
        assertFalse(redacted, "nonce=\"y\"" in redacted)
        assertFalse(redacted, "response=\"z\"" in redacted)
        // From / CSeq 应保留
        assertTrue(redacted, "From: <sip:34020000001310000001@10.0.0.1>" in redacted)
        assertTrue(redacted, "CSeq: 1 REGISTER" in redacted)
    }

    @Test
    fun redactorHandlesMultipleAuthHeaders() {
        val content = """
            Authorization: Digest nonce="abc"
            Proxy-Authorization: Digest response="xyz"
            WWW-Authenticate: Digest realm="3402000000", nonce="fff"
            Proxy-Authenticate: Basic realm="proxy"
        """.trimIndent()

        val redacted = SipHeaderRedactor.redact(content)
        assertTrue(redacted, "Authorization: <redacted>" in redacted)
        assertTrue(redacted, "Proxy-Authorization: <redacted>" in redacted)
        assertTrue(redacted, "WWW-Authenticate: <redacted>" in redacted)
        assertTrue(redacted, "Proxy-Authenticate: <redacted>" in redacted)
        assertFalse(redacted, "nonce=\"abc\"" in redacted)
        assertFalse(redacted, "response=\"xyz\"" in redacted)
        assertFalse(redacted, "nonce=\"fff\"" in redacted)
        assertFalse(redacted, "Basic realm=\"proxy\"" in redacted)
    }

    @Test
    fun redactorHandlesSystemLogFormat() {
        val content = """
            === UVP GB28181 Sim — 系统日志
            [14:30:00.123] [I] [Network] SIP 发送 REGISTER
            Authorization: Digest username="foo", response="bar"
            [14:30:00.456] [I] [Network] SIP 接收 401
            WWW-Authenticate: Digest realm="x", nonce="y"
        """.trimIndent()

        val redacted = SipHeaderRedactor.redact(content)
        assertTrue(redacted, "Authorization: <redacted>" in redacted)
        assertTrue(redacted, "WWW-Authenticate: <redacted>" in redacted)
        assertFalse(redacted, "username=\"foo\"" in redacted)
        assertFalse(redacted, "response=\"bar\"" in redacted)
        assertFalse(redacted, "nonce=\"y\"" in redacted)
    }

    @Test
    fun redactorHandlesEmptyContent() {
        val redacted = SipHeaderRedactor.redact("")
        assertTrue(redacted.isEmpty())
    }

    @Test
    fun redactorHandlesNoSensitiveContent() {
        val content = """
            === UVP GB28181 Sim — SIP 日志
            → REGISTER  sip:x@y
            From: <sip:a@b>
            To: <sip:c@d>
            CSeq: 1 REGISTER
        """.trimIndent()

        val redacted = SipHeaderRedactor.redact(content)
        // 应该原样返回(无敏感头)
        assertTrue(redacted, "From: <sip:a@b>" in redacted)
        assertTrue(redacted, "To: <sip:c@d>" in redacted)
        assertTrue(redacted, "CSeq: 1 REGISTER" in redacted)
    }
}
