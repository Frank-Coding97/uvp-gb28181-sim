package com.uvp.sim.domain

import com.uvp.sim.config.DeviceConfig
import com.uvp.sim.config.GbVersion
import com.uvp.sim.config.ServerConfig
import com.uvp.sim.config.SimConfig
import com.uvp.sim.network.TransportType
import com.uvp.sim.recording.NoopRecordingService
import com.uvp.sim.recording.RecordSource
import com.uvp.sim.recording.RecordType
import com.uvp.sim.recording.RecordingFile
import com.uvp.sim.recording.RecordingService
import com.uvp.sim.recording.RecordingState
import com.uvp.sim.sip.PlaybackOffer
import com.uvp.sim.sip.SipHeader
import com.uvp.sim.sip.SipMessage
import com.uvp.sim.sip.SipMethod
import com.uvp.sim.sip.SipRequest
import com.uvp.sim.sip.SipResponse
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.uvp.sim.testing.TestEngine

@OptIn(ExperimentalCoroutinesApi::class)
class SimulatorEngineRecordingTest {

    private fun cfg() = SimConfig(
        gbVersion = GbVersion.V2022,
        server = ServerConfig(
            ip = "127.0.0.1", port = 5060,
            serverId = "34020000002000000001",
            domain = "3402000000"
        ),
        device = DeviceConfig(
            deviceId = "34020000001110000001",
            videoChannelId = "34020000001320000001",
            alarmChannelId = "34020000001340000001",
            username = "u", password = "p"
        ),
        transport = TransportType.UDP,
        keepaliveIntervalSeconds = 60
    )

    private class FakeRecordingService(
        initialFiles: List<RecordingFile> = emptyList()
    ) : RecordingService {
        val starts = mutableListOf<Pair<RecordSource, String>>()
        var stops = 0
        override val state = MutableStateFlow<RecordingState>(RecordingState.Idle)
        override val files: StateFlow<List<RecordingFile>> = MutableStateFlow(initialFiles)
        override suspend fun start(source: RecordSource, channelId: String): Result<Unit> {
            starts += source to channelId
            return Result.success(Unit)
        }
        override suspend fun stop(): Result<RecordingFile?> { stops += 1; return Result.success(null) }
        override suspend fun load() = Unit
        override suspend fun delete(id: String): Result<Unit> = Result.success(Unit)
    }

    private fun deviceControlMessage(recordCmd: String): SipRequest {
        val xml = """<?xml version="1.0"?>
<Control>
<CmdType>DeviceControl</CmdType>
<SN>17</SN>
<DeviceID>34020000001320000001</DeviceID>
<RecordCmd>$recordCmd</RecordCmd>
</Control>"""
        return SipRequest(
            method = SipMethod.MESSAGE,
            requestUri = "sip:34020000001110000001@3402000000",
            headers = listOf(
                SipMessage.Header(SipHeader.VIA, "SIP/2.0/UDP 127.0.0.1:5060;branch=z9hG4bKtest"),
                SipMessage.Header(SipHeader.FROM, "<sip:34020000002000000001@3402000000>;tag=ftag"),
                SipMessage.Header(SipHeader.TO, "<sip:34020000001110000001@3402000000>"),
                SipMessage.Header(SipHeader.CALL_ID, "msg-test"),
                SipMessage.Header(SipHeader.CSEQ, "1 MESSAGE"),
                SipMessage.Header("Content-Type", "Application/MANSCDP+xml")
            ),
            body = xml.encodeToByteArray()
        )
    }

    private fun recordInfoMessage(): SipRequest {
        val xml = """<?xml version="1.0"?>
<Query>
<CmdType>RecordInfo</CmdType>
<SN>42</SN>
<DeviceID>34020000001320000001</DeviceID>
<StartTime>2026-06-01T00:00:00</StartTime>
<EndTime>2026-06-30T23:59:59</EndTime>
<Type>all</Type>
<Secrecy>0</Secrecy>
</Query>"""
        return SipRequest(
            method = SipMethod.MESSAGE,
            requestUri = "sip:34020000001110000001@3402000000",
            headers = listOf(
                SipMessage.Header(SipHeader.VIA, "SIP/2.0/UDP 127.0.0.1:5060;branch=z9hG4bKquery"),
                SipMessage.Header(SipHeader.FROM, "<sip:34020000002000000001@3402000000>;tag=qtag"),
                SipMessage.Header(SipHeader.TO, "<sip:34020000001110000001@3402000000>"),
                SipMessage.Header(SipHeader.CALL_ID, "query-test"),
                SipMessage.Header(SipHeader.CSEQ, "1 MESSAGE"),
                SipMessage.Header("Content-Type", "Application/MANSCDP+xml")
            ),
            body = xml.encodeToByteArray()
        )
    }

