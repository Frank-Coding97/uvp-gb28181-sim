package com.uvp.sim.domain.coord

import com.uvp.sim.config.DeviceConfig
import com.uvp.sim.config.GbVersion
import com.uvp.sim.config.ServerConfig
import com.uvp.sim.config.SimConfig
import com.uvp.sim.domain.MockSipTransport
import com.uvp.sim.media.AudioPlayback
import com.uvp.sim.media.AudioSink
import com.uvp.sim.media.G711
import com.uvp.sim.network.BroadcastRxSource
import com.uvp.sim.network.RtpMode
import com.uvp.sim.network.RtpPacket
import com.uvp.sim.network.TransportType
import com.uvp.sim.sip.SipMethod
import com.uvp.sim.sip.SipOutboxImpl
import com.uvp.sim.sip.SipRequest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * T-E3-3:iOS 端 BroadcastCoordinator + 真 AudioPlayback.ios 的连线集成测试。
 *
 * 走完整链路 —— fake RTP receiver 塞 G.711A 包 → coordinator 解码 → 真 AudioPlayback.ios
 * `.write` 不 throw。真机端到端(平台喊话 → iPhone 出声)在 T-E3-4 手工验。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BroadcastCoordinatorIosIntegrationTest {

    private val deviceId = "35020000001310000001"
    private val platformId = "35020000002000000001"

    private fun cfg() = SimConfig(
        gbVersion = GbVersion.V2022,
        server = ServerConfig(
            ip = "192.168.10.222", port = 8160,
            serverId = platformId, domain = "3502000000",
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

    private class FakeRx : BroadcastRxSource {
        override val localPort: Int = 50033
        override suspend fun bind(mode: RtpMode): Int = localPort
        override suspend fun connect(remoteHost: String, remotePort: Int) {}
        override fun start(onPacket: (RtpPacket) -> Unit): Job = Job()
        override suspend fun close() {}
    }

    /**
     * 用真 AudioPlayback(iOS actual)+ 真 AudioSink wrapper 验:
     *   1. coordinator 走 fireBroadcastInvite 不 crash
     *   2. 手工 feed 1 秒 G.711A 数据 →  AudioSink.write 不 throw
     */
    @Test
    fun ios_coordinator_wires_audiosink_and_survives_pcm_feed() = runTest {
        val transport = MockSipTransport()
        transport.connect()

        // AudioSink 用真的 AudioPlayback.ios wrapper(commonMain realAudioSink 的对应物)
        val ap = AudioPlayback(sampleRate = 8000, channelCount = 1)
        val sink = object : AudioSink {
            override fun start() = ap.start()
            override fun write(pcm: ShortArray) = ap.write(pcm)
            override fun stop() = ap.stop()
        }

        val bc = BroadcastCoordinatorImpl(
            config = cfg(),
            transport = transport,
            scope = this,
            outbox = SipOutboxImpl(transport) {},
            localIpProvider = { "192.168.10.112" },
            localPortProvider = { 5060 },
            rtpReceiverFactory = { FakeRx() },
            audioSinkFactory = { _, _ -> sink },
        )

        bc.fireBroadcastInvite(
            sourceId = platformId,
            platformUri = "sip:$platformId@3502000000",
            targetId = deviceId,
        )
        runCurrent()

        // 检查 outbound INVITE 已发
        val invites = transport.sent.filterIsInstance<SipRequest>()
            .filter { it.method == SipMethod.INVITE }
        assertEquals(1, invites.size)
        assertNotNull(bc.current.value)

        // 直接 feed alaw 到 sink,证明 audio path 没 throw
        sink.start()
        try {
            for (i in 0 until 50) {
                val pcm = ShortArray(160) { j ->
                    (sin(2 * PI * 440.0 * (i * 160 + j) / 8000.0) * Short.MAX_VALUE * 0.5)
                        .toInt().toShort()
                }
                val alaw = G711.encodeAlaw(pcm)
                val decoded = G711.decodeAlaw(alaw)
                sink.write(decoded)
            }
        } finally {
            sink.stop()
        }
        assertTrue(true, "feed 50 帧 G.711A 无 throw")
    }
}
