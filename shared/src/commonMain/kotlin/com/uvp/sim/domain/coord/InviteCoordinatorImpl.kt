package com.uvp.sim.domain.coord

import com.uvp.sim.config.CatalogNode
import com.uvp.sim.config.SimConfig
import com.uvp.sim.domain.ClockOffset
import com.uvp.sim.network.RtpMode
import com.uvp.sim.network.RtpSender
import com.uvp.sim.network.SipTransport
import com.uvp.sim.recording.RecordingService
import com.uvp.sim.sip.SipMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [InviteCoordinator] 的 PR4 T4.1 RED 空 stub。
 *
 * 真实现会在 T4.2 GREEN 落地(把 Engine 上 handleInvite / handlePlaybackInvite /
 * handleAck / handleBye / handleCancel / handleInfo / stopStream / stopActiveStream /
 * sendBroadcastInvite / handleBroadcastInviteResponse 等 ~28 个符号迁过来)。
 *
 * 当前所有方法都返回 default / throw NotImplementedError,让 T4.1 的 6 个测试**全部失败**,
 * 验证测试用例本身有效。
 */
internal class InviteCoordinatorImpl(
    private val config: SimConfig,
    private val transport: SipTransport,
    private val scope: CoroutineScope,
    private val localIpProvider: () -> String = { "0.0.0.0" },
    private val localPortProvider: () -> Int = { 5060 },
    private val cameraCapture: com.uvp.sim.camera.CameraCapture? = null,
    private val audioCapture: com.uvp.sim.camera.AudioCapture? = null,
    private val rtpSenderFactory: ((host: String, port: Int, mode: RtpMode) -> RtpSender)? = null,
    private val recordingService: RecordingService = com.uvp.sim.recording.NoopRecordingService,
    private val playbackBuilder: com.uvp.sim.domain.PlaybackBuilder? = null,
    private val catalogTree: StateFlow<List<CatalogNode>> = MutableStateFlow(emptyList()),
    private val clockOffsetProvider: () -> ClockOffset = { ClockOffset.Empty },
    private val broadcastHandshakeListener: BroadcastDialogHandshakeListener = NoopBroadcastDialogHandshakeListener,
    private val mutableSipState: MutableStateFlow<com.uvp.sim.sip.SipState> =
        MutableStateFlow(com.uvp.sim.sip.SipState.Disconnected),
    private val simEventEmit: suspend (com.uvp.sim.domain.SimEvent) -> Unit = {},
    cseqProvider: (() -> Int)? = null,
    cseqIncrementer: (() -> Int)? = null,
    callIdProvider: (() -> String?)? = null,
    callIdSetter: ((String) -> Unit)? = null,
    fromTagProvider: (() -> String?)? = null,
    fromTagSetter: ((String) -> Unit)? = null,
) : InviteCoordinator {

    private val _state = MutableStateFlow(InviteState.Idle)
    override val state: StateFlow<InviteState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<InviteEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<InviteEvent> = _events.asSharedFlow()

    private val _activeStreamSnapshot = MutableStateFlow<ActiveStreamSnapshot?>(null)
    override val activeStreamSnapshot: StateFlow<ActiveStreamSnapshot?> =
        _activeStreamSnapshot.asStateFlow()

    private val _currentChannelName = MutableStateFlow(config.device.videoChannelName)
    override val currentChannelName: StateFlow<String> = _currentChannelName.asStateFlow()

    // SN 池 provider 适配(PR2 / PR3 同模式;T4.2 真用)
    private var internalCseq: Int = 0
    private var internalCallId: String? = null
    private var internalFromTag: String? = null
    private val cseqRead: () -> Int = cseqProvider ?: { internalCseq }
    private val cseqIncAndRead: () -> Int = cseqIncrementer ?: { internalCseq += 1; internalCseq }
    private val callIdRead: () -> String? = callIdProvider ?: { internalCallId }
    private val callIdWrite: (String) -> Unit = callIdSetter ?: { internalCallId = it }
    private val fromTagRead: () -> String? = fromTagProvider ?: { internalFromTag }
    private val fromTagWrite: (String) -> Unit = fromTagSetter ?: { internalFromTag = it }

    override suspend fun onIncoming(msg: SipMessage): RoutingResult {
        throw NotImplementedError("PR4 T4.1 RED stub - implement in T4.2 GREEN")
    }

    override suspend fun shutdown() {
        // PR4 T4.1 RED stub - no-op
    }

    override suspend fun stopStream(reason: String) {
        throw NotImplementedError("PR4 T4.1 RED stub - implement in T4.2 GREEN")
    }

    override suspend fun fireBroadcastInvite(sourceId: String, platformUri: String, targetId: String) {
        throw NotImplementedError("PR4 T4.1 RED stub - implement in T4.2 GREEN")
    }
}

/** 单测默认占位:reaction 都 no-op,允许 Coord 在没接 Engine 的环境里跑出错路径。 */
internal object NoopBroadcastDialogHandshakeListener : BroadcastDialogHandshakeListener {
    override suspend fun onInviting(
        callId: String, fromTag: String, cseq: Int, sourceId: String, targetId: String,
        platformUri: String, localAudioPort: Int, deviceSsrc: String, mode: RtpMode,
    ) {}
    override suspend fun onTalking(
        callId: String, remoteTag: String, remoteHost: String, remotePort: Int,
        codec: com.uvp.sim.domain.AudioRxCodec,
    ) {}
    override suspend fun onFailed(callId: String, reason: BroadcastEndReasonHint) {}
}
