package com.uvp.sim.domain.coord.manscdp

import com.uvp.sim.config.DeviceConfig
import com.uvp.sim.config.GbVersion
import com.uvp.sim.config.GeoPoint
import com.uvp.sim.config.ServerConfig
import com.uvp.sim.config.SimConfig
import com.uvp.sim.domain.CatalogTreeStore
import com.uvp.sim.domain.ClockOffset
import com.uvp.sim.domain.DeviceControlModel
import com.uvp.sim.domain.MockGpsSource
import com.uvp.sim.domain.MockSipTransport
import com.uvp.sim.domain.SimEvent
import com.uvp.sim.domain.SubscriptionRegistry
import com.uvp.sim.network.TransportType
import com.uvp.sim.recording.NoopRecordingService
import com.uvp.sim.sip.DefaultSipDialogIdentityService
import com.uvp.sim.sip.SipDialogIdentityService
import com.uvp.sim.sip.SipOutboxImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 4 个 SubRouter 测试共用的装配 helper。
 *
 * 给每个 SubRouterTest 提供一对 [MockSipTransport] + [ManscdpContext],SubRouter 直接调
 * `handle(cmd, xml, fromUri)` 验证 outbound MESSAGE 是否如预期生成。
 */
internal object SubRouterTestFixtures {

    fun config() = SimConfig(
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
            password = "wvp2025!!!",
        ),
        transport = TransportType.UDP,
        keepaliveIntervalSeconds = 60,
        mockPosition = GeoPoint(116.404, 39.915),
    )

    data class Fixture(
        val ctx: ManscdpContext,
        val transport: MockSipTransport,
        val deviceControlState: MutableStateFlow<DeviceControlModel>,
        val events: MutableList<SimEvent>,
    )

    suspend fun newFixture(
        scope: CoroutineScope,
        cfg: SimConfig = config(),
        identityService: SipDialogIdentityService = DefaultSipDialogIdentityService(localIp = "192.168.1.50"),
    ): Fixture {
        val transport = MockSipTransport()
        transport.connect()
        val events = mutableListOf<SimEvent>()
        val deviceControlState = MutableStateFlow(DeviceControlModel())
        val catalogTree = MutableStateFlow(CatalogTreeStore.effectiveTree(cfg))
        val ctx = ManscdpContext(
            config = cfg,
            outbox = SipOutboxImpl(transport) {},
            identityService = identityService,
            subscriptionRegistry = SubscriptionRegistry(scope),
            deviceControlState = deviceControlState,
            catalogTree = catalogTree,
            mockGps = MockGpsSource(cfg.mockPosition),
            localIpProvider = { "192.168.1.50" },
            localPortProvider = { 5060 },
            clockOffsetProvider = { ClockOffset.Empty },
            stateRegisteredOrInCall = { true },
            simEventEmit = { ev -> events += ev },
        )
        return Fixture(ctx, transport, deviceControlState, events)
    }

    /** 简洁断言:transport.sent 里发出去的 MANSCDP MESSAGE 体含 substring。 */
    fun MockSipTransport.containsBody(substring: String): Boolean =
        sent.any { it.body.decodeToString().contains(substring) }

    @Suppress("unused")
    val noopRecordingService get() = NoopRecordingService
}