    private fun playbackInvite(callId: String = "playback-test"): SipRequest {
        val sdp = """v=0
o=server 0 0 IN IP4 192.0.2.10
s=Playback
u=34020000001320000001:0
c=IN IP4 192.0.2.20
t=1718208000 1718208600
m=video 9000 RTP/AVP 96
a=rtpmap:96 PS/90000
a=recvonly
y=0123456789
""".replace("\n", "\r\n")
        return SipRequest(
            method = SipMethod.INVITE,
            requestUri = "sip:34020000001320000001@3402000000",
            headers = listOf(
                SipMessage.Header(SipHeader.VIA, "SIP/2.0/UDP 127.0.0.1:5060;branch=z9hG4bKplayback"),
                SipMessage.Header(SipHeader.FROM, "<sip:34020000002000000001@3402000000>;tag=stag"),
                SipMessage.Header(SipHeader.TO, "<sip:34020000001320000001@3402000000>"),
                SipMessage.Header(SipHeader.CALL_ID, callId),
                SipMessage.Header(SipHeader.CSEQ, "1 INVITE"),
                SipMessage.Header(SipHeader.CONTACT, "<sip:server@127.0.0.1:5060>"),
                SipMessage.Header("Content-Type", "application/sdp")
            ),
            body = sdp.encodeToByteArray()
        )
    }

    /** Helper:启动 engine + 完成 register,以便 inbound listener 处于活跃。 */
    private suspend fun bringUp(
        transport: MockSipTransport,
        engine: SimulatorEngine,
        scope: kotlinx.coroutines.test.TestScope
    ) {
        transport.connect()
        engine.register()
        scope.testScheduler.runCurrent()
        // 取出 REGISTER,回 200 OK 让 engine 进 Registered
        val reg = transport.sent[0] as SipRequest
        val baseHeaders = reg.headers.filter {
            val k = SipHeader.canonicalize(it.name)
            k == SipHeader.VIA || k == SipHeader.FROM ||
                k == SipHeader.CALL_ID || k == SipHeader.CSEQ
        } + SipMessage.Header(
            SipHeader.TO,
            (reg.headers.firstOrNull { SipHeader.canonicalize(it.name) == SipHeader.TO }?.value
                ?: "<sip:u@e>") + ";tag=server-tag"
        )
        transport.deliver(SipResponse(statusCode = 200, reasonPhrase = "OK", headers = baseHeaders))
        scope.testScheduler.runCurrent()
        // 清掉 REGISTER 痕迹,后续断言只看新消息
        transport.sent.clear()
    }

    @Test fun deviceControl_record_callsRecordingServiceStart() = runTest {
        val transport = MockSipTransport(cfg())
        val rec = FakeRecordingService()
        val engine = TestEngine.create(
            cfg(), transport, this, localIpProvider = { "192.168.1.50" },
            recordingService = rec
        )
        try {
            bringUp(transport, engine, this)
            transport.deliver(deviceControlMessage("Record"))
            testScheduler.runCurrent()
            assertEquals(1, rec.starts.size)
            assertEquals(RecordSource.PlatformCmd to "34020000001320000001", rec.starts[0])
            // sent[0] = SIP 200 OK; sent[1] = MANSCDP DeviceControl Response (Result=OK)
            val firstSent = transport.sent.firstOrNull() as? SipResponse
            assertEquals(200, firstSent?.statusCode)
            val response = transport.sent.filterIsInstance<SipRequest>()
                .firstOrNull { it.method == SipMethod.MESSAGE }
            assertNotNull(response, "应有 DeviceControl Response MESSAGE")
            val xml = response.body.decodeToString()
            assertTrue(xml.contains("<CmdType>DeviceControl</CmdType>"))
            assertTrue(xml.contains("<Result>OK</Result>"))
            assertTrue(xml.contains("<SN>17</SN>"))
        } finally {
            engine.shutdown()
        }
    }

    @Test fun deviceControl_stopRecord_callsRecordingServiceStop() = runTest {
        val transport = MockSipTransport(cfg())
        val rec = FakeRecordingService()
        val engine = TestEngine.create(
            cfg(), transport, this, localIpProvider = { "192.168.1.50" },
            recordingService = rec
        )
        try {
            bringUp(transport, engine, this)
            transport.deliver(deviceControlMessage("StopRecord"))
            testScheduler.runCurrent()
            assertEquals(1, rec.stops)
        } finally {
            engine.shutdown()
        }
    }

    @Test fun deviceControl_unknown_isNoop() = runTest {
        val transport = MockSipTransport(cfg())
        val rec = FakeRecordingService()
        val engine = TestEngine.create(
            cfg(), transport, this,
            recordingService = rec
        )
        try {
            bringUp(transport, engine, this)
            transport.deliver(deviceControlMessage("Reboot"))
            testScheduler.runCurrent()
            assertEquals(0, rec.starts.size)
            assertEquals(0, rec.stops)
        } finally {
            engine.shutdown()
        }
    }

