package com.uvp.sim.testing

import com.uvp.sim.app.PlatformResources
import com.uvp.sim.app.ConfigStore
import com.uvp.sim.camera.AudioCapture
import com.uvp.sim.camera.CameraCapture
import com.uvp.sim.config.SimConfig
import com.uvp.sim.domain.AlarmHistoryStore
import com.uvp.sim.domain.CatalogTreeStore
import com.uvp.sim.domain.ClockOffset
import com.uvp.sim.domain.DeviceControlModel
import com.uvp.sim.domain.EngineCoordinators
import com.uvp.sim.domain.EngineHolders
import com.uvp.sim.domain.MockGpsSource
import com.uvp.sim.domain.PlaybackBuilder
import com.uvp.sim.domain.SimEvent
import com.uvp.sim.domain.SimulatorEngine
import com.uvp.sim.domain.SubscriptionRegistry
import com.uvp.sim.domain.newDefaultIdentityService
import com.uvp.sim.domain.registerPoolLambdasFrom
import com.uvp.sim.domain.coord.BroadcastCoordinatorImpl
import com.uvp.sim.domain.coord.InviteCoordinatorImpl
import com.uvp.sim.domain.coord.ManscdpRouterImpl
import com.uvp.sim.domain.coord.PlaybackCoordinatorImpl
import com.uvp.sim.domain.coord.RegistrationCoordinatorImpl
import com.uvp.sim.media.AudioSink
import com.uvp.sim.network.BroadcastRxSource
import com.uvp.sim.network.RtpMode
import com.uvp.sim.network.RtpSender
import com.uvp.sim.network.SipTransport
import com.uvp.sim.recording.NoopRecordingService
import com.uvp.sim.recording.RecordingService
import com.uvp.sim.sip.SipOutboxImpl
import com.uvp.sim.sip.SipState
import com.uvp.sim.snapshot.JpegLocalCache
import com.uvp.sim.snapshot.SnapshotCapture
import io.ktor.client.engine.HttpClientEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 测试装配工厂 — 替代 `SimulatorEngine(...)` 直构造,内部完成 buildHolders + buildCoordinators。
 *
 * 保留 7 个测试常用命名参数(localIpProvider / rtpReceiverFactory / recordingService /
 * playbackBuilder / audioSinkFactory / cameraCapture / audioCapture / rtpSenderFactory),
 * 默认值跟生产 PlatformResources 一致(全 null / Noop)。
 *
 * 跟生产 AppEngine.buildCoordinators 完全同款装配链,只是 resources 用 TestResources 占位。
 */
