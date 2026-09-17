package com.uvp.sim.domain

import com.uvp.sim.network.ConnectionLost
import com.uvp.sim.network.ConnectionLostReason
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * GB/T 28181 §5.2 传输层断链自愈 —— 退避策略 + [SipReconnectSupervisor] 行为。
 *
 * 分两层测:
 * 1. [reconnectDelayMs] 是纯函数,整张退避表(含封顶、含大次数不溢出)一行断言钉死。
 *    真等 1+2+4+8 秒去测"退避是不是翻倍"既慢又会因机器负载 flaky,所以策略被刻意抽成了纯函数。
 * 2. 监管器用**直接构造**而不是经 Engine:这一层要验的是"退避序列 / 成功后注册 / 失败继续退避 /
 *    stop 之后收手"。经 Engine 测会被注册域的 3 次退避、心跳定时器混进来,
 *    虚拟时钟一推进就满屏噪声事件,断言反而看不出因果。
 *    Engine 级"断连→作废→重连→真的重发 REGISTER"的闭环另有 [SimulatorEngineReconnectTest]。
 *
 * 监管器只认 [com.uvp.sim.network.SipTransport] 接口,[MockSipTransport] 提供了
 * [MockSipTransport.simulateConnectionLoss] 这条"注入被动断连"的缝,这里用它。
 */
class SipReconnectTest {

    // ---------------------------------------------------------------- 纯函数退避表

    @Test
    fun `backoff doubles from one second and caps at thirty seconds`() {
        val policy = ReconnectPolicy()
        assertEquals(1_000L, policy.delayMsFor(1), "第一拍必须快 —— 现场盯着的就是这一下")
        assertEquals(2_000L, policy.delayMsFor(2))
        assertEquals(4_000L, policy.delayMsFor(3))
        assertEquals(8_000L, policy.delayMsFor(4))
        assertEquals(16_000L, policy.delayMsFor(5))
        // 第 6 次原值 32000,越过上限 → 封顶
        assertEquals(30_000L, policy.delayMsFor(6), "应封顶在 30s")
        assertEquals(30_000L, policy.delayMsFor(7), "封顶后保持恒定")
    }

    /**
     * 次数**不封顶**是要的语义(平台恢复后设备必须能自己醒过来),所以"次数很大"不是异常输入,
     * 而是正常运行几小时后的必然状态。这里钉死它不会在乘法里溢出成负数 ——
     * `coerceAtMost` 拦不住负数,一旦溢出就会变成 `delay(-N)` 立即返回,退避彻底失效、
     * 几十毫秒一次疯狂重连。
     */
    @Test
    fun `large attempt counts saturate instead of overflowing`() {
        val policy = ReconnectPolicy()
        assertEquals(30_000L, policy.delayMsFor(100))
        assertEquals(30_000L, policy.delayMsFor(1_000))
        assertEquals(30_000L, policy.delayMsFor(Int.MAX_VALUE))
    }

    @Test
    fun `attempt zero and negative are treated as the first attempt`() {
        val policy = ReconnectPolicy()
        assertEquals(1_000L, policy.delayMsFor(0))
        assertEquals(1_000L, policy.delayMsFor(-5))
    }

    @Test
    fun `initial delay is clamped into the cap range`() {
        // 初始值比上限还大 → 取上限(否则第一拍就突破 maxDelayMs,封顶形同虚设)
        assertEquals(1_000L, reconnectDelayMs(1, initialDelayMs = 5_000L, maxDelayMs = 1_000L, multiplier = 2))
        // 初始值给 0/负数 → 兜到 1ms,不能出现 delay(0) 的忙等
        assertEquals(1L, reconnectDelayMs(1, initialDelayMs = 0L, maxDelayMs = 30_000L, multiplier = 2))
    }

    @Test
    fun `multiplier of one or less yields a constant delay`() {
        assertEquals(500L, reconnectDelayMs(1, 500L, 30_000L, multiplier = 1))
        assertEquals(500L, reconnectDelayMs(9, 500L, 30_000L, multiplier = 1))
    }

    // ---------------------------------------------------------------- 监管器行为

