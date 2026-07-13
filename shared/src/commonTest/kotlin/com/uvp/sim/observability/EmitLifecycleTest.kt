package com.uvp.sim.observability

import com.uvp.sim.config.DeviceConfig
import com.uvp.sim.config.GbVersion
import com.uvp.sim.config.ServerConfig
import com.uvp.sim.config.SimConfig
import com.uvp.sim.domain.MockSipTransport
import com.uvp.sim.domain.SimulatorEngine
import com.uvp.sim.network.TransportType
import com.uvp.sim.sip.SipHeader
import com.uvp.sim.sip.SipMessage
import com.uvp.sim.sip.SipRequest
import com.uvp.sim.sip.SipResponse
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import com.uvp.sim.testing.TestEngine

/**
 * T04 — emit 接入验证(LIFECYCLE 路径)。
 *
 * NETWORK 路径(UdpSipTransport bind/sendto)在 jvmTest UdpSipTransportTest 里测,
 * 因为需要真 UDP socket。这里测纯 SIP 状态机驱动的 LIFECYCLE emit。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EmitLifecycleTest {

    @BeforeTest
    fun setup() {
        SystemLogger.resetForTest()
        SessionTracker.resetForTest()
    }

    @AfterTest
    fun teardown() {
        SystemLogger.resetForTest()
        SessionTracker.resetForTest()
    }

    private fun config() = SimConfig(
        gbVersion = GbVersion.V2022,
        server = ServerConfig(
            ip = "127.0.0.1", port = 5060,
            serverId = "34020000002000000001", domain = "3402000000"
        ),
        device = DeviceConfig(
            deviceId = "34020000001110000001",
            videoChannelId = "34020000001320000001",
            alarmChannelId = "34020000001340000001",
            username = "34020000001110000001",
            password = "test-password"
        ),
        transport = TransportType.UDP,
        keepaliveIntervalSeconds = 60
    )

    private fun fakeResponse(
        req: SipRequest, statusCode: Int, reasonPhrase: String,
        extraHeaders: List<SipMessage.Header> = emptyList()
    ): SipResponse {
        val baseHeaders = req.headers.filter {
            val k = SipHeader.canonicalize(it.name)
            k == SipHeader.VIA || k == SipHeader.FROM ||
                k == SipHeader.CALL_ID || k == SipHeader.CSEQ
        } + SipMessage.Header(
            SipHeader.TO,
            (req.toHeader() ?: "<sip:u@e>") + ";tag=server-tag"
        )
        return SipResponse(
            statusCode = statusCode,
            reasonPhrase = reasonPhrase,
            headers = baseHeaders + extraHeaders
        )
    }

    @Test fun registerStartEmitsLifecycleInfo() = runTest {
        SystemLogger.bindScope(this)
        val transport = MockSipTransport()
        val engine = TestEngine.create(config(), transport, this, localIpProvider = { "192.168.1.50" })
        try {
            transport.connect()
            engine.register()
            testScheduler.runCurrent()

            val lifecycleLogs = SystemLogger.snapshot.filter { it.tag == LogTag.Lifecycle }
            val started = lifecycleLogs.firstOrNull {
                it.level == LogLevel.Info && it.message.contains("开始注册")
            }
            assertNotNull(started, "应该有 [INFO][LIFECYCLE] 开始注册:${lifecycleLogs.map { it.message }}")
            assertTrue("127.0.0.1:5060" in started.message,
                "应包含目标地址,实际:${started.message}")
        } finally {
            engine.shutdown()
            SystemLogger.shutdownForTest()
        }
    }

    @Test fun registerSuccessEmitsLifecycleInfo() = runTest {
        SystemLogger.bindScope(this)
        val transport = MockSipTransport()
        val engine = TestEngine.create(config(), transport, this)
        try {
            transport.connect()
            engine.register()
            testScheduler.runCurrent()
            val firstReq = transport.sent[0] as SipRequest
            transport.deliver(fakeResponse(firstReq, 200, "OK"))
            testScheduler.runCurrent()

            val ok = SystemLogger.snapshot.firstOrNull {
                it.tag == LogTag.Lifecycle && it.level == LogLevel.Info &&
                    it.message.contains("已注册")
            }
            assertNotNull(ok, "should emit 已注册 event, got: ${SystemLogger.snapshot.map { it.message }}")
        } finally {
            engine.shutdown()
            SystemLogger.shutdownForTest()
        }
    }

    @Test fun registerRejectionEmitsLifecycleWarning() = runTest {
        SystemLogger.bindScope(this)
        val transport = MockSipTransport()
        val engine = TestEngine.create(config(), transport, this)
        try {
            transport.connect()
            engine.register()
            testScheduler.runCurrent()
            val firstReq = transport.sent[0] as SipRequest
            transport.deliver(fakeResponse(firstReq, 403, "Forbidden"))
            testScheduler.runCurrent()

            val reject = SystemLogger.snapshot.firstOrNull {
                it.tag == LogTag.Lifecycle && it.level == LogLevel.Warning &&
                    "403" in it.message
            }
            assertNotNull(reject, "应该有 403 拒绝事件,实际:${SystemLogger.snapshot.map { it.level to it.message }}")
        } finally {
            engine.shutdown()
            SystemLogger.shutdownForTest()
        }
    }

    @Test fun unregisterEmitsLifecycleInfo() = runTest {
        SystemLogger.bindScope(this)
        val transport = MockSipTransport()
        val engine = TestEngine.create(config(), transport, this)
        try {
            transport.connect()
            engine.register()
            testScheduler.runCurrent()
            val firstReq = transport.sent[0] as SipRequest
            transport.deliver(fakeResponse(firstReq, 200, "OK"))
            testScheduler.runCurrent()

            engine.unregister()
            testScheduler.runCurrent()

            val unreg = SystemLogger.snapshot.firstOrNull {
                it.tag == LogTag.Lifecycle && it.message.contains("注销")
            }
            assertNotNull(unreg, "应该有注销事件:${SystemLogger.snapshot.map { it.message }}")
        } finally {
            engine.shutdown()
            SystemLogger.shutdownForTest()
        }
    }
}
