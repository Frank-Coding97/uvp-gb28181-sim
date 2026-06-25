package com.uvp.sim.app

import com.uvp.sim.config.DeviceConfig
import com.uvp.sim.config.GbVersion
import com.uvp.sim.config.ServerConfig
import com.uvp.sim.config.SimConfig
import com.uvp.sim.network.TransportType
import com.uvp.sim.sip.SipState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * PR6 T6.1 RED:[AppEngine] 装配根契约。
 *
 * GREEN(T6.3)后改正向断言:connect / disconnect 幂等、updateConfig 重连、
 * state default、events 滚动、resources null 时不崩。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppEngineTest {

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

    private fun newApp(scope: kotlinx.coroutines.CoroutineScope) = AppEngine(
        resources = FakeAndroidResources(),
        initialConfig = config(),
        parentScope = scope,
    )

    @Test
    fun t6_1_a_initial_state_disconnected() = runTest {
        val app = newApp(this)
        runCurrent()
        assertEquals(SipState.Disconnected, app.state.value, "初始状态 Disconnected")
        assertNotNull(app.config.value)
    }

    @Test
    fun t6_1_b_setBroadcastSpeaker_works_in_stub() = runTest {
        val app = newApp(this)
        assertEquals(true, app.broadcastSpeakerOn.value)
        app.setBroadcastSpeaker(false)
        runCurrent()
        assertEquals(false, app.broadcastSpeakerOn.value)
    }

    @Test
    fun t6_1_c_connect_stub_throws() = runTest {
        val app = newApp(this)
        // RED:抛错;GREEN:正常装配
        assertFails("RED: connect stub 应抛错") {
            app.connect()
        }
    }

    @Test
    fun t6_1_d_updateConfig_stub_throws() = runTest {
        val app = newApp(this)
        assertFails("RED: updateConfig stub 应抛错") {
            app.updateConfig(config())
        }
    }

    @Test
    fun t6_1_e_resources_with_null_factories_compiles() = runTest {
        // 验证 FakeAndroidResources 全 null 也能构造 AppEngine(iOS 占位场景)
        val app = newApp(this)
        runCurrent()
        // 公开 StateFlow 都不 null
        assertTrue(app.state.value == SipState.Disconnected)
        assertTrue(app.config.value.device.deviceId.isNotEmpty())
    }
}