    /** 记录监管器的四路输出。`emitEvent` / `onAttemptChanged` 都是同步 lambda,不入 Flow,断言无时序噪声。 */
    private class Fixture(transport: MockSipTransport, scheduler: TestCoroutineScheduler) {
        val losses = mutableListOf<ConnectionLost>()
        val events = mutableListOf<SimEvent>()
        val attempts = mutableListOf<ReconnectAttempt?>()
        var registerCalls = 0
            private set

        // 独立 scope:被测对象的寿命不该等于测试的寿命 —— 否则"stop 之后收手"这条断言
        // 会被 TestScope 自身的取消掩盖,分不清是监管器停了还是整个测试环境没了。
        private val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler))

        val supervisor = SipReconnectSupervisor(
            transport = transport,
            scope = scope,
            onSessionLost = { losses += it },
            register = { registerCalls += 1 },
            emitEvent = { events += it },
            onAttemptChanged = { attempts += it },
        )

        fun start() = supervisor.start()
        fun stop() = supervisor.stop()
        fun cancelScope() = scope.cancel()

        fun scheduledDelays(): List<Long> =
            events.filterIsInstance<SimEvent.ReconnectScheduled>().map { it.delayMs }

        fun succeededCount(): Int = events.count { it is SimEvent.ReconnectSucceeded }
    }

    @Test
    fun `loss tears down the session then reconnects after the first backoff and registers once`() = runTest {
        val transport = MockSipTransport()
        transport.connect()
        assertEquals(1, transport.connectCalls)

        val fx = Fixture(transport, testScheduler)
        fx.start()
        testScheduler.runCurrent()

        transport.simulateConnectionLoss()
        testScheduler.runCurrent()

        assertEquals(1, fx.losses.size, "一次被动断连只应触发一次会话收尾")
        assertEquals(ConnectionLostReason.PeerClosed, fx.losses.single().reason)
        assertEquals(1, fx.attempts.last()?.attempt, "应立刻进入第 1 次重连(不等任何退避才上报状态)")
        assertEquals(1_000L, fx.attempts.last()?.delayMs)
        assertEquals(1, transport.connectCalls, "退避时间没到就不该重建连接")

        testScheduler.advanceTimeBy(1_000L)
        testScheduler.runCurrent()

        assertEquals(2, transport.connectCalls, "退避到期应重建连接")
        assertEquals(1, fx.registerCalls, "连接层恢复后应重新注册,且只注册一次")
        assertNull(fx.attempts.last(), "重连成功后应摘掉\"正在重连\"标记")
        assertEquals(listOf(1_000L), fx.scheduledDelays())
        assertEquals(1, fx.succeededCount())
        assertTrue(
            fx.events.any { it is SimEvent.ConnectionLost },
            "应向 UI 广播断连事件,实际=${fx.events.map { it::class.simpleName }}",
        )

        fx.cancelScope()
    }

    /**
     * "重连失败继续退避"是这套自愈里最容易写错的半边:失败路径若不 `continue`,
     * 要么整个 recover 直接结束(设备再也不重连),要么 attempt 不涨(退避永远 1s、
     * 日志每秒钟刷一条)。这里用虚拟时钟把前四拍全跑完。
     */
    @Test
    fun `failed reconnect keeps doubling the backoff and never registers`() = runTest {
        val transport = MockSipTransport()
        transport.connect()
        transport.failConnectRemaining = 4

        val fx = Fixture(transport, testScheduler)
        fx.start()
        testScheduler.runCurrent()

        transport.simulateConnectionLoss()
        testScheduler.runCurrent()

        val observed = mutableListOf<Long>()
        repeat(4) { i ->
            val attempt = fx.attempts.last()
            assertEquals(i + 1, attempt?.attempt, "第 ${i + 1} 拍的 attempt 应连续递增")
            observed += attempt!!.delayMs
            testScheduler.advanceTimeBy(observed.last())
            testScheduler.runCurrent()
        }

        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L), observed, "失败不应打断翻倍")
        assertEquals(5, transport.connectCalls, "1 次初始建连 + 4 次失败的重建尝试")
        assertEquals(0, fx.registerCalls, "连接层没恢复就绝不能重新注册(那是假活)")
        assertEquals(0, fx.succeededCount())
        assertTrue(fx.attempts.last() != null, "失败期间应一直保留\"正在重连\"标记")

        fx.cancelScope()
    }

    @Test
    fun `stop prevents any further reconnect attempt`() = runTest {
        val transport = MockSipTransport()
        transport.connect()

        val fx = Fixture(transport, testScheduler)
        fx.start()
        testScheduler.runCurrent()

        transport.simulateConnectionLoss()
        testScheduler.runCurrent()
        assertEquals(1, fx.attempts.last()?.attempt)

        fx.stop()
        assertNull(fx.attempts.last(), "stop 应把\"正在重连\"标记清成 null")

        testScheduler.advanceTimeBy(10 * 60_000L)
        testScheduler.runCurrent()
        assertEquals(1, transport.connectCalls, "stop 之后不得再重建连接")

        // 再断一次(用户已注销、链路随后又断)同样不该有反应
        transport.simulateConnectionLoss()
        testScheduler.advanceTimeBy(10 * 60_000L)
        testScheduler.runCurrent()
        assertEquals(1, transport.connectCalls, "stop 之后新来的断连事件应被忽略")

        fx.cancelScope()
    }

    @Test
    fun `start is idempotent and a second call does not double the reconnect`() = runTest {
        val transport = MockSipTransport()
        transport.connect()

        val fx = Fixture(transport, testScheduler)
        fx.start()
        fx.start()
        fx.start()
        testScheduler.runCurrent()

        transport.simulateConnectionLoss()
        testScheduler.runCurrent()
        testScheduler.advanceTimeBy(1_000L)
        testScheduler.runCurrent()

        // 起两条监管协程的话,同一次断连会被两个收集者各处理一遍 → connectCalls 变 4
        assertEquals(2, transport.connectCalls, "重复 start 不应产生第二条监管协程")
        assertEquals(1, fx.registerCalls)

        fx.cancelScope()
    }

    /**
     * 退避期间链路被**别人**修好了(最典型:操作员在"正在重连"时手点了注册 →
     * `AppEngine.connect` 自己把 TCP 接回来了)。这时再 close+connect 一次,对平台而言
     * 等于把刚绑好的设备重新绑一遍,刚建立的注册会话会被自己打断,要等下一次心跳才恢复在线。
     */
    @Test
    fun `reconnect is skipped when another path already restored the link`() = runTest {
        val transport = MockSipTransport()
        transport.connect()

        val fx = Fixture(transport, testScheduler)
        fx.start()
        testScheduler.runCurrent()

        transport.simulateConnectionLoss()
        testScheduler.runCurrent()

        // 模拟退避期间的自救:链路已经可用了
        transport.connect()
        assertEquals(2, transport.connectCalls)

        testScheduler.advanceTimeBy(1_000L)
        testScheduler.runCurrent()

        assertEquals(2, transport.connectCalls, "链路已可用时不得再 close+connect")
        assertEquals(1, fx.registerCalls, "但仍要把控制权交回注册域")
        assertNull(fx.attempts.last())
        assertEquals(1, fx.succeededCount())

        fx.cancelScope()
    }

    /**
     * `close()` 抛错不是重连失败 —— 上一代连接已经是死 fd,关不掉不影响新建连。
     * 这条路径若被当成失败,现场会遇到"明明连得上却一直在退避"。
     */
    @Test
    fun `close failure before rebuild does not abort the reconnect`() = runTest {
        val transport = MockSipTransport()
        transport.connect()
        transport.failCloseRemaining = 1

        val fx = Fixture(transport, testScheduler)
        fx.start()
        testScheduler.runCurrent()

        transport.simulateConnectionLoss()
        testScheduler.runCurrent()
        testScheduler.advanceTimeBy(1_000L)
        testScheduler.runCurrent()

        assertEquals(2, transport.connectCalls, "close 失败不应阻断重建")
        assertEquals(1, fx.registerCalls)
        assertEquals(1, fx.succeededCount())

        fx.cancelScope()
    }
}
