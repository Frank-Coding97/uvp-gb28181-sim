package com.uvp.sim.domain

import com.uvp.sim.observability.ErrorCategory
import com.uvp.sim.observability.SystemLogger
import com.uvp.sim.sip.SipParseException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * P3-3 (Wave 5 PR-OBSERVABILITY) — 验证 [UserErrorMapper.categorize] 的异常 → ErrorCategory 映射。
 *
 * 不能直接 throw `java.net.SocketException`(commonMain 不能 import),用本地 mock subclass
 * 同名类来模拟 categorize 用 simpleName 匹配的行为。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UserErrorMapperCategoryTest {

    @BeforeTest
    fun setup() = SystemLogger.resetForTest()

    @AfterTest
    fun teardown() = SystemLogger.resetForTest()

    // ---------- Mock 异常(simpleName 匹配 JVM/Native 真实类型) ----------

    private class SocketException(message: String) : RuntimeException(message)
    private class SocketTimeoutException(message: String) : RuntimeException(message)
    private class ConnectException(message: String) : RuntimeException(message)
    private class UnknownHostException(message: String) : RuntimeException(message)
    private class ClosedChannelException(message: String) : RuntimeException(message)
    private class IOException(message: String) : RuntimeException(message)
    private class SecurityException(message: String) : RuntimeException(message)
    private class NumberFormatException(message: String) : RuntimeException(message)

    @Test
    fun socketException_is_transient() = runTest {
        assertEquals(
            ErrorCategory.Transient,
            UserErrorMapper.categorize(SocketException("socket closed")),
        )
    }

    @Test
    fun socketTimeoutException_is_transient() = runTest {
        assertEquals(
            ErrorCategory.Transient,
            UserErrorMapper.categorize(SocketTimeoutException("read timeout")),
        )
    }

    @Test
    fun connectException_is_transient() = runTest {
        assertEquals(
            ErrorCategory.Transient,
            UserErrorMapper.categorize(ConnectException("conn refused")),
        )
    }

    @Test
    fun unknownHostException_is_transient() = runTest {
        assertEquals(
            ErrorCategory.Transient,
            UserErrorMapper.categorize(UnknownHostException("no such host")),
        )
    }

    @Test
    fun closedChannelException_is_transient() = runTest {
        assertEquals(
            ErrorCategory.Transient,
            UserErrorMapper.categorize(ClosedChannelException("channel closed")),
        )
    }

    @Test
    fun ioException_is_transient() = runTest {
        assertEquals(
            ErrorCategory.Transient,
            UserErrorMapper.categorize(IOException("IO err")),
        )
    }

    @Test
    fun sipParseException_is_protocolViolation() = runTest {
        assertEquals(
            ErrorCategory.ProtocolViolation,
            UserErrorMapper.categorize(SipParseException("bad SIP header")),
        )
    }

    @Test
    fun illegalArgumentException_is_userInput() = runTest {
        assertEquals(
            ErrorCategory.UserInput,
            UserErrorMapper.categorize(IllegalArgumentException("bad ip format")),
        )
    }

    @Test
    fun numberFormatException_is_userInput() = runTest {
        assertEquals(
            ErrorCategory.UserInput,
            UserErrorMapper.categorize(NumberFormatException("not a port")),
        )
    }

    @Test
    fun securityException_is_permanent() = runTest {
        assertEquals(
            ErrorCategory.Permanent,
            UserErrorMapper.categorize(SecurityException("auth rejected")),
        )
    }

    @Test
    fun illegalStateException_is_internal() = runTest {
        assertEquals(
            ErrorCategory.Internal,
            UserErrorMapper.categorize(IllegalStateException("invariant broken")),
        )
    }

    @Test
    fun nullPointerException_is_internal() = runTest {
        assertEquals(
            ErrorCategory.Internal,
            UserErrorMapper.categorize(NullPointerException("npe")),
        )
    }

    @Test
    fun unknownThrowable_falls_back_to_internal() = runTest {
        assertEquals(
            ErrorCategory.Internal,
            UserErrorMapper.categorize(RuntimeException("hmm")),
        )
    }

    // ---------- map(...) 返回 UserError ----------

    @Test
    fun map_returns_userError_with_message_and_category() = runTest {
        val result = UserErrorMapper.map("send INVITE", SipParseException("missing Via"))
        // message:不含完整 e.message(防内网泄漏),仅 context + type
        assertEquals("send INVITE: SipParseException", result.message)
        assertEquals(ErrorCategory.ProtocolViolation, result.category)
    }

    @Test
    fun userError_toString_returns_message() = runTest {
        val err = UserError("ctx: SomeException", ErrorCategory.Internal)
        assertEquals("ctx: SomeException", err.toString())
    }
}
