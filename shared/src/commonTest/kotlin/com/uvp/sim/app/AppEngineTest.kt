package com.uvp.sim.app

import com.uvp.sim.config.DeviceConfig
import com.uvp.sim.config.GbVersion
import com.uvp.sim.config.GeoPoint
import com.uvp.sim.config.ServerConfig
import com.uvp.sim.config.SimConfig
import com.uvp.sim.domain.ClockOffset
import com.uvp.sim.network.TransportType
import com.uvp.sim.sip.SipState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
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
        resources = FakePlatformResources(),
        runtime = FakePlatformRuntime(),
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

    /**
     * P3-5(PR-USER-BUG-1):setConfig 切换后,从 SimConfig 派生的 holders 必须重新派生,
     * 不能沿用旧值。覆盖:catalogTree / mockGps / currentChannelName / clockOffset /
     * subscriptionRegistry 五个 holder。
     */
    @Test
    fun setConfig_rehydrates_holders() = runTest {
        val app = newApp(this)
        runCurrent()

        // ---- 初始基线 ----
        val initialChannelName = app.currentChannelName.value
        val initialCatalogIds = app.catalogTree.value.map { it.id }.toSet()
        assertTrue(initialCatalogIds.isNotEmpty(), "catalogTree 初始非空(默认 3-4 节点)")

        // 模拟运行期 holder 落了脏数据:订阅注册 + clockOffset 校了时
        val registry = app.subscriptionRegistryForTest()
        registry.activate(
            com.uvp.sim.domain.SubscriptionDialog(
                kind = "MobilePosition",
                subscriberUri = "sip:platform@10.0.0.1:5060",
                callId = "stale-call@host",
                fromTag = "ft", toTag = "tt",
                intervalSeconds = 60, expiresSeconds = 3600, remainingSeconds = 3600
            )
        ) {}
        runCurrent()
        assertTrue(registry.subscriptions.value["MobilePosition"]?.active == true, "脏数据写进 registry 成功")

        // ---- 切新配置 ----
        val newConfig = config().copy(
            device = config().device.copy(
                deviceId = "99020000001310000099",
                videoChannelId = "99020000001320000099",
                videoChannelName = "新装通道名"
            ),
            mockPosition = GeoPoint(longitude = 121.473, latitude = 31.230) // 上海
        )
        app.setConfig(newConfig)
        runCurrent()

        // ---- 验证 holder 全部重派生 ----
        // 1. currentChannelName 跟 new device 一致
        assertEquals(
            "新装通道名", app.currentChannelName.value,
            "currentChannelName 必须跟随新 device.videoChannelName"
        )
        assertNotEquals(initialChannelName, app.currentChannelName.value)

        // 2. catalogTree 已重新生成(包含新 deviceId 节点)
        val newCatalogIds = app.catalogTree.value.map { it.id }.toSet()
        assertTrue(
            "99020000001310000099" in newCatalogIds,
            "catalogTree 必须包含新 deviceId 节点,实际=$newCatalogIds"
        )
        assertNotEquals(initialCatalogIds, newCatalogIds)

        // 3. mockGps 起点已 reset(下一帧 .next() 落到新起点附近)
        val fix = app.mockGpsForTest().next()
        // 新起点 (121.473, 31.230)。next() 每帧最大走 0.0001 度,远离北京默认 (116.404, 39.915)
        assertTrue(
            fix.point.longitude > 120.0 && fix.point.longitude < 123.0,
            "mockGps 必须从新起点开始,实际经度=${fix.point.longitude}"
        )
        assertTrue(
            fix.point.latitude > 30.0 && fix.point.latitude < 32.0,
            "mockGps 必须从新起点开始,实际纬度=${fix.point.latitude}"
        )

        // 4. clockOffset 已清空(rehydrate 重置为 Empty)
        assertEquals(
            ClockOffset.Empty.platformBaselineMs, app.clockOffset.value.platformBaselineMs,
            "clockOffset.platformBaselineMs 必须清空"
        )
        assertEquals(false, app.clockOffset.value.isSynced, "clockOffset.isSynced 必须 false")

        // 5. subscriptionRegistry 已 cancelAll(旧 stale-call 已清掉)
        assertTrue(
            registry.subscriptions.value.isEmpty(),
            "subscriptionRegistry 必须 cancelAll,实际=${registry.subscriptions.value}"
        )
    }

    /**
     * P1-1(2026-06-28):applyConfigPartial 是局部应用路径 — 只刷 _config 与
     * catalogTree 派生,不能动 clockOffset / subscriptionRegistry / mockGps /
     * currentChannelName。专为 saveCatalogTree 这类"只换数据快照"场景设计。
     */
    @Test
    fun applyConfigPartial_only_refreshes_catalog_keeps_runtime_state() = runTest {
        val app = newApp(this)
        runCurrent()

        val baselineChannelName = app.currentChannelName.value
        val baselineCatalogIds = app.catalogTree.value.map { it.id }.toSet()

        // 模拟运行期脏数据(订阅 + clockOffset baseline)
        val registry = app.subscriptionRegistryForTest()
        registry.activate(
            com.uvp.sim.domain.SubscriptionDialog(
                kind = "MobilePosition",
                subscriberUri = "sip:platform@10.0.0.1:5060",
                callId = "live-call@host",
                fromTag = "ft", toTag = "tt",
                intervalSeconds = 60, expiresSeconds = 3600, remainingSeconds = 3600
            )
        ) {}
        runCurrent()
        assertTrue(registry.subscriptions.value["MobilePosition"]?.active == true)

        // 走 saveCatalogTree 等价路径:只改 catalogTree 字段(其它字段同 baseline)
        val newTree = listOf(
            com.uvp.sim.config.CatalogNode(
                id = "99020000001320000099",
                type = com.uvp.sim.config.CatalogNodeType.VideoChannel,
                name = "局部新增通道",
                parentId = app.config.value.device.deviceId,
            )
        )
        val newCfg = app.config.value.copy(catalogTree = newTree)
        app.applyConfigPartial(newCfg)
        runCurrent()

        // catalogTree 已刷
        val newCatalogIds = app.catalogTree.value.map { it.id }.toSet()
        assertNotEquals(baselineCatalogIds, newCatalogIds)

        // 运行期状态必须原样保留
        assertEquals(baselineChannelName, app.currentChannelName.value, "currentChannelName 不能动")
        assertTrue(
            registry.subscriptions.value["MobilePosition"]?.active == true,
            "subscriptionRegistry 必须保留,实际=${registry.subscriptions.value}"
        )
    }
}
