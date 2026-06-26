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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * PR6 T6.3 GREEN:[AppEngine] 装配根契约。正向断言。
 *
 * 因 AppEngine.connect 需要真实 transport(UdpSipTransport 监听端口 / Coordinator 等),
 * commonTest 不便构造。这里只测 stub 不依赖 transport 的路径:
 *   - initial state / config 默认值
 *   - setBroadcastSpeaker 在无 engine 时直接写本地 _broadcastSpeakerOn
 *   - setConfig 内存视图
 *   - cancelConnect / disconnect 在无 engine 时 no-op
 *   - 各 public API 在无 engine 时不抛(Coord 调用 ?. 安全)
 *
 * 真实 connect 路径回归走 SipViewModel 既有 Android 真机回归 + 全 Engine contract test 不改一行通过 = 等价证明。
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
    fun t6_3_a_initial_state_disconnected() = runTest {
        val app = newApp(this)
        runCurrent()
        assertEquals(SipState.Disconnected, app.state.value, "初始 Disconnected")
        assertNotNull(app.config.value)
        assertEquals("35020000001310000001", app.config.value.device.deviceId)
    }

    @Test
    fun t6_3_b_setBroadcastSpeaker_works_without_engine() = runTest {
        val app = newApp(this)
        assertEquals(true, app.broadcastSpeakerOn.value)
        app.setBroadcastSpeaker(false)
        runCurrent()
        assertEquals(false, app.broadcastSpeakerOn.value)
    }

    @Test
    fun t6_3_c_setConfig_updates_in_memory() = runTest {
        val app = newApp(this)
        val new = config().copy(keepaliveIntervalSeconds = 120)
        app.setConfig(new)
        runCurrent()
        assertEquals(120, app.config.value.keepaliveIntervalSeconds)
    }

    @Test
    fun t6_3_d_disconnect_no_engine_is_noop() = runTest {
        val app = newApp(this)
        app.disconnect()
        app.cancelConnect()
        runCurrent()
        assertEquals(SipState.Disconnected, app.state.value)
    }

    @Test
    fun t6_3_e_public_API_no_engine_is_noop() = runTest {
        val app = newApp(this)
        app.reportSnapshot()
        app.localResetAlarm()
        app.stopStream("test")
        app.consumeEffect()
        app.updatePoseFromRender(0f, 0f, 1f)
        runCurrent()
        // 无 engine 时所有 public API 是 no-op,不抛错
        assertTrue(app.state.value == SipState.Disconnected)
    }
}
