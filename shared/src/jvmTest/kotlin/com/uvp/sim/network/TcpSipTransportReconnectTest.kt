package com.uvp.sim.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * GB/T 28181 §5.2 —— [TcpSipTransport] 的**真实 socket** 断连检测 + 重连后不被打回。
 *
 * 为什么必须在真 TCP 上测(而不是 [com.uvp.sim.domain.MockSipTransport]):
 * 这一层要守的东西全是"fd 生命周期"性质的 —— read loop 读到 EOF 才叫断、异步释放 fd 与
 * 新一代 connect 的竞态、半死连接(有 socket 无 readChannel)不能被当成可用。Mock 里没有
 * 世代号、没有 fd、没有真的 EOF,拿它测这几点等于守着一个 mock,线上该坏还是坏。
 *
 * 用 [ServerSocket] 扮演上级 SIP 平台:accept 一条连接、按需 FIN / 灌非法帧,
 * 观察 transport 上报的 [ConnectionLost] 与后续重连行为。
 */
class TcpSipTransportReconnectTest {

    private fun newServer(): ServerSocket =
        ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))

    private fun newTransport(server: ServerSocket): TcpSipTransport =
        TcpSipTransport(RemoteEndpoint("127.0.0.1", server.localPort, TransportType.TCP))

    /** 一条 TCP 分帧合法的 SIP 响应(RFC 3261 §18.3:流式必须带 Content-Length)。 */
    private fun tcpResponse(callId: String): ByteArray = buildString {
        append("SIP/2.0 200 OK\r\n")
        append("Via: SIP/2.0/TCP 127.0.0.1:5060\r\n")
        append("From: <sip:platform@3402000000>;tag=1\r\n")
        append("To: <sip:34020000001320000001@3402000000>;tag=2\r\n")
        append("Call-ID: $callId\r\n")
        append("CSeq: 1 OPTIONS\r\n")
        append("Content-Length: 0\r\n")
        append("\r\n")
    }.toByteArray()

    @Test
    fun `peer FIN is reported once as PeerClosed and marks the connection unusable`() = runBlocking {
        val server = newServer()
        val transport = newTransport(server)
        try {
            val lost = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(5_000) { transport.connectionLost.first() }
            }
            transport.connect()
            val conn = server.accept()
            assertTrue(transport.isConnected, "刚建连应报可用")

            conn.close()

            val loss = lost.await()
            assertEquals(ConnectionLostReason.PeerClosed, loss.reason)
            assertFalse(
                transport.isConnected,
                "被动断连后 isConnected 必须为 false —— 重连监管器正是靠它决定要不要重建;" +
                    "若仍报 true,设备会安静地卡在一条死链上",
            )
        } finally {
            transport.close()
            server.close()
        }
    }

    @Test
    fun `missing Content-Length is reported as FramingError not ReadError`() = runBlocking {
        val server = newServer()
        val transport = newTransport(server)
        try {
            val lost = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(5_000) { transport.connectionLost.first() }
            }
            transport.connect()
            val conn = server.accept()

            // 有 start-line、有 header、有空行,但没有 Content-Length —— 流式下无法对齐帧边界
            val malformed = buildString {
                append("SIP/2.0 200 OK\r\n")
                append("Call-ID: framing\r\n")
                append("CSeq: 1 OPTIONS\r\n")
                append("\r\n")
            }.toByteArray()
            conn.getOutputStream().write(malformed)
            conn.getOutputStream().flush()

            val loss = lost.await()
            assertEquals(
                ConnectionLostReason.FramingError,
                loss.reason,
                "帧级解析失败应单独归类 —— 排障时它与\"对端关了\"是完全不同的两件事",
            )
            assertFalse(transport.isConnected)
        } finally {
            transport.close()
            server.close()
        }
    }

    /**
     * 世代竞态:read loop 被动终止时只能**异步**释放 fd(要走 mutex),而这个异步任务与
     * 调用方的重连是并发的。没有世代号守卫时,释放晚一步执行就会把**刚建好的新连接**关掉 ——
     * 现象是"重连看起来成功了,但立刻又断",而且每轮重建都复现,极难从日志看出来。
     *
     * **怎么让它确定性复现**(靠 sleep 撞运气是不行的:那个释放通常在几毫秒内就自己跑完了,
     * 窗口根本打不开):把 transport 的 parentScope 换成一条**被门闩占住的**单线程调度器。
     * read loop 排出来的"异步释放"会乖乖挂在队列里,而重连走的是 IO 调度器、不受门闩影响 ——
     * 于是"新一代已建好、上一代的释放还没跑"这个中间态被稳定地造出来。
     */
    @Test
    fun `reconnect after FIN is not undone by the previous generation's async release`() = runBlocking {
        val server = newServer()
        val gate = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val gatedDispatcher = executor.asCoroutineDispatcher()
        val gatedScope = CoroutineScope(SupervisorJob() + gatedDispatcher)
        // 先占住这条唯一线程,让 ownedScope 上排出来的任务全部挂在队列里
        executor.submit(Runnable { gate.await() })

        val transport = TcpSipTransport(
            RemoteEndpoint("127.0.0.1", server.localPort, TransportType.TCP),
            parentScope = gatedScope,
        )
        try {
            val lost = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(5_000) { transport.connectionLost.first() }
            }
            transport.connect()
            val first = server.accept()

            first.close() // 对端 FIN
            lost.await() // read loop 已发现 EOF:readChannel 同步置空,异步释放已排进 gatedScope
            delay(150) // 让 read loop 把 finally 跑完(排任务的动作在 tryEmit 之后)
            assertFalse(transport.isConnected, "被动断连后应报不可用")

            // ⚠ 此刻上一代的 releaseIfGeneration 仍挂在门闩后面没跑
            transport.connect()
            val second = server.accept()
            assertTrue(transport.isConnected, "新一代刚建好,应报可用")

            // 放行上一代的异步释放 —— 有世代号守卫时它必须什么都不做
            gate.countDown()
            drain(executor)
            delay(150) // 再给 releaseSocketLocked 里 withContext(IoDispatcher) 的收尾留点时间
            drain(executor)

            assertTrue(
                transport.isConnected,
                "新连接被上一代 read loop 的异步释放关掉了(generation 守卫失效)",
            )

            // 新连接必须真的活着:能收到对端消息(若 fd 被旧世代关掉,这里会失败)
            val env = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(5_000) { transport.incoming.first() }
            }
            second.getOutputStream().write(tcpResponse("gen-race"))
            second.getOutputStream().flush()
            assertEquals("gen-race", env.await().message.callId())
            assertTrue(transport.isConnected, "收完一条消息后连接应依然可用")
        } finally {
            gate.countDown()
            gatedScope.cancel()
            gatedDispatcher.close()
            transport.close()
            server.close()
        }
    }

    /** 单线程执行器上的栅栏:等此前提交的任务(含被门闩放行的异步释放)全部跑完。 */
    private fun drain(executor: ExecutorService) {
        executor.submit(Runnable { }).get()
    }

    /**
     * 健康连接上重复 `connect()` 必须是空操作。
     *
     * 这条守卫是重连监管器的"别白折腾"判断的另一半:`AppEngine.connect()` 在用户手点注册时
     * 也会调进来,若每次都重开 socket,平台侧等于重新绑定设备,刚建立的注册会话会被自己打断。
     */
    @Test
    fun `connect is a no-op while the connection is healthy`() = runBlocking {
        val server = newServer()
        val transport = newTransport(server)
        try {
            transport.connect()
            val conn = server.accept()
            val port = transport.localPort
            assertTrue(port > 0, "已建连时 localPort 应是真实本地端口")

            transport.connect()

            assertEquals(port, transport.localPort, "健康连接上重复 connect 不应换 socket")
            server.soTimeout = 500
            assertFailsWith<SocketTimeoutException>("健康连接上重复 connect 不应新开一条连接") {
                server.accept()
            }

            val env = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(5_000) { transport.incoming.first() }
            }
            conn.getOutputStream().write(tcpResponse("idem"))
            conn.getOutputStream().flush()
            assertEquals("idem", env.await().message.callId(), "原连接应依然可用")
        } finally {
            transport.close()
            server.close()
        }
    }
}
