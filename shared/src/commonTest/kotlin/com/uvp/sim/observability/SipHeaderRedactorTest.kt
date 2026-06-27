package com.uvp.sim.observability

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * P2-7 (audit §3) — SipHeaderRedactor 单元测试。
 *
 * 覆盖 Authorization / Proxy-Authorization / WWW-Authenticate / Proxy-Authenticate
 * 整行脱敏 + kv 风格 password/secret/token + 大小写不敏感 + 混合场景。
 */
class SipHeaderRedactorTest {

    @Test fun authorizationDigestFullLineRedacted() {
        val raw = """
            REGISTER sip:34020000001320000001@10.0.0.1 SIP/2.0
            Authorization: Digest username="34020000001310000001", realm="3402000000", nonce="abc123", uri="sip:34020000001320000001@10.0.0.1", response="dead00beef"
            Content-Length: 0
        """.trimIndent()
        val result = SipHeaderRedactor.redact(raw)
        assertTrue("Authorization: <redacted>" in result, "Authorization 整行应被脱敏: $result")
        assertFalse("username=\"34020000001310000001\"" in result, "username 不应泄露: $result")
        assertFalse("dead00beef" in result, "response 不应泄露: $result")
        assertFalse("nonce=\"abc123\"" in result, "nonce 不应泄露: $result")
    }

    @Test fun proxyAuthorizationRedacted() {
        val raw = "Proxy-Authorization: Digest username=\"alice\", response=\"secret123\""
        val result = SipHeaderRedactor.redact(raw)
        assertTrue("Proxy-Authorization: <redacted>" in result, result)
        assertFalse("alice" in result, result)
        assertFalse("secret123" in result, result)
    }

    @Test fun wwwAuthenticateRedacted() {
        val raw = "WWW-Authenticate: Digest realm=\"3402000000\", nonce=\"ffffeeee\", algorithm=MD5"
        val result = SipHeaderRedactor.redact(raw)
        assertTrue("WWW-Authenticate: <redacted>" in result, result)
        assertFalse("ffffeeee" in result, "nonce 不应出现: $result")
        assertFalse("MD5" in result, "algorithm 不应出现: $result")
    }

    @Test fun proxyAuthenticateRedacted() {
        val raw = "Proxy-Authenticate: Basic realm=\"proxy\""
        val result = SipHeaderRedactor.redact(raw)
        assertTrue("Proxy-Authenticate: <redacted>" in result, result)
        assertFalse("Basic" in result, result)
    }

    @Test fun caseInsensitiveMatching() {
        val raw1 = "AUTHORIZATION: Digest creds"
        val result1 = SipHeaderRedactor.redact(raw1)
        assertTrue("AUTHORIZATION: <redacted>" in result1, result1)
        assertFalse("Digest creds" in result1, result1)

        val raw2 = "authorization: Digest creds"
        val result2 = SipHeaderRedactor.redact(raw2)
        assertTrue("authorization: <redacted>" in result2, result2)

        val raw3 = "AuThOrIzAtIoN: Digest creds"
        val result3 = SipHeaderRedactor.redact(raw3)
        assertTrue("AuThOrIzAtIoN: <redacted>" in result3, result3)
    }

    @Test fun kvStylePasswordRedacted() {
        val raw = "login password=hunter2 ok"
        val result = SipHeaderRedactor.redact(raw)
        assertTrue("password=****" in result.lowercase(), result)
        assertFalse("hunter2" in result, result)
    }

    @Test fun kvStyleSecretRedacted() {
        val raw = "config secret: my_secret_key end"
        val result = SipHeaderRedactor.redact(raw)
        assertTrue("secret=****" in result.lowercase(), result)
        assertFalse("my_secret_key" in result, result)
    }

    @Test fun kvStyleTokenRedacted() {
        val raw = "auth token=xyz123 done"
        val result = SipHeaderRedactor.redact(raw)
        assertTrue("token=****" in result.lowercase(), result)
        assertFalse("xyz123" in result, result)
    }

