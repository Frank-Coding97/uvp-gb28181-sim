package com.uvp.sim.domain.coord

import com.uvp.sim.config.DeviceConfig
import com.uvp.sim.config.GbVersion
import com.uvp.sim.config.GeoPoint
import com.uvp.sim.config.ServerConfig
import com.uvp.sim.config.SimConfig
import com.uvp.sim.domain.AlarmHistoryStore
import com.uvp.sim.domain.CatalogTreeStore
import com.uvp.sim.domain.DeviceControlModel
import com.uvp.sim.domain.MockSipTransport
import com.uvp.sim.domain.SubscriptionDialog
import com.uvp.sim.domain.SubscriptionRegistry
import com.uvp.sim.domain.location.LocationProvider
import com.uvp.sim.domain.location.PositionFix
import com.uvp.sim.network.TransportType
import com.uvp.sim.recording.NoopRecordingService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * plan §3.2 T7 集成测试 —
 *
 * 覆盖 MobilePosition 订阅生命周期与 [LocationProvider.start] / [LocationProvider.stop] 的对齐。
 * 关键回归包括 P0 fix(自然过期路径必须触发 stop)与并发订阅的启停幂等。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ManscdpRouterLifecycleTest {

    /** 记账 start/stop 调用次数的 [LocationProvider] fake。 */
    private class CountingLocationProvider : LocationProvider {
        var startCalls = 0
        var stopCalls = 0
        override fun start() { startCalls++ }
        override fun stop() { stopCalls++ }
        override fun next(): PositionFix? = null
    }

    private fun config() = SimConfig(
        gbVersion = GbVersion.V2022,
        server = ServerConfig(
            ip = "192.168.1.100", port = 5060,
            serverId = "34020000002000000001", domain = "3402000000",
        ),
        device = DeviceConfig(
            deviceId = "34020000001110000001",
            videoChannelId = "34020000001320000001",
            alarmChannelId = "34020000001340000001",
            username = "34020000001110000001",
            password = "test-password",
        ),
        transport = TransportType.UDP,
        keepaliveIntervalSeconds = 60,
        mockPosition = GeoPoint(116.404, 39.915),
    )

    private fun newRouter(
        scope: CoroutineScope,
        registry: SubscriptionRegistry,
        location: LocationProvider,
    ): ManscdpRouterImpl {
        val transport = MockSipTransport()
        val cfg = config()
        return ManscdpRouterImpl(
            config = cfg,
            transport = transport,
            outbox = com.uvp.sim.sip.SipOutboxImpl(transport) {},
            scope = scope,
            localIpProvider = { "192.168.1.50" },
            localPortProvider = { 5060 },
            subscriptionRegistry = registry,
            catalogTree = MutableStateFlow(CatalogTreeStore.effectiveTree(cfg)),
            alarmHistoryStore = AlarmHistoryStore(),
            mutableDeviceControlState = MutableStateFlow(DeviceControlModel()),
            rebootCallback = {},
            requestKeyFrameCallback = {},
            broadcastInvoker = object : BroadcastInvoker {
                override suspend fun fireBroadcastInvite(sourceId: String, platformUri: String, targetId: String) =
                    BroadcastInviteStart.Started
            },
            recordingService = NoopRecordingService,
            mockGps = location,
            stateRegisteredOrInCall = { true },
            identityService = com.uvp.sim.sip.DefaultSipDialogIdentityService(localIp = "192.168.1.50"),
        )
    }

    private fun mobilePositionDialog(callId: String, expires: Int = 30, interval: Int = 5) =
        SubscriptionDialog(
            kind = "MobilePosition",
            subscriberUri = "sip:platform@192.168.1.100:5060",
            callId = callId,
            fromTag = "plat-tag",
            toTag = "dev-tag",
            intervalSeconds = interval,
            expiresSeconds = expires,
            remainingSeconds = expires,
        )

    @Test
    fun cancel_lastMobilePositionSubscription_stopsLocation() = runTest {
        val registry = SubscriptionRegistry(this)
        val location = CountingLocationProvider()
        val router = newRouter(this, registry, location)
        // 手动 activate 一个 MobilePosition dialog(绕开 SUBSCRIBE ingress),模拟真实活跃订阅
        registry.activate(mobilePositionDialog("c1")) {}
        // 直接 sync 一次让 start 触发(activate 加入路径需要 router.syncLocationLifecycle,
        // 但从外部注入 dialog 绕开了 router.handleSubscribe 的显式调用点 —— 通过 cancel
        // 触发 onDialogRemoved 钩子来验证 stop 就足够)
        assertEquals(0, location.stopCalls, "初始不该 stop")

        // 触发 cancel 路径
        registry.cancel("c1")
        runCurrent()

        assertEquals(1, location.stopCalls, "唯一订阅 cancel 后必须触发 stop")
    }

    @Test
    fun naturalExpiry_stopsLocation_p0Regression() = runTest {
        // P0 关键回归:自然过期路径(不经 router.cancel)必须也触发 stop
        val registry = SubscriptionRegistry(this)
        val location = CountingLocationProvider()
        newRouter(this, registry, location)

        registry.activate(mobilePositionDialog("c1", expires = 2)) {}
        assertEquals(0, location.stopCalls, "初始不该 stop")

        advanceTimeBy(3_000L) // 倒计时归零 + 保底

        assertEquals(1, location.stopCalls, "自然过期路径必须触发 stop (plan §3.2 P0 fix)")
    }

    @Test
    fun cancelOne_ofTwoSubscriptions_doesNotStopLocation() = runTest {
        // 两个 dialog 并存,cancel 其中一个 → 剩下的还在,不该 stop
        val registry = SubscriptionRegistry(this)
        val location = CountingLocationProvider()
        newRouter(this, registry, location)

        registry.activate(mobilePositionDialog("c1", interval = 5)) {}
        registry.activate(mobilePositionDialog("c2", interval = 10)) {}
        assertEquals(0, location.stopCalls)

        registry.cancel("c1")
        runCurrent()

        assertEquals(0, location.stopCalls, "还有订阅存活时不该 stop")

        registry.cancel("c2")
        runCurrent()

        assertEquals(1, location.stopCalls, "最后一个 cancel 后才 stop")
    }

    @Test
    fun cancelNonMobilePositionSubscription_doesNotAffectLocation() = runTest {
        // Catalog / Alarm 订阅进出不该影响定位启停
        val registry = SubscriptionRegistry(this)
        val location = CountingLocationProvider()
        newRouter(this, registry, location)

        registry.activate(
            SubscriptionDialog(
                kind = "Catalog",
                subscriberUri = "sip:platform@192.168.1.100:5060",
                callId = "cat-1",
                fromTag = "plat-tag", toTag = "dev-tag",
                intervalSeconds = 60, expiresSeconds = 300, remainingSeconds = 300,
            )
        ) {}
        registry.cancel("cat-1")
        runCurrent()

        assertEquals(0, location.startCalls, "Catalog 订阅不该触发 location.start")
        assertEquals(0, location.stopCalls, "Catalog 订阅不该触发 location.stop")
    }

    @Test
    fun refresh_doesNotChangeLocationLifecycle() = runTest {
        // Codex R1 P1-6 补测试:refresh(SUBSCRIBE 续期)不触发 start/stop 变更
        val registry = SubscriptionRegistry(this)
        val location = CountingLocationProvider()
        newRouter(this, registry, location)

        registry.activate(mobilePositionDialog("c1", expires = 30)) {}
        val startsAfterActivate = location.startCalls
        val stopsAfterActivate = location.stopCalls

        registry.refresh("c1", newExpires = 60)
        runCurrent()

        assertEquals(startsAfterActivate, location.startCalls, "refresh 不该重复 start")
        assertEquals(stopsAfterActivate, location.stopCalls, "refresh 不该 stop")
    }

    @Test
    fun cancelAll_stopsLocation_evenWithMultipleDialogs() = runTest {
        val registry = SubscriptionRegistry(this)
        val location = CountingLocationProvider()
        newRouter(this, registry, location)

        registry.activate(mobilePositionDialog("c1")) {}
        registry.activate(mobilePositionDialog("c2")) {}

        registry.cancelAll()
        runCurrent()

        // cancelAll 对每个 dialog 触发一次 onDialogRemoved → 两次 sync,
        // 第一次可能还有 c2 存活不 stop,第二次 isEmpty=true 才 stop → stop 至少 1 次
        assertEquals(true, location.stopCalls >= 1, "cancelAll 必须最终触发 stop,实际=${location.stopCalls}")
    }
}
