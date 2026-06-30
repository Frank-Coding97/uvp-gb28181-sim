package com.uvp.sim.domain.coord

import com.uvp.sim.config.CatalogNode
import com.uvp.sim.config.SimConfig
import com.uvp.sim.domain.SimEvent
import com.uvp.sim.sip.SipBuilders
import com.uvp.sim.sip.SipOutbox
import com.uvp.sim.sip.SipState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * PR1 T1.2 RED→GREEN — [InviteDialogHandler] ACK watchdog 契约。
 *
 * 本 task 只迁 watchdog + 字段骨架,verify/ACK/CANCEL/BYE 在 T1.3/T1.4 测试覆盖。
 */
class InviteDialogHandlerTest {

    /** 短超时让 runTest 快速推进虚拟时间。 */
    private val testAckTimeoutMs = 1_000L

    @Test
    fun installAckWatchdog_fires_onTimeout_after_timeout() = runTest {
        val shared = FakeSharedState(this)
        val handler = InviteDialogHandler(shared, ackTimeoutMs = testAckTimeoutMs)
        val fired = CompletableDeferred<String>()

        handler.installAckWatchdog("cid-1") { cid -> fired.complete(cid) }
        assertEquals("cid-1", handler.awaitingAckCallId)

        advanceTimeBy(testAckTimeoutMs + 100)
        assertEquals("cid-1", fired.await())
        assertNull(handler.awaitingAckCallId, "timeout 后 awaitingAckCallId 应清空")
    }

    @Test
    fun cancelAckWatchdogIfMatches_prevents_timeout_callback() = runTest {
        val shared = FakeSharedState(this)
        val handler = InviteDialogHandler(shared, ackTimeoutMs = testAckTimeoutMs)
        var fired = false

        handler.installAckWatchdog("cid-2") { fired = true }
        val matched = handler.cancelAckWatchdogIfMatches("cid-2")

        assertTrue(matched, "cid 匹配应返回 true")
        assertNull(handler.awaitingAckCallId)

        advanceTimeBy(testAckTimeoutMs + 100)
        assertFalse(fired, "已 cancel 的 watchdog 不应 fire")
    }

    @Test
    fun cancelAckWatchdogIfMatches_returns_false_when_cid_mismatch() = runTest {
        val shared = FakeSharedState(this)
        val handler = InviteDialogHandler(shared, ackTimeoutMs = testAckTimeoutMs)

        handler.installAckWatchdog("cid-3") { /* noop */ }
        val matched = handler.cancelAckWatchdogIfMatches("cid-other")

        assertFalse(matched, "cid 不匹配应返回 false")
        assertEquals("cid-3", handler.awaitingAckCallId, "不匹配时不应清状态")

        handler.cancelAckWatchdog()  // 清理避免 runTest 阻塞
    }

    @Test
    fun installAckWatchdog_overwrites_previous_watchdog() = runTest {
        val shared = FakeSharedState(this)
        val handler = InviteDialogHandler(shared, ackTimeoutMs = testAckTimeoutMs)
        var firstFired = false
        var secondFired = false

        handler.installAckWatchdog("cid-a") { firstFired = true }
        handler.installAckWatchdog("cid-b") { secondFired = true }

        assertEquals("cid-b", handler.awaitingAckCallId, "新 watchdog 应覆盖旧 cid")

        advanceTimeBy(testAckTimeoutMs + 100)
        assertFalse(firstFired, "旧 watchdog 应被 cancel,不该 fire")
        assertTrue(secondFired, "新 watchdog 应 fire")
    }

    @Test
    fun cancelAckWatchdog_clears_state_idempotent() = runTest {
        val shared = FakeSharedState(this)
        val handler = InviteDialogHandler(shared, ackTimeoutMs = testAckTimeoutMs)

        handler.installAckWatchdog("cid-x") {}
        handler.cancelAckWatchdog()
        assertNull(handler.awaitingAckCallId)

        // 二次调用 idempotent
        handler.cancelAckWatchdog()
        assertNull(handler.awaitingAckCallId)
    }
}

/**
 * 测试用 [InviteSharedState] stub —— 仅暴露 scope + simEventEmit,其余字段不参与本 task。
 */
private class FakeSharedState(scope: TestScope) : InviteSharedState {
    override val scope = scope
    override val config: SimConfig get() = error("unused in T1.2")
    override val outbox: SipOutbox get() = error("unused in T1.2")
    override val catalogTree: StateFlow<List<CatalogNode>> = MutableStateFlow(emptyList())
    override val localIp: String = "127.0.0.1"
    override val localPort: Int = 5060

    override fun currentActiveStream(): InviteCoordinatorImpl.ActiveStream? = null
    override fun currentSipState(): SipState = SipState.Registered
    override suspend fun simEventEmit(event: SimEvent) { /* swallow */ }
}
