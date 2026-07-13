package com.uvp.sim.domain

import com.uvp.sim.config.DeviceConfig
import com.uvp.sim.config.GbVersion
import com.uvp.sim.config.GeoPoint
import com.uvp.sim.config.ServerConfig
import com.uvp.sim.config.SimConfig
import com.uvp.sim.network.TransportType
import com.uvp.sim.sip.SipHeader
import com.uvp.sim.sip.SipMessage
import com.uvp.sim.sip.SipMethod
import com.uvp.sim.sip.SipRequest
import com.uvp.sim.sip.SipResponse
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import com.uvp.sim.testing.TestEngine

/**
 * M5 batch2 §7.10 — 通道在线状态切换 + 简化 NOTIFY fan-out 集成测试。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CatalogStatusChangeTest {

    private fun config() = SimConfig(
        gbVersion = GbVersion.V2022,
        server = ServerConfig(
            ip = "192.168.1.100", port = 5060,
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
        keepaliveIntervalSeconds = 60,
        mockPosition = GeoPoint(116.404, 39.915)
    )

    private fun catalogSubscribeRequest(callId: String = "cat-sub@plat"): SipRequest {
        val body = """<?xml version="1.0"?>
<Query><CmdType>Catalog</CmdType><SN>1</SN>
<DeviceID>34020000001110000001</DeviceID></Query>""".encodeToByteArray()
        return SipRequest(
            method = SipMethod.SUBSCRIBE,
            requestUri = "sip:34020000001110000001@192.168.1.50:5060",
            headers = listOf(
                SipMessage.Header(SipHeader.VIA, "SIP/2.0/UDP 192.168.1.100:5060;branch=z9hG4bK-cs1"),
                SipMessage.Header(SipHeader.FROM, "<sip:34020000002000000001@3402000000>;tag=plat-tag"),
                SipMessage.Header(SipHeader.TO, "<sip:34020000001110000001@3402000000>"),
                SipMessage.Header(SipHeader.CALL_ID, callId),
                SipMessage.Header(SipHeader.CSEQ, "1 SUBSCRIBE"),
                SipMessage.Header(SipHeader.EVENT, "presence"),
                SipMessage.Header(SipHeader.EXPIRES, "86400")
            ),
            body = body
        )
    }

    private fun fakeRegister200(req: SipRequest): SipResponse {
        val headers = req.headers.filter {
            val c = SipHeader.canonicalize(it.name)
            c == SipHeader.VIA || c == SipHeader.FROM || c == SipHeader.CALL_ID || c == SipHeader.CSEQ
        } + SipMessage.Header(
            SipHeader.TO,
            (req.toHeader() ?: "<sip:u@e>") + ";tag=server-tag"
        )
        return SipResponse(statusCode = 200, reasonPhrase = "OK", headers = headers)
    }

    private suspend fun registerEngine(transport: MockSipTransport, engine: SimulatorEngine) {
        engine.register()
        val regReq = transport.sent.filterIsInstance<SipRequest>().first { it.method == SipMethod.REGISTER }
        transport.deliver(fakeRegister200(regReq))
    }

    @Test fun toggle_updatesStatusFieldInTree() = runTest {
        val transport = MockSipTransport(config())
        transport.connect()
        val engine = TestEngine.create(config(), transport, this, localIpProvider = { "192.168.1.50" })
        registerEngine(transport, engine)
        runCurrent()

        val targetId = engine.catalogTree.value.first { it.id != it.parentId }.id
        engine.toggleChannelStatus(targetId, online = false)
        runCurrent()

        val node = engine.catalogTree.value.first { it.id == targetId }
        assertEquals("OFF", node.fields["Status"])

        engine.toggleChannelStatus(targetId, online = true)
        runCurrent()
        val node2 = engine.catalogTree.value.first { it.id == targetId }
        assertEquals("ON", node2.fields["Status"])

        engine.shutdown()
    }

    @Test fun toggle_fanoutSimplifiedNotifyToCatalogSubscriber() = runTest {
        val transport = MockSipTransport(config())
        transport.connect()
        val engine = TestEngine.create(config(), transport, this, localIpProvider = { "192.168.1.50" })
        registerEngine(transport, engine)
        runCurrent()

        transport.deliver(catalogSubscribeRequest())
        runCurrent()
        transport.sent.clear()

        val targetId = engine.catalogTree.value.first { it.id != it.parentId }.id
        engine.toggleChannelStatus(targetId, online = false)
        runCurrent()

        val notifies = transport.sent
            .filterIsInstance<SipRequest>()
            .filter { it.method == SipMethod.NOTIFY }
        assertEquals(1, notifies.size, "exactly one status-only NOTIFY")
        val body = notifies.first().body.decodeToString()
        assertTrue(body.contains("<Event>OFF</Event>"))
        assertTrue(body.contains("<Status>OFF</Status>"))
        // 简化包不应有 Manufacturer 等完整字段
        assertFalse(body.contains("Manufacturer"))
        assertFalse(body.contains("Parental"))

        engine.shutdown()
    }

    @Test fun toggle_noSubscribers_updatesTreeButSendsNoPacket() = runTest {
        val transport = MockSipTransport(config())
        transport.connect()
        val engine = TestEngine.create(config(), transport, this, localIpProvider = { "192.168.1.50" })
        registerEngine(transport, engine)
        runCurrent()
        transport.sent.clear()

        val targetId = engine.catalogTree.value.first { it.id != it.parentId }.id
        engine.toggleChannelStatus(targetId, online = false)
        runCurrent()

        val notifies = transport.sent.filterIsInstance<SipRequest>()
            .filter { it.method == SipMethod.NOTIFY }
        assertEquals(0, notifies.size, "no subscriber → no NOTIFY")
        assertEquals("OFF", engine.catalogTree.value.first { it.id == targetId }.fields["Status"])

        engine.shutdown()
    }

    @Test fun toggle_unknownChannelId_noChangeNoPacket() = runTest {
        val transport = MockSipTransport(config())
        transport.connect()
        val engine = TestEngine.create(config(), transport, this, localIpProvider = { "192.168.1.50" })
        registerEngine(transport, engine)
        runCurrent()

        transport.deliver(catalogSubscribeRequest())
        runCurrent()
        transport.sent.clear()

        val before = engine.catalogTree.value
        engine.toggleChannelStatus("DOES-NOT-EXIST-999", online = false)
        runCurrent()
        assertEquals(before, engine.catalogTree.value, "tree unchanged")

        val notifies = transport.sent.filterIsInstance<SipRequest>()
            .filter { it.method == SipMethod.NOTIFY }
        assertEquals(0, notifies.size, "unknown channel → no NOTIFY")

        engine.shutdown()
    }

    @Test fun toggle_sameStatus_noNotifySpam() = runTest {
        val transport = MockSipTransport(config())
        transport.connect()
        val engine = TestEngine.create(config(), transport, this, localIpProvider = { "192.168.1.50" })
        registerEngine(transport, engine)
        runCurrent()
        transport.deliver(catalogSubscribeRequest())
        runCurrent()
        transport.sent.clear()

        val targetId = engine.catalogTree.value.first { it.id != it.parentId }.id
        // 默认 ON,再发 ON 不应发包
        engine.toggleChannelStatus(targetId, online = true)
        runCurrent()
        val notifies = transport.sent.filterIsInstance<SipRequest>()
            .filter { it.method == SipMethod.NOTIFY }
        assertEquals(0, notifies.size, "状态相同应不发简化 NOTIFY")

        engine.shutdown()
    }
}
