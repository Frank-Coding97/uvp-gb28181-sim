package com.uvp.sim.domain.coord

import com.uvp.sim.config.DeviceConfig
import com.uvp.sim.config.GbVersion
import com.uvp.sim.config.ServerConfig
import com.uvp.sim.config.SimConfig
import com.uvp.sim.domain.BroadcastDialogState
import com.uvp.sim.domain.MockSipTransport
import com.uvp.sim.network.TransportType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull

/**
 * PR5 T5.1 RED:[BroadcastCoordinatorImpl] 直接路径覆盖。
 *
 * GREEN 后(T5.3)改正向断言。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BroadcastCoordinatorTest {

    private fun config() = SimConfig(
        gbVersion = GbVersion.V2022,
        server = ServerConfig(
            ip = "192.168.10.222", port = 8160,
            serverId = "35020000002000000001", domain = "3502000000",
        ),
        device = DeviceConfig(
            deviceId = "35020000001310000001",
            videoChannelId = "35020000001320000001",
            alarmChannelId = "35020000001340000001",
            username = "35020000001310000001",
            password = "p",
        ),
        transport = TransportType.UDP,
        keepaliveIntervalSeconds = 60,
    )

    private fun newBc(scope: CoroutineScope, transport: MockSipTransport): BroadcastCoordinatorImpl =
        BroadcastCoordinatorImpl(
            config = config(),
            transport = transport,
            scope = scope,
            localIpProvider = { "192.168.10.112" },
            localPortProvider = { 5060 },
        )

    @Test
    fun t5_1_bc_a_fireBroadcastInvite_stub_throws() = runTest {
        val transport = MockSipTransport()
        transport.connect()
        val bc = newBc(this, transport)
        // RED:抛错;GREEN:发 outbound INVITE
        assertFails("RED: fireBroadcastInvite stub 应抛错") {
            bc.fireBroadcastInvite(
                sourceId = "35020000002000000001",
                platformUri = "sip:35020000002000000001@3502000000",
                targetId = "35020000001310000001",
            )
        }
    }

    @Test
    fun t5_1_bc_b_setSpeaker_works_in_stub() = runTest {
        val transport = MockSipTransport()
        transport.connect()
        val bc = newBc(this, transport)
        // setSpeaker 不依赖业务,stub 也能工作 — 验证 StateFlow 正确翻转
        assertEquals(true, bc.speakerOn.value, "默认 true")
        bc.setSpeaker(false)
        runCurrent()
        assertEquals(false, bc.speakerOn.value, "setSpeaker(false) 后应 false")
        bc.setSpeaker(true)
        runCurrent()
        assertEquals(true, bc.speakerOn.value, "setSpeaker(true) 后应 true")
    }

    @Test
    fun t5_1_bc_c_stop_with_no_active_throws_in_stub() = runTest {
        val transport = MockSipTransport()
        transport.connect()
        val bc = newBc(this, transport)
        // RED:抛错;GREEN:无活跃 broadcast 是 no-op
        assertFails("RED: stop stub 应抛错") { bc.stop() }
    }

    @Test
    fun t5_1_bc_d_initial_state_no_dialog() = runTest {
        val transport = MockSipTransport()
        transport.connect()
        val bc = newBc(this, transport)
        // current.value 必须 null,即"无活跃 broadcast"
        assertNull(bc.current.value, "初始无活跃 broadcast")
        // debugSnapshot 0/0/false
        val snap = bc.debugSnapshot()
        assertEquals(0L, snap.rxPacketCount)
        assertEquals(0L, snap.decodeErrorCount)
        assertEquals(false, snap.rxActive)
    }
}