internal object TestEngine {
    fun create(
        config: SimConfig,
        transport: SipTransport,
        scope: CoroutineScope,
        localIpProvider: () -> String = { "0.0.0.0" },
        cameraCapture: CameraCapture? = null,
        audioCapture: AudioCapture? = null,
        rtpSenderFactory: ((String, Int, RtpMode, String?) -> RtpSender)? = null,
        rtpReceiverFactory: ((CoroutineScope) -> BroadcastRxSource)? = null,
        audioSinkFactory: ((Int, Int) -> AudioSink)? = null,
        recordingService: RecordingService = NoopRecordingService,
        playbackBuilder: PlaybackBuilder? = null,
    ): SimulatorEngine {
        val resources = TestResources(
            rtpSenderFactory = rtpSenderFactory?.let { f ->
                { host, port, _, mode, expectedClientHost -> f(host, port, mode, expectedClientHost) }
            },
            rtpReceiverFactory = rtpReceiverFactory,
            audioSinkFactory = audioSinkFactory,
            localIpProvider = localIpProvider,
        )
        val holders = EngineHolders(
            state = MutableStateFlow(SipState.Disconnected),
            events = MutableSharedFlow(extraBufferCapacity = 64),
            deviceControlState = MutableStateFlow(DeviceControlModel()),
            catalogTree = MutableStateFlow(CatalogTreeStore.effectiveTree(config)),
            clockOffset = MutableStateFlow(ClockOffset.Empty),
            alarmHistoryStore = AlarmHistoryStore(),
            subscriptionRegistry = SubscriptionRegistry(scope),
            mockGps = MockGpsSource(config.mockPosition),
            identityService = newDefaultIdentityService(localIpProvider = localIpProvider),
        )
        val outbox = SipOutboxImpl(transport) { ev -> holders.events.emit(ev) }
        val identityService = holders.identityService
        val pool = registerPoolLambdasFrom(identityService)
        val localPortProvider: () -> Int = { transport.localPort.takeIf { it > 0 } ?: 5060 }

        val registration = RegistrationCoordinatorImpl(
            config = config, transport = transport, scope = scope, outbox = outbox,
            localIpProvider = localIpProvider, localPortProvider = localPortProvider,
            cseqProvider = pool.cseqProvider, cseqIncrementer = pool.cseqIncrementer,
            callIdProvider = pool.callIdProvider, callIdSetter = pool.callIdSetter,
            fromTagProvider = pool.fromTagProvider, fromTagSetter = pool.fromTagSetter,
        )
        val broadcast = BroadcastCoordinatorImpl(
            config = config, transport = transport, scope = scope, outbox = outbox,
            localIpProvider = localIpProvider, localPortProvider = localPortProvider,
            rtpReceiverFactory = rtpReceiverFactory, audioSinkFactory = audioSinkFactory,
            simEventEmit = { ev -> holders.events.emit(ev) },
            cseqProvider = pool.cseqProvider, cseqIncrementer = pool.cseqIncrementer,
            callIdProvider = pool.callIdProvider, callIdSetter = pool.callIdSetter,
            fromTagProvider = pool.fromTagProvider, fromTagSetter = pool.fromTagSetter,
        )
        val playback = PlaybackCoordinatorImpl(
            config = config, transport = transport, outbox = outbox, scope = scope,
            localIpProvider = localIpProvider, localPortProvider = localPortProvider,
            playbackBuilder = playbackBuilder, recordingService = recordingService,
            simEventEmit = { ev -> holders.events.emit(ev) },
            cseqProvider = pool.cseqProvider, cseqIncrementer = pool.cseqIncrementer,
            callIdProvider = pool.callIdProvider, callIdSetter = pool.callIdSetter,
            fromTagProvider = pool.fromTagProvider, fromTagSetter = pool.fromTagSetter,
        )
        val invite = InviteCoordinatorImpl(
            config = config, transport = transport, outbox = outbox, scope = scope,
            localIpProvider = localIpProvider, localPortProvider = localPortProvider,
            cameraCapture = cameraCapture, audioCapture = audioCapture,
            rtpSenderFactory = rtpSenderFactory,
            catalogTree = holders.catalogTree, clockOffsetProvider = { holders.clockOffset.value },
            mutableSipState = holders.state, simEventEmit = { ev -> holders.events.emit(ev) },
            cseqProvider = pool.cseqProvider, cseqIncrementer = pool.cseqIncrementer,
            callIdProvider = pool.callIdProvider, callIdSetter = pool.callIdSetter,
            fromTagProvider = pool.fromTagProvider, fromTagSetter = pool.fromTagSetter,
        )
        var engineRef: SimulatorEngine? = null
        val manscdp = ManscdpRouterImpl(
            config = config, transport = transport, outbox = outbox, scope = scope,
            localIpProvider = localIpProvider, localPortProvider = localPortProvider,
            subscriptionRegistry = holders.subscriptionRegistry,
            catalogTree = holders.catalogTree,
            alarmHistoryStore = holders.alarmHistoryStore,
            mutableDeviceControlState = holders.deviceControlState,
            rebootCallback = { engineRef?.rebootForDeviceControl() ?: Unit },
            requestKeyFrameCallback = { cameraCapture?.requestKeyFrame() },
            broadcastInvoker = broadcast,
            recordingService = recordingService,
            mockGps = holders.mockGps,
            clockOffsetProvider = { holders.clockOffset.value },
            stateRegisteredOrInCall = { holders.state.value == SipState.Registered || holders.state.value == SipState.InCall },
            broadcastBusy = { broadcast.current.value != null },
            simEventEmit = { ev -> holders.events.emit(ev) },
            identityService = identityService,
        )
        val coords = EngineCoordinators(registration, broadcast, playback, invite, manscdp)
        val engine = SimulatorEngine(config, transport, scope, resources, coords, holders)
        engineRef = engine
        return engine
    }
}

internal class TestResources(
    override val rtpSenderFactory: ((String, Int, CoroutineScope, RtpMode, String?) -> RtpSender)? = null,
    override val rtpReceiverFactory: ((CoroutineScope) -> BroadcastRxSource)? = null,
    override val audioSinkFactory: ((Int, Int) -> AudioSink)? = null,
    override val localIpProvider: () -> String = { "0.0.0.0" },
    override val snapshotCapture: SnapshotCapture? = null,
    override val snapshotCache: JpegLocalCache? = null,
    override val httpEngineFactory: (() -> HttpClientEngine)? = null,
    override val playbackBuilderFactory: ((CoroutineScope, com.uvp.sim.media.AudioCodec, (String, Int, RtpMode) -> RtpSender) -> PlaybackBuilder)? = null,
    override val configStore: ConfigStore = NoopConfigStore,
) : PlatformResources

internal object NoopConfigStore : ConfigStore {
    override suspend fun loadOnce(fallback: SimConfig): SimConfig = fallback
    override suspend fun save(config: SimConfig) = Unit
}