    @Test fun nonSensitiveHeadersUntouched() {
        val raw = """
            From: <sip:34020000001310000001@10.0.0.1>;tag=abc
            To: <sip:34020000001320000001@10.0.0.1>
            CSeq: 1 REGISTER
        """.trimIndent()
        val result = SipHeaderRedactor.redact(raw)
        assertTrue("From: <sip:34020000001310000001@10.0.0.1>" in result, result)
        assertTrue("To: <sip:34020000001320000001@10.0.0.1>" in result, result)
        assertTrue("CSeq: 1 REGISTER" in result, result)
    }

    @Test fun mixedRedaction() {
        val raw = """
            user=alice token=xyz123
            Authorization: Digest nonce="abc" response="xyz"
            From: <sip:foo@bar>
            password=secret123
        """.trimIndent()
        val result = SipHeaderRedactor.redact(raw)
        assertTrue("token=****" in result.lowercase(), result)
        assertTrue("password=****" in result.lowercase(), result)
        assertTrue("Authorization: <redacted>" in result, result)
        assertFalse("xyz123" in result, result)
        assertFalse("secret123" in result, result)
        assertFalse("abc" in result, result)
        assertFalse("xyz" in result, result)
        assertTrue("From: <sip:foo@bar>" in result, "非敏感头应保留: $result")
    }

    @Test fun multiLineAuthHeaders() {
        val raw = """
            Authorization: Digest username="a", nonce="b"
            Content-Length: 0
            Proxy-Authorization: Digest response="c"
        """.trimIndent()
        val result = SipHeaderRedactor.redact(raw)
        assertTrue("Authorization: <redacted>" in result, result)
        assertTrue("Proxy-Authorization: <redacted>" in result, result)
        assertFalse("username=\"a\"" in result, result)
        assertFalse("nonce=\"b\"" in result, result)
        assertFalse("response=\"c\"" in result, result)
    }

    @Test fun redactHeaderSingle_authorization() {
        val result = SipHeaderRedactor.redactHeader("Authorization", "Digest username=\"x\"")
        assertEquals("<redacted>", result)
    }

    @Test fun redactHeaderSingle_proxyAuthorization() {
        val result = SipHeaderRedactor.redactHeader("Proxy-Authorization", "Basic xyz")
        assertEquals("<redacted>", result)
    }

    @Test fun redactHeaderSingle_wwwAuthenticate() {
        val result = SipHeaderRedactor.redactHeader("WWW-Authenticate", "Digest realm=\"x\"")
        assertEquals("<redacted>", result)
    }

    @Test fun redactHeaderSingle_proxyAuthenticate() {
        val result = SipHeaderRedactor.redactHeader("Proxy-Authenticate", "Digest nonce=\"y\"")
        assertEquals("<redacted>", result)
    }

    @Test fun redactHeaderSingle_caseInsensitive() {
        assertEquals("<redacted>", SipHeaderRedactor.redactHeader("AUTHORIZATION", "x"))
        assertEquals("<redacted>", SipHeaderRedactor.redactHeader("authorization", "x"))
        assertEquals("<redacted>", SipHeaderRedactor.redactHeader("AuThOrIzAtIoN", "x"))
    }

    @Test fun redactHeaderSingle_nonSensitive() {
        assertEquals("value123", SipHeaderRedactor.redactHeader("From", "value123"))
        assertEquals("<sip:x@y>", SipHeaderRedactor.redactHeader("To", "<sip:x@y>"))
        assertEquals("1 REGISTER", SipHeaderRedactor.redactHeader("CSeq", "1 REGISTER"))
    }

    @Test fun emptyInput() {
        assertEquals("", SipHeaderRedactor.redact(""))
        assertEquals("value", SipHeaderRedactor.redactHeader("From", "value"))
    }

    @Test fun noSensitiveContent() {
        val raw = "From: <sip:a@b>\nTo: <sip:c@d>\nCSeq: 1 INVITE\n"
        val result = SipHeaderRedactor.redact(raw)
        assertEquals(raw, result, "无敏感内容应原样返回")
    }
}
