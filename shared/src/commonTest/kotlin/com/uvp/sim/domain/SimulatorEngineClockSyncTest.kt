package com.uvp.sim.domain

import com.uvp.sim.config.DeviceConfig
import com.uvp.sim.config.GbVersion
import com.uvp.sim.config.ServerConfig
import com.uvp.sim.config.SimConfig
import com.uvp.sim.network.TransportType
import com.uvp.sim.sip.SipHeader
import com.uvp.sim.sip.SipMessage
import com.uvp.sim.sip.SipRequest
import com.uvp.sim.sip.SipResponse
import com.uvp.sim.sip.SipState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import com.uvp.sim.testing.TestEngine

/**
 * M5 §4.15 SIP Date 校时:注册 200 OK 解析 Date 头 + currentLocalIso 跟随。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SimulatorEngineClockSyncTest {

    private fun config() = SimConfig(
        gbVersion = GbVersion.V2022,
        server = ServerConfig(ip = "127.0.0.1", port = 5060,
            serverId = "34020000002000000001", domain = "3402000000"),
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
        request: SipRequest,
        statusCode: Int,
        reasonPhrase: String,
        extraHeaders: List<SipMessage.Header> = emptyList()
    ): SipResponse {
        val baseHeaders = request.headers.filter {
            val k = SipHeader.canonicalize(it.name)
            k == SipHeader.VIA || k == SipHeader.FROM || k == SipHeader.CALL_ID || k == SipHeader.CSEQ
        } + SipMessage.Header(
            SipHeader.TO,
            (request.toHeader() ?: "<sip:u@e>") + ";tag=server-tag"
        )
        return SipResponse(
            statusCode = statusCode,
            reasonPhrase = reasonPhrase,
            headers = baseHeaders + extraHeaders
        )
    }

    @Test fun `200 OK 带合法 RFC1123 Date 头校时成功`() = runTest {
        val transport = MockSipTransport()
        val engine = TestEngine.create(config(), transport, this, localIpProvider = { "192.168.1.50" })
        try {
            transport.connect()
            engine.register()
            testScheduler.runCurrent()
            val req = transport.sent[0] as SipRequest
            val rawDate = "Wed, 18 Jun 2026 07:30:00 GMT"
            val resp = fakeResponse(req, 200, "OK",
                listOf(SipMessage.Header(SipHeader.DATE, rawDate)))
            transport.deliver(resp)
            testScheduler.runCurrent()

            assertTrue(engine.clockOffset.value.isSynced)
            val expected = Instant.parse("2026-06-18T07:30:00Z").toEpochMilliseconds()
            assertEquals(expected, engine.clockOffset.value.platformBaselineMs)
            assertEquals(rawDate, engine.clockOffset.value.rawDateHeader)
            assertEquals(SipState.Registered, engine.state.value)
        } finally { engine.shutdown() }
    }

    @Test fun `200 OK 无 Date 头不校时但注册仍成功`() = runTest {
        val transport = MockSipTransport()
        val engine = TestEngine.create(config(), transport, this, localIpProvider = { "192.168.1.50" })
        try {
            transport.connect()
            engine.register()
            testScheduler.runCurrent()
            val req = transport.sent[0] as SipRequest
            transport.deliver(fakeResponse(req, 200, "OK"))
            testScheduler.runCurrent()

            assertFalse(engine.clockOffset.value.isSynced)
            assertEquals(SipState.Registered, engine.state.value)
        } finally { engine.shutdown() }
    }

    @Test fun `200 OK Date 头乱码不校时仍注册`() = runTest {
        val transport = MockSipTransport()
        val engine = TestEngine.create(config(), transport, this, localIpProvider = { "192.168.1.50" })
        try {
            transport.connect()
            engine.register()
            testScheduler.runCurrent()
            val req = transport.sent[0] as SipRequest
            transport.deliver(fakeResponse(req, 200, "OK",
                listOf(SipMessage.Header(SipHeader.DATE, "not-a-date"))))
            testScheduler.runCurrent()

            assertFalse(engine.clockOffset.value.isSynced)
            assertEquals(SipState.Registered, engine.state.value)
        } finally { engine.shutdown() }
    }

    @Test fun `ISO8601 Date 头也兼容`() = runTest {
        val transport = MockSipTransport()
        val engine = TestEngine.create(config(), transport, this, localIpProvider = { "192.168.1.50" })
        try {
            transport.connect()
            engine.register()
            testScheduler.runCurrent()
            val req = transport.sent[0] as SipRequest
            transport.deliver(fakeResponse(req, 200, "OK",
                listOf(SipMessage.Header(SipHeader.DATE, "2026-06-18T15:30:00+08:00"))))
            testScheduler.runCurrent()

            assertTrue(engine.clockOffset.value.isSynced)
            val expected = Instant.parse("2026-06-18T07:30:00Z").toEpochMilliseconds()
            assertEquals(expected, engine.clockOffset.value.platformBaselineMs)
        } finally { engine.shutdown() }
    }

    @Test fun `未校时 currentLocalIso 跟本地墙钟近似`() = runTest {
        val transport = MockSipTransport()
        val engine = TestEngine.create(config(), transport, this, localIpProvider = { "192.168.1.50" })
        try {
            // 不调 register,clockOffset 保持 Empty
            assertFalse(engine.clockOffset.value.isSynced)
            // currentLocalIso 是 private,通过 adjustedNowMs 间接验证
            val adjusted = engine.clockOffset.value.adjustedNowMs()
            val local = Clock.System.now().toEpochMilliseconds()
            assertTrue(abs(adjusted - local) < 200)
        } finally { engine.shutdown() }
    }
}