    @Test fun recordInfo_zeroFiles_emits200_thenEmptyNotify() = runTest {
        val transport = MockSipTransport(cfg())
        val rec = FakeRecordingService()
        val engine = TestEngine.create(
            cfg(), transport, this,
            recordingService = rec
        )
        try {
            bringUp(transport, engine, this)
            transport.deliver(recordInfoMessage())
            testScheduler.runCurrent()
            assertTrue(transport.sent.size >= 2, "应发 200 + Notify 至少 2 条, 实际=${transport.sent.size}")
            val notify = transport.sent[1] as SipRequest
            assertEquals(SipMethod.MESSAGE, notify.method)
            val xml = notify.body.decodeToString()
            assertTrue(xml.contains("<SumNum>0</SumNum>"))
            assertTrue(xml.contains("<RecordList Num=\"0\""))
        } finally {
            engine.shutdown()
        }
    }

    @Test fun recordInfo_threeFiles_paginatedAndSent() = runTest {
        val files = (0 until 60).map {
            RecordingFile(
                id = "f$it",
                startTimeMs = 1_780_243_200_000L + it * 1000,
                endTimeMs = 1_780_243_200_000L + it * 1000 + 500,
                durationMs = 500,
                channelId = "34020000001320000001",
                filePath = "/r/f$it.mp4",
                sizeBytes = 1024L,
                type = RecordType.Time
            )
        }
        val transport = MockSipTransport(cfg())
        val rec = FakeRecordingService(initialFiles = files)
        val engine = TestEngine.create(
            cfg(), transport, this,
            recordingService = rec
        )
        try {
            bringUp(transport, engine, this)
            transport.deliver(recordInfoMessage())
            testScheduler.runCurrent()
            val notifies = transport.sent.filterIsInstance<SipRequest>().filter { it.method == SipMethod.MESSAGE }
            assertEquals(2, notifies.size)
            assertTrue(notifies[0].body.decodeToString().contains("<SumNum>60</SumNum>"))
            assertTrue(notifies[1].body.decodeToString().contains("<SumNum>60</SumNum>"))
        } finally {
            engine.shutdown()
        }
    }

    @Test fun playbackInvite_noPlaybackBuilder_returns487() = runTest {
        val transport = MockSipTransport(cfg())
        val engine = TestEngine.create(
            cfg(), transport, this, localIpProvider = { "192.168.1.50" },
            recordingService = NoopRecordingService,
            playbackBuilder = null
        )
        try {
            bringUp(transport, engine, this)
            transport.deliver(playbackInvite())
            testScheduler.runCurrent()
            val resp = transport.sent.firstOrNull() as? SipResponse
            assertNotNull(resp)
            assertEquals(487, resp.statusCode)
        } finally {
            engine.shutdown()
        }
    }

    @Test fun playbackInvite_emptyRange_returns487() = runTest {
        val transport = MockSipTransport(cfg())
        val rec = FakeRecordingService(initialFiles = emptyList())
        var built = false
        val builder = object : PlaybackBuilder {
            override suspend fun build(
                offer: PlaybackOffer,
                segments: List<RecordingFile>,
                ssrc: String
            ): PlaybackSession? { built = true; return null }
        }
        val engine = TestEngine.create(
            cfg(), transport, this, localIpProvider = { "192.168.1.50" },
            recordingService = rec,
            playbackBuilder = builder
        )
        try {
            bringUp(transport, engine, this)
            transport.deliver(playbackInvite())
            testScheduler.runCurrent()
            val resp = transport.sent.firstOrNull() as? SipResponse
            assertEquals(487, resp?.statusCode)
            assertTrue(!built, "区间为空时不应进入 builder")
        } finally {
            engine.shutdown()
        }
    }

    @Test fun playbackInvite_hitsRange_calls200WithSdpAnswer() = runTest {
        val matchingFile = RecordingFile(
            id = "a",
            startTimeMs = 1_718_208_000_000L,
            endTimeMs = 1_718_208_300_000L,
            durationMs = 300_000L,
            channelId = "34020000001320000001",
            filePath = "/r/a.mp4",
            sizeBytes = 1024L
        )
        val transport = MockSipTransport(cfg())
        val rec = FakeRecordingService(initialFiles = listOf(matchingFile))
        val session = object : PlaybackSession {
            override val localRtpPort: Int = 12345
            override suspend fun run() = Unit
            override suspend fun cancel() = Unit
        }
        val builder = object : PlaybackBuilder {
            override suspend fun build(
                offer: PlaybackOffer,
                segments: List<RecordingFile>,
                ssrc: String
            ): PlaybackSession {
                assertEquals(1, segments.size)
                return session
            }
        }
        val engine = TestEngine.create(
            cfg(), transport, this, localIpProvider = { "192.168.1.50" },
            recordingService = rec,
            playbackBuilder = builder
        )
        try {
            bringUp(transport, engine, this)
            transport.deliver(playbackInvite())
            testScheduler.runCurrent()
            val resp = transport.sent.firstOrNull() as? SipResponse
            assertEquals(200, resp?.statusCode)
            val sdp = resp?.body?.decodeToString() ?: ""
            assertTrue(sdp.contains("m=video 12345"), "SDP 应含 builder 给的 localRtpPort, sdp=$sdp")
            assertTrue(sdp.contains("s=Playback"))
        } finally {
            engine.shutdown()
        }
    }
}
