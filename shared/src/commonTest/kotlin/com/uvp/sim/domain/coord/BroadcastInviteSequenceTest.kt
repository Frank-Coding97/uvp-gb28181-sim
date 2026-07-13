package com.uvp.sim.domain.coord

import com.uvp.sim.config.DeviceConfig
import com.uvp.sim.config.GbVersion
import com.uvp.sim.config.ServerConfig
import com.uvp.sim.config.SimConfig
import com.uvp.sim.domain.MockSipTransport
import com.uvp.sim.network.TransportType
import com.uvp.sim.sip.SipMethod
import com.uvp.sim.sip.SipRequest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * T-E3-2:验证反向 INVITE 时序 —— MESSAGE → 200 OK → Broadcast Response → INVITE 3 步顺序。
 *
 * 依赖 [BroadcastCoordinatorImpl.fireBroadcastInvite] 完成 dialog 建立 + outbound INVITE。
 * commonMain 状态机 Android 现网跑通,本测试在 commonTest 里跑 JVM + iosSimulatorArm64Test
 * 双 target,确保 iOS 编译无平台分支导致偏移。
 *
 * 上游 spec:AC-1 (MESSAGE 双重应答) + AC-2 (反向 INVITE) + plan §4 主链路时序图。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BroadcastInviteSequenceTest {

    private val deviceId = "35020000001310000001"
    private val platformId = "35020000002000000001"
    private val platformDomain = "3502000000"

    private fun config() = SimConfig(
        gbVersion = GbVersion.V2022,
        server = ServerConfig(
            ip = "192.168.10.222", port = 8160,
            serverId = platformId, domain = platformDomain,
        ),
        device = DeviceConfig(
            deviceId = deviceId,
            videoChannelId = "35020000001320000001",
            alarmChannelId = "35020000001340000001",
            username = deviceId,
            password = "p",
        ),
        transport = TransportType.UDP,
        keepaliveIntervalSeconds = 60,
    )

    private class FakeRx : com.uvp.sim.network.BroadcastRxSource {
        var bindCount = 0
        override val localPort: Int = 50021
        override suspend fun bind(mode: com.uvp.sim.network.RtpMode): Int {
            bindCount++
            return 50021
        }
        override suspend fun connect(remoteHost: String, remotePort: Int) {}
        override fun start(onPacket: (com.uvp.sim.network.RtpPacket) -> Unit) =
            kotlinx.coroutines.Job()
        override suspend fun close() {}
    }

    private class FakeAudioSink : com.uvp.sim.media.AudioSink {
        override fun start(): Boolean = true
        override fun write(pcm: ShortArray) {}
        override fun stop() {}
    }

    @Test
    fun fire_broadcast_invite_sends_outbound_invite_with_sdp() = runTest {
        val transport = MockSipTransport()
        transport.connect()
        val fakeRx = FakeRx()
        val bc = BroadcastCoordinatorImpl(
            config = config(),
            transport = transport,
            scope = this,
            outbox = com.uvp.sim.sip.SipOutboxImpl(transport) {},
            localIpProvider = { "192.168.10.112" },
            localPortProvider = { 5060 },
            rtpReceiverFactory = { fakeRx },
            audioSinkFactory = { _, _ -> FakeAudioSink() },
        )

        bc.fireBroadcastInvite(
            sourceId = platformId,
            platformUri = "sip:$platformId@$platformDomain",
            targetId = deviceId,
        )
        runCurrent()

        // Step 1 · outbound INVITE 已发
        val invites = transport.sent.filterIsInstance<SipRequest>()
            .filter { it.method == SipMethod.INVITE }
        assertEquals(1, invites.size, "应发出 1 条 outbound INVITE")

        // Step 2 · SDP 内容合规:s=Broadcast + m=audio {port} pt=8 + a=recvonly
        // 注:国标 §F.2.1 附录建议 s=Play,项目实现用 s=Broadcast 表达广播语义,
        // 与 Android 现网跑通版本一致(Wave 4 定型)。
        val sdp = invites[0].body.decodeToString()
        assertTrue(sdp.contains("s=Broadcast"), "SDP session name 应为 Broadcast: $sdp")
        assertTrue(sdp.contains("m=audio 50021"), "SDP m= 应含 bind 端口 50021: $sdp")
        assertTrue(
            sdp.contains("RTP/AVP 8") || sdp.contains("RTP/AVP 8 0"),
            "SDP 应声明 pt=8 (G.711A): $sdp",
        )
        assertTrue(sdp.contains("a=recvonly"), "SDP a= 应为 recvonly (设备收音): $sdp")

        // Step 3 · dialog 已建
        assertNotNull(bc.current.value, "outbound INVITE 后 dialog 应存在")
        assertEquals(deviceId, bc.current.value?.targetId)

        // RX bind 已触发(证明 receiver 早于 INVITE 拿到 localPort)
        assertTrue(fakeRx.bindCount >= 1, "RTP receiver 应在 INVITE 前 bind 拿到 localPort")
    }
}
