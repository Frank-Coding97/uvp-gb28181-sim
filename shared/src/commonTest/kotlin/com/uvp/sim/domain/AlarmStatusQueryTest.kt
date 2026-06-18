package com.uvp.sim.domain

import com.uvp.sim.config.DeviceConfig
import com.uvp.sim.config.GbVersion
import com.uvp.sim.config.GeoPoint
import com.uvp.sim.config.ServerConfig
import com.uvp.sim.config.SimConfig
import com.uvp.sim.gb28181.AlarmPayload
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
import kotlin.test.assertTrue

/**
 * B2 — SimulatorEngine.handleMessage 路由 AlarmStatusQuery 集成测试
 * (M5 平台兼容性补漏 batch1, 矩阵 3.10).
 *
 * 验证:
 *   - MESSAGE CmdType=AlarmStatus 触发 sendAlarmStatusResponse
 *   - 响应 SN/DeviceID 透传
 *   - 报警状态(isAlarming)联动到 DutyStatus / NotNumber
 *   - GB-2016 / GB-2022 双版本路径
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AlarmStatusQueryTest {

    private fun config(gbVersion: GbVersion = GbVersion.V2022) = SimConfig(
        gbVersion = gbVersion,
        server = ServerConfig(
            ip = "192.168.1.100",
            port = 5060,
            serverId = "34020000002000000001",
            domain = "3402000000"
        ),
        device = DeviceConfig(
            deviceId = "34020000001110000001",
            videoChannelId = "34020000001320000001",
            alarmChannelId = "34020000001340000001",
            username = "34020000001110000001",
            password = "wvp2025!!!"
        ),
        transport = TransportType.UDP,
        keepaliveIntervalSeconds = 60,
        mockPosition = GeoPoint(116.404, 39.915)
    )

    private fun fakeRegister200(req: SipRequest): SipResponse {
        val headers = req.headers.filter {
            val c = SipHeader.canonicalize(it.name)
            c == SipHeader.VIA || c == SipHeader.FROM ||
                c == SipHeader.CALL_ID || c == SipHeader.CSEQ
        } + SipMessage.Header(
            SipHeader.TO,
            (req.toHeader() ?: "<sip:u@e>") + ";tag=server-tag"
        )
        return SipResponse(statusCode = 200, reasonPhrase = "OK", headers = headers)
    }

    private suspend fun registerEngine(
        transport: MockSipTransport,
        engine: SimulatorEngine,
        scope: kotlinx.coroutines.test.TestScope
    ) {
        engine.register()
        scope.testScheduler.runCurrent()
        val regReq = transport.sent.filterIsInstance<SipRequest>()
            .first { it.method == SipMethod.REGISTER }
        transport.deliver(fakeRegister200(regReq))
        scope.testScheduler.runCurrent()
    }

    private fun alarmStatusQuery(sn: String = "1", callId: String = "alms-q@plat"): SipRequest {
        val body = """<?xml version="1.0"?>
<Query>
<CmdType>AlarmStatus</CmdType>
<SN>$sn</SN>
<DeviceID>34020000001110000001</DeviceID>
</Query>""".encodeToByteArray()
        return SipRequest(
            method = SipMethod.MESSAGE,
            requestUri = "sip:34020000001110000001@192.168.1.50:5060",
            headers = listOf(
                SipMessage.Header(SipHeader.VIA,
                    "SIP/2.0/UDP 192.168.1.100:5060;branch=z9hG4bK-alms-$sn"),
                SipMessage.Header(SipHeader.FROM,
                    "<sip:34020000002000000001@3402000000>;tag=plat-$sn"),
                SipMessage.Header(SipHeader.TO,
                    "<sip:34020000001110000001@3402000000>"),
                SipMessage.Header(SipHeader.CALL_ID, callId),
                SipMessage.Header(SipHeader.CSEQ, "1 MESSAGE")
            ),
            body = body
        )
    }

    /** 抓 sim 发出的 AlarmStatus 响应 body(MESSAGE 出栈)。 */
    private fun alarmStatusResponses(transport: MockSipTransport): List<String> =
        transport.sent.filterIsInstance<SipRequest>()
            .filter { it.method == SipMethod.MESSAGE }
            .map { it.body.decodeToString() }
            .filter { it.contains("<CmdType>AlarmStatus</CmdType>") }

    // ---- B2-T1: Query 触发 Response ----
    @Test
    fun b2_t1_queryTriggersResponse() = runTest {
        val transport = MockSipTransport()
        transport.connect()
        val engine = SimulatorEngine(config(), transport, this,
            localIpProvider = { "192.168.1.50" })
        registerEngine(transport, engine, this)
        runCurrent()
        transport.sent.clear()

        transport.deliver(alarmStatusQuery(sn = "1"))
        runCurrent()

        val bodies = alarmStatusResponses(transport)
        assertEquals(1, bodies.size, "应回 1 条 AlarmStatus MESSAGE")
        assertTrue(bodies.first().contains("<CmdType>AlarmStatus</CmdType>"))
        engine.shutdown()
    }

    // ---- B2-T2: SN 透传 ----
    @Test
    fun b2_t2_snPassthrough() = runTest {
        val transport = MockSipTransport()
        transport.connect()
        val engine = SimulatorEngine(config(), transport, this,
            localIpProvider = { "192.168.1.50" })
        registerEngine(transport, engine, this)
        runCurrent()
        transport.sent.clear()

        transport.deliver(alarmStatusQuery(sn = "99"))
        runCurrent()

        val body = alarmStatusResponses(transport).first()
        assertTrue(body.contains("<SN>99</SN>"), "SN 应透传")
        engine.shutdown()
    }

    // ---- B2-T3: DeviceID 顶层 = config.device.deviceId ----
    @Test
    fun b2_t3_topDeviceIdMatchesConfig() = runTest {
        val transport = MockSipTransport()
        transport.connect()
        val engine = SimulatorEngine(config(), transport, this,
            localIpProvider = { "192.168.1.50" })
        registerEngine(transport, engine, this)
        runCurrent()
        transport.sent.clear()

        transport.deliver(alarmStatusQuery(sn = "1"))
        runCurrent()

        val body = alarmStatusResponses(transport).first()
        assertTrue(body.contains("<DeviceID>34020000001110000001</DeviceID>"),
            "顶层 DeviceID 应为设备 ID")
        engine.shutdown()
    }

    // ---- B2-T4: 报警状态联动 — alarming=true → DutyStatus=ALARM ----
    @Test
    fun b2_t4_alarmingStateMapsToAlarm() = runTest {
        val transport = MockSipTransport()
        transport.connect()
        val engine = SimulatorEngine(config(), transport, this,
            localIpProvider = { "192.168.1.50" })
        registerEngine(transport, engine, this)
        runCurrent()
        // 触发报警 -> isAlarming=true
        engine.reportAlarm(AlarmPayload.quickDefault(config()))
        runCurrent()
        transport.sent.clear()

        transport.deliver(alarmStatusQuery(sn = "1"))
        runCurrent()

        val body = alarmStatusResponses(transport).first()
        assertTrue(body.contains("<DutyStatus>ALARM</DutyStatus>"),
            "GB-2022 报警中 → DutyStatus=ALARM, body=$body")
        engine.shutdown()
    }

    // ---- B2-T5: 复位后查询 — alarming=false → DutyStatus=OFFDUTY ----
    @Test
    fun b2_t5_afterResetMapsToOffduty() = runTest {
        val transport = MockSipTransport()
        transport.connect()
        val engine = SimulatorEngine(config(), transport, this,
            localIpProvider = { "192.168.1.50" })
        registerEngine(transport, engine, this)
        runCurrent()

        // 触发再复位
        engine.reportAlarm(AlarmPayload.quickDefault(config()))
        runCurrent()
        engine.localResetAlarm()
        runCurrent()
        transport.sent.clear()

        transport.deliver(alarmStatusQuery(sn = "1"))
        runCurrent()

        val body = alarmStatusResponses(transport).first()
        assertTrue(body.contains("<DutyStatus>OFFDUTY</DutyStatus>"),
            "复位后 → DutyStatus=OFFDUTY, body=$body")
        engine.shutdown()
    }

    // ---- B2-T6: GB-2016 路径 — 扁平 NotNumber ----
    @Test
    fun b2_t6_gb2016UsesNotNumber() = runTest {
        val transport = MockSipTransport()
        transport.connect()
        val engine = SimulatorEngine(config(GbVersion.V2016), transport, this,
            localIpProvider = { "192.168.1.50" })
        registerEngine(transport, engine, this)
        runCurrent()
        transport.sent.clear()

        transport.deliver(alarmStatusQuery(sn = "1"))
        runCurrent()

        val body = alarmStatusResponses(transport).first()
        assertTrue(body.contains("<NotNumber>0</NotNumber>"),
            "GB-2016 路径应有 NotNumber, body=$body")
        assertTrue(!body.contains("<DutyStatus>"),
            "GB-2016 不应含 DutyStatus")
        engine.shutdown()
    }
}
