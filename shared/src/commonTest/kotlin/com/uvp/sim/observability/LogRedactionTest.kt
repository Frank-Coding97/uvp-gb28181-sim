package com.uvp.sim.observability

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * M-7 (audit §3) — SystemLogger 敏感字段脱敏。
 *
 * 覆盖 SIP 协议常见敏感头(Authorization / Proxy-Authorization / WWW-Authenticate /
 * Proxy-Authenticate)与 kv 风格 password/secret/token,以及联合场景。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LogRedactionTest {

    @BeforeTest
    fun setup() = SystemLogger.resetForTest()

    @AfterTest
    fun teardown() = SystemLogger.resetForTest()

    /** 同步派生:绕过 actor 串行化,直接 sanitize 看输出,用于纯过滤逻辑断言。 */
    private fun sanitized(raw: String): String = SystemLogger.sanitize(raw)

    @Test fun digestAuthorizationHeaderRedacted() {
        val raw =
            "REGISTER sip:34020000001320000001@10.0.0.1 SIP/2.0\n" +
            "Authorization: Digest username=\"34020000001310000001\"," +
            " realm=\"3402000000\"," +
            " nonce=\"abc123\"," +
            " uri=\"sip:34020000001320000001@10.0.0.1\"," +
            " response=\"dead00beef\"\n" +
            "Content-Length: 0"
        val final = sanitized(raw)
        assertTrue(
            "Authorization: <redacted>" in final,
            "Authorization 整行应被脱敏,实际: $final"
        )
        assertFalse(
            "username=\"34020000001310000001\"" in final,
            "Digest username 不应泄露: $final"
        )
        assertFalse("dead00beef" in final, "Digest response 不应泄露: $final")
        assertFalse("nonce=\"abc123\"" in final, "Digest nonce 不应泄露: $final")
    }

    @Test fun proxyAuthorizationRedacted() {
        val final = sanitized("Proxy-Authorization: Digest username=\"alice\", response=\"secret\"")
        assertTrue("Proxy-Authorization: <redacted>" in final, final)
        assertFalse("alice" in final, final)
        assertFalse("secret" in final, final)
    }

    @Test fun wwwAuthenticateChallengeRedacted() {
        val final = sanitized("WWW-Authenticate: Digest realm=\"3402000000\", nonce=\"ffffeeee\"")
        assertTrue("WWW-Authenticate: <redacted>" in final, final)
        // nonce 是平台下发的挑战值,也不应该出现在调试日志里
        assertFalse("ffffeeee" in final, final)
    }

    @Test fun kvStylePasswordStillRedacted() {
        val final = sanitized("login password=hunter2 ok")
        assertTrue("password=****" in final.lowercase(), final)
        assertFalse("hunter2" in final, final)
    }

    @Test fun caseInsensitiveMatching() {
        val final = sanitized("AUTHORIZATION: Digest creds")
        assertTrue("AUTHORIZATION: <redacted>" in final, final)
        assertFalse("Digest creds" in final, final)
    }

    @Test fun nonSensitiveHeadersUntouched() {
        val final = sanitized("From: <sip:34020000001310000001@10.0.0.1>;tag=abc")
        // From 是路由必要信息,不脱敏 — out-of-scope per M-7
        assertTrue("From: <sip:34020000001310000001@10.0.0.1>" in final, final)
    }

    @Test fun mixedTokenRedaction() {
        val final = sanitized("user=alice token=xyz123 Authorization: Digest meta=z")
        assertTrue("token=****" in final.lowercase(), final)
        assertTrue("Authorization: <redacted>" in final, final)
        assertFalse("xyz123" in final, final)
        assertFalse("meta=z" in final, final)
    }

    /** 端到端验证一次:actor 真把 sanitize 应用到 emit 的 message。 */
    @Test fun endToEndAuthHeaderRedactedAfterActorDispatch() = runTest {
        SystemLogger.bindScope(this)
        SystemLogger.emit(
            LogLevel.Info,
            LogTag.Network,
            "Authorization: Digest nonce=\"xyz\"",
        )
        testScheduler.advanceUntilIdle()
        val msg = SystemLogger.snapshot.last().message
        assertTrue("Authorization: <redacted>" in msg, msg)
        assertFalse("xyz" in msg, msg)
        SystemLogger.shutdownForTest()
    }
}
