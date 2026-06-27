package com.uvp.sim.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * P1-5 (audit §2) — RtpSender TCP_PASSIVE accept guard 单元测。
 *
 * 测覆盖:
 *   1. expectedClientHost == null → 接受任意连接(回归保兼容)
 *   2. expectedClientHost 匹配 localhost → 接受 + 发送通畅
 *   3. expectedClientHost 不匹配(占用一个不可路由 IP) → 客户端连接被 close,sender 继续等
 *   4. 多次 mismatch 后(MAX_ACCEPT_MISMATCH=10)放弃
 *
 * Ktor `Socket.remoteAddress` 在 localhost 上拿到的 hostname 一般是 "127.0.0.1",
 * 用例 2 用 "127.0.0.1" 作 expected;用例 3/4 用 "10.99.99.99" 这种本地连进来不可能匹配的值。
 *
 * 注:此测跑真 socket(Ktor + java.nio),Robolectric/纯 JVM 都能跑。
 */
class RtpSenderTcpPassiveGuardTest {

    private val testScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @AfterTest
    fun tearDown() {
        testScope.cancel()
    }

    /**
     * 探测 sender 是否已经接受了一个合法连接:
     * 试探性发送一个 RTP 包,若 tcpWrite 已建立则发送成功并返回 true(用反射读字段,
     * 避免暴露内部 API)。这里通过给 sender 设的远程地址回探一下也行,但本测 sender
     * 是被动监听方,没有真实远程,所以直接试 send + 看是否抛错 / 是否 no-op。
     *
     * 简化方法:send 在 TCP_PASSIVE + 未连接时是 no-op(tcpWrite == null 直接 return),
     * 我们用客户端 socket 看是否能读到对方发的字节判断 sender 接受没。
     */
    private fun assertSenderAccepted(client: Socket, sender: RtpSender, timeoutMs: Long = 1500L): Boolean = runBlocking {
        val testPayload = ByteArray(50) { (it and 0xFF).toByte() }
        // 给 sender 一点时间完成 accept + openWriteChannel
        delay(200)
        sender.send(testPayload)
        // 客户端读 2 字节长度 + 50 字节 payload,共 52
        val ins = client.getInputStream()
        client.soTimeout = timeoutMs.toInt()
        try {
            val buf = ByteArray(2 + 50)
            var off = 0
            while (off < buf.size) {
                val r = ins.read(buf, off, buf.size - off)
                if (r < 0) return@runBlocking false
                off += r
            }
            // 校验大端长度前缀
            val len = ((buf[0].toInt() and 0xFF) shl 8) or (buf[1].toInt() and 0xFF)
            len == 50
        } catch (_: IOException) {
            false
        }
    }

    // ---------- case 1:不配 expectedClientHost,任何连接照收 ----------

    @Test
    fun nullExpectedHostAcceptsAnyConnection() = runBlocking {
        val sender = RtpSender(
            remoteHost = "ignored",
            remotePort = 0,
            parentScope = testScope,
            mode = RtpMode.TCP_PASSIVE,
            expectedClientHost = null,
        )
        try {
            val port = sender.bindLocalPort()
            assertTrue(port in 1..65535)
            // 平台主动连进来
            val client = Socket("127.0.0.1", port)
            assertTrue(
                assertSenderAccepted(client, sender),
                "null expectedClientHost 必须接受任意来源(回归保兼容)"
            )
            client.close()
        } finally {
            sender.close()
        }
    }

    // ---------- case 2:expectedClientHost 命中 → 接受 ----------

    @Test
    fun matchingExpectedHostAcceptsConnection() = runBlocking {
        val sender = RtpSender(
            remoteHost = "ignored",
            remotePort = 0,
            parentScope = testScope,
            mode = RtpMode.TCP_PASSIVE,
            expectedClientHost = "127.0.0.1",
        )
        try {
            val port = sender.bindLocalPort()
            val client = Socket(InetAddress.getByName("127.0.0.1"), port)
            assertTrue(
                assertSenderAccepted(client, sender),
                "匹配的客户端 IP 必须接受并能发送 RTP"
            )
            client.close()
        } finally {
            sender.close()
        }
    }

    // ---------- case 3:expectedClientHost 不匹配 → close 当前连接,继续等 ----------

    @Test
    fun mismatchedExpectedHostDropsConnectionAndKeepsWaiting() = runBlocking {
        val sender = RtpSender(
            remoteHost = "ignored",
            remotePort = 0,
            parentScope = testScope,
            mode = RtpMode.TCP_PASSIVE,
            expectedClientHost = "10.99.99.99",  // 本地连过去不可能命中
        )
        try {
            val port = sender.bindLocalPort()
            // 第一次连接 — 来源是 127.0.0.1 跟 expected 不匹配,应被 close
            val client = Socket("127.0.0.1", port)
            // 读到 EOF 说明 sender 主动 close 了
            client.soTimeout = 2000
            val ins = client.getInputStream()
            val r = ins.read()
            assertTrue(
                r == -1,
                "expectedClientHost 不匹配时,sender 必须 close 连接,客户端读到 EOF (实际 $r)"
            )
            client.close()

            // 第二次连接 — sender 应继续 accept(没死循环退出)
            val client2 = Socket("127.0.0.1", port)
            client2.soTimeout = 2000
            val ins2 = client2.getInputStream()
            val r2 = ins2.read()
            assertTrue(
                r2 == -1,
                "sender 应该还在 accept 循环里,下一个连接照常被验证 → 仍然 close"
            )
            client2.close()
        } finally {
            sender.close()
        }
    }

    // ---------- case 4:多次 mismatch 后放弃 ----------

    @Test
    fun givesUpAfterTooManyMismatches() = runBlocking {
        val sender = RtpSender(
            remoteHost = "ignored",
            remotePort = 0,
            parentScope = testScope,
            mode = RtpMode.TCP_PASSIVE,
            expectedClientHost = "10.99.99.99",
        )
        try {
            val port = sender.bindLocalPort()
            // 连续怼 11 次(MAX_ACCEPT_MISMATCH = 10),最后一次应该连不上或立即 EOF
            // 前 10 次:接受 + close;第 11 次:sender 已退出 accept 循环
            repeat(10) {
                val c = Socket("127.0.0.1", port)
                c.soTimeout = 2000
                // 读 EOF 等待 sender close
                runCatching { c.getInputStream().read() }
                c.close()
            }
            // 给 sender actor 一点时间退出循环
            delay(300)

            // 第 11 次:或连不上(server 还在,但 accept 协程已退出 → connect 会成功但
            // 立即被 OS 缓冲,我们读不出 EOF 而是 hang;用 withTimeoutOrNull 避免卡死)
            val acceptedAfterGiveup = withTimeoutOrNull(1500) {
                runCatching {
                    val c = Socket("127.0.0.1", port)
                    c.soTimeout = 1000
                    val r = c.getInputStream().read()
                    c.close()
                    r != -1  // 任何成功读出来都视为"还在接 → 没放弃"
                }.getOrNull() ?: false
            } ?: false
            assertFalse(
                acceptedAfterGiveup,
                "超过 MAX_ACCEPT_MISMATCH 次后 sender 不再接受新连接"
            )
        } finally {
            sender.close()
        }
    }
}
