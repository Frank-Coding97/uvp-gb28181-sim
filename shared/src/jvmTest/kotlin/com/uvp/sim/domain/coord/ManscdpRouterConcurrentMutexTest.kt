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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * cross-review R3 #2 修复 — 真并发 mutex 契约测试(JVM-only,用 Thread.sleep + AtomicInteger)。
 *
 * 用 runBlocking + Dispatchers.Default 让 launch 真并行;fake provider 内 Thread.sleep 50ms
 * 制造重叠窗口;AtomicInteger 记账 inFlight。
 * Mutex 生效时 maxConcurrent 严格 = 1;若 mutex 移除,5 个协程会重叠,maxConcurrent > 1。
 */
class ManscdpRouterConcurrentMutexTest {

    /** Real-sleep fake provider — 用 Thread.sleep 阻塞真线程,让 Dispatchers.Default 上多协程真重叠。 */
    private class SleepingLocationProvider : LocationProvider {
        val startCalls = AtomicInteger(0)
        val stopCalls = AtomicInteger(0)
        val maxConcurrent = AtomicInteger(0)
        private val inFlight = AtomicInteger(0)

        override fun start() = record(startCalls)
        override fun stop() = record(stopCalls)
        override fun next(): PositionFix? = null

        private fun record(counter: AtomicInteger) {
            val cur = inFlight.incrementAndGet()
            maxConcurrent.updateAndGet { maxOf(it, cur) }
            try {
                Thread.sleep(50)
            } catch (_: InterruptedException) {}
            counter.incrementAndGet()
            inFlight.decrementAndGet()
        }
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

    private fun mobilePositionDialog(callId: String) = SubscriptionDialog(
        kind = "MobilePosition",
        subscriberUri = "sip:platform@192.168.1.100:5060",
        callId = callId,
        fromTag = "plat-tag",
        toTag = "dev-tag",
        intervalSeconds = 5,
        expiresSeconds = 30,
        remainingSeconds = 30,
    )

    @Test
    fun mutex_preventsConcurrentSyncEntry_realThreads() {
        runBlocking(Dispatchers.Default) {
            val registry = SubscriptionRegistry(this)
            val location = SleepingLocationProvider()
            val router = newRouter(this, registry, location)

            registry.activate(mobilePositionDialog("c1")) {}
            kotlinx.coroutines.delay(20)

            coroutineScope {
                val scope = this
                repeat(5) {
                    scope.launch { router.resyncLocationLifecycle() }
                }
            }

            assertEquals(
                1, location.maxConcurrent.get(),
                "Mutex 必须保证 provider 操作串行(真并发 Dispatchers.Default + Thread.sleep):maxConcurrent=${location.maxConcurrent.get()}",
            )
            assertTrue(location.startCalls.get() >= 5, "startCalls=${location.startCalls.get()}")

            registry.cancel("c1")
            kotlinx.coroutines.delay(100)
        }
    }
}
