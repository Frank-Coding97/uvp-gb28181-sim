package com.uvp.sim.domain.coord

import com.uvp.sim.config.SimConfig
import com.uvp.sim.domain.AudioRxCodec
import com.uvp.sim.domain.BroadcastDialog
import com.uvp.sim.domain.BroadcastDialogState
import com.uvp.sim.domain.BroadcastEndReason
import com.uvp.sim.domain.SimEvent
import com.uvp.sim.network.RtpMode
import com.uvp.sim.network.SipTransport
import com.uvp.sim.observability.LogLevel
import com.uvp.sim.observability.LogTag
import com.uvp.sim.observability.SystemLogger
import com.uvp.sim.sip.SipBuilders
import com.uvp.sim.sip.SipHeader
import com.uvp.sim.sip.SipHeaderHelpers
import com.uvp.sim.sip.SipMessage
import com.uvp.sim.sip.SipMethod
import com.uvp.sim.sip.SipRequest
import com.uvp.sim.sip.SipResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Clock

/**
 * [BroadcastCoordinator] 真实现(PR5 T5.3 GREEN)。
 *
 * 完整接管语音对讲下行域:
 *   - sendBroadcastInvite + handleBroadcastInviteResponse + sendBroadcastAck + sendBroadcastBye
 *     (从 InviteCoordinatorImpl 迁过来,实现 BroadcastInvoker)
 *   - RX 链 + audio 链 + dialog state(从 Engine 整段迁,删 BroadcastDialogHandshakeListener 临时桥)
 *   - setSpeaker / stop public API
 *   - debugSnapshot 替代 Engine 上 rxPacketCountForTest 等 test-hook
 *
 * onIncoming 路由:
 *   - SipResponse + CSeq INVITE + callId 命中本域 → handleBroadcastInviteResponse,Handled
 *   - BYE + callId 命中 → handleBroadcastBye + 200 OK,Handled
 *   - 其他 → Skip
 */
internal class BroadcastCoordinatorImpl(
    private val config: SimConfig,
    private val transport: SipTransport,
    private val scope: CoroutineScope,
    private val outbox: com.uvp.sim.sip.SipOutbox,
    private val localIpProvider: () -> String = { "0.0.0.0" },
    private val localPortProvider: () -> Int = { 5060 },
    rtpReceiverFactory: ((CoroutineScope) -> com.uvp.sim.network.BroadcastRxSource)? = null,
    audioSinkFactory: ((Int, Int) -> com.uvp.sim.media.AudioSink)? = null,
    private val simEventEmit: suspend (SimEvent) -> Unit = {},
    cseqProvider: (() -> Int)? = null,
    cseqIncrementer: (() -> Int)? = null,
    callIdProvider: (() -> String?)? = null,
    callIdSetter: ((String) -> Unit)? = null,
    fromTagProvider: (() -> String?)? = null,
    fromTagSetter: ((String) -> Unit)? = null,
) : BroadcastCoordinator {

    private val _state = MutableStateFlow(BroadcastDialogState.Inviting)
    override val state: StateFlow<BroadcastDialogState> = _state.asStateFlow()

    private val _current = MutableStateFlow<BroadcastDialog?>(null)
    override val current: StateFlow<BroadcastDialog?> = _current.asStateFlow()

    // R2 #1:state 转换锁。stop / shutdown / handleBroadcastInviteResponse(2xx 或 fail) / handleBroadcastBye
    // 四路都做"判活跃 → teardownBroadcastMedia → 清 _current"动作,无锁时可两路同时进入 teardown,
    // 把刚建好的 rtpReceiver / job 提前 close 一次再来第二次。
    private val stateMutex = Mutex()

    private val _speakerOn = MutableStateFlow(true)
    override val speakerOn: StateFlow<Boolean> = _speakerOn.asStateFlow()

    private val _events = MutableSharedFlow<BroadcastCoordEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<BroadcastCoordEvent> = _events.asSharedFlow()

    // SN 池 provider
    private var internalCseq: Int = 0
    private var internalCallId: String? = null
    private var internalFromTag: String? = null
    private val cseqRead: () -> Int = cseqProvider ?: { internalCseq }
    private val cseqIncAndRead: () -> Int = cseqIncrementer ?: { internalCseq += 1; internalCseq }
    private val callIdRead: () -> String? = callIdProvider ?: { internalCallId }
    private val callIdWrite: (String) -> Unit = callIdSetter ?: { internalCallId = it }
    private val fromTagRead: () -> String? = fromTagProvider ?: { internalFromTag }
    private val fromTagWrite: (String) -> Unit = fromTagSetter ?: { internalFromTag = it }
    private val cseq: Int get() = cseqRead()
    private val localIp: String get() = localIpProvider()

    // RX 链字段(从 Engine 迁过来)
    private var broadcastLocalAudioPort: Int = 0
    private var broadcastMode: RtpMode = RtpMode.UDP
    private var rxPackets = 0L
    private var rxBytes = 0L
    private var rxSeqLost = 0L
    private var rxDecodeErrors = 0L
    private var rxFirstPacketAtMs = 0L
    private var rtpReceiver: com.uvp.sim.network.BroadcastRxSource? = null
    private var rxJob: Job? = null
    private var rxStatsJob: Job? = null
    // R3 #6:RTP 入栈热路径 bounded channel + 单消费者,见 startBroadcastRx。
    private var rxPacketChannel: Channel<com.uvp.sim.network.RtpPacket>? = null
    private var rxConsumerJob: Job? = null
    private val audioChannel = Channel<ShortArray>(capacity = 50, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private var audioPlayback: com.uvp.sim.media.AudioSink? = null
    private var audioPlayJob: Job? = null

    private val resolvedRtpReceiverFactory: (CoroutineScope) -> com.uvp.sim.network.BroadcastRxSource =
        rtpReceiverFactory ?: { sc -> com.uvp.sim.network.realBroadcastRxSource(sc) }
    private val resolvedAudioSinkFactory: (Int, Int) -> com.uvp.sim.media.AudioSink =
        audioSinkFactory ?: { sr, ch -> com.uvp.sim.media.realAudioSink(sr, ch) }

    private fun nowMs(): Long = Clock.System.now().toEpochMilliseconds()

    companion object {
        const val BROADCAST_STATS_INTERVAL_MS: Long = 1_000L
    }

    // ---------- onIncoming ----------

    override suspend fun onIncoming(envelope: com.uvp.sim.network.SipEnvelope): RoutingResult {
        val msg = envelope.message
        return when (msg) {
            is SipResponse -> {
                val cseqRaw = msg.cseqRaw()
                val cseqParts = cseqRaw?.split(" ")
                val cseqMethod = cseqParts?.getOrNull(1)?.let { SipMethod.fromString(it) }
                val cseqNum = cseqParts?.getOrNull(0)?.trim()?.toIntOrNull()
                val bc = _current.value
                if (cseqMethod != SipMethod.INVITE || bc == null || msg.callId() != bc.callId) {
                    RoutingResult.Skip
                } else if (!verifyBroadcastResponseOrDrop(envelope, msg, bc, cseqNum)) {
                    // 校验失败已日志 warning,当作 Handled 防止其他 coordinator 误处理伪造响应。
                    RoutingResult.Handled
                } else {
                    handleBroadcastInviteResponse(envelope, msg, bc)
                    RoutingResult.Handled
                }
            }
            is SipRequest -> {
                if (msg.method == SipMethod.BYE) {
                    val bc = _current.value
                    if (bc != null && msg.callId() == bc.callId) {
                        handleBroadcastBye(envelope, msg, bc)
                        RoutingResult.Handled
                    } else RoutingResult.Skip
                } else RoutingResult.Skip
            }
        }
    }

    override suspend fun shutdown() {
        stateMutex.withLock {
            if (_current.value != null) {
                teardownBroadcastMedia()
                _current.value = null
            }
        }
    }

    /**
     * R2 #1 (verify-followup) helper:失败路径共用的"判活跃 → teardown → 清 _current"原子动作。
     * 返回 true = 这次确实做了清理(调用方应继续发 BroadcastEnded 事件);false = 已被别的路径清过。
     */
    private suspend fun tearDownIfActive(): Boolean = stateMutex.withLock {
        if (_current.value == null) return@withLock false
        teardownBroadcastMedia()
        _current.value = null
        true
    }

    override fun setSpeaker(on: Boolean) {
        _speakerOn.value = on
        SystemLogger.emit(LogLevel.Info, LogTag.Media, "语音对讲扬声器 → ${if (on) "开" else "静音"}")
    }

    override suspend fun stop(reason: BroadcastEndReason) {
        val bcToStop = stateMutex.withLock {
            val bc = _current.value ?: return@withLock null
            teardownBroadcastMedia()
            _current.value = null
            bc
        } ?: return
        val remoteTag = bcToStop.remoteTag
        if (remoteTag != null) {
            sendBroadcastBye(bcToStop, remoteTag)
        }
        val dur = nowMs() - bcToStop.createdAtMs
        simEventEmit(SimEvent.BroadcastEnded(reason, dur))
        SystemLogger.emit(LogLevel.Info, LogTag.Media, "语音广播停止(${reason.name})")
    }
    override fun debugSnapshot(): BroadcastDebugSnapshot =
        BroadcastDebugSnapshot(
            rxPacketCount = rxPackets,
            decodeErrorCount = rxDecodeErrors,
            rxActive = rxJob?.isActive == true,
        )

    // ---------- BroadcastInvoker 实现 ----------

    override suspend fun fireBroadcastInvite(sourceId: String, platformUri: String, targetId: String) {
        // ManscdpRouter 已做 busy 检查;到这里说明无活跃 broadcast
        val mode = when (config.audioTransport) {
            com.uvp.sim.config.AudioTransportType.UDP -> RtpMode.UDP
            com.uvp.sim.config.AudioTransportType.TCP_ACTIVE -> RtpMode.TCP_ACTIVE
            com.uvp.sim.config.AudioTransportType.TCP_PASSIVE -> RtpMode.TCP_PASSIVE
        }
        broadcastMode = mode
        // bind RTP receiver
        val receiver = resolvedRtpReceiverFactory(scope)
        val boundPort = runCatching { receiver.bind(mode) }.getOrDefault(-1)
        if (boundPort < 0) {
            runCatching { receiver.close() }
            simEventEmit(SimEvent.BroadcastEnded(BroadcastEndReason.Error, 0))
            SystemLogger.emit(LogLevel.Warning, LogTag.Media, "语音广播绑定本地端口失败 → 放弃 INVITE")
            return
        }
        rtpReceiver = receiver
        broadcastLocalAudioPort = boundPort
        _speakerOn.value = true
        rxPackets = 0L; rxBytes = 0L; rxSeqLost = 0L; rxDecodeErrors = 0L; rxFirstPacketAtMs = 0L
        val deviceSsrc = com.uvp.sim.sip.SsrcUtils.generate(
            realtime = true,
            domainCode = config.server.domain.takeLast(5).padStart(5, '0'),
            sequence = (cseq + 1) and 0x0FFF,
        )
        val sdpTransport = if (mode == RtpMode.UDP) com.uvp.sim.sip.SdpTransport.UDP else com.uvp.sim.sip.SdpTransport.TCP
        val sdpSetup = if (mode == RtpMode.TCP_ACTIVE) com.uvp.sim.sip.SdpTcpSetup.ACTIVE else com.uvp.sim.sip.SdpTcpSetup.PASSIVE
        val sdp = com.uvp.sim.sip.SdpAnswer.buildBroadcastOffer(
            deviceId = targetId,
            localIp = localIp,
            localAudioPort = boundPort,
            deviceSsrc = deviceSsrc,
            transport = sdpTransport,
            tcpSetup = sdpSetup,
        )
        val callIdBc = SipBuilders.randomCallId(localIp)
        val branch = SipBuilders.randomBranch()
        val fromTagBc = SipBuilders.randomTag()
        val inviteCseq = 1
        val invite = SipBuilders.buildOutboundInvite(
            config = config,
            // From/Contact/Subject 的设备侧 ID 用 deviceId(不是 channelId),
            // WVP 侧 InviteRequestProcessor.443 就是靠这个反查 device 的;
            // 之前误传 targetId(channelId)导致 "requesterId 34020000001320000001/34020000001320000001"
            // 两个 ID 相同 → WVP 把 INVITE 忽略 → 等 5s 后返回 403。
            localId = config.device.deviceId,
            channelId = targetId,
            platformUri = platformUri,
            sourceId = sourceId,
            deviceSsrc = deviceSsrc,
            sdpBody = sdp,
            localIp = localIp,
            localPort = localPortProvider(),
            cseq = inviteCseq,
            callId = callIdBc,
            branch = branch,
            fromTag = fromTagBc,
        )
        _current.value = BroadcastDialog(
            callId = callIdBc,
            localTag = fromTagBc,
            remoteTag = null,
            cseq = inviteCseq,
            sourceId = sourceId,
            targetId = targetId,
            sourcePlatformUri = platformUri,
            localAudioPort = boundPort,
            deviceSsrc = deviceSsrc,
            state = BroadcastDialogState.Inviting,
            createdAtMs = nowMs(),
        )
        _state.value = BroadcastDialogState.Inviting
        runCatching {
            outbox.send(invite).getOrThrow()
            simEventEmit(SimEvent.BroadcastInvited(platformUri, boundPort))
            SystemLogger.emit(
                LogLevel.Info, LogTag.Media,
                "反向 INVITE 已发: 通道 $targetId → $platformUri, ssrc=$deviceSsrc 本地音频端口=$boundPort"
            )
        }.onFailure {
            // R2 #1 (verify-followup):用 stateMutex 串行化失败 teardown
            tearDownIfActive()
            simEventEmit(com.uvp.sim.domain.transportErrorOf("send broadcast INVITE", it))
        }
    }

    // ---------- 业务方法 ----------

    private suspend fun handleBroadcastInviteResponse(envelope: com.uvp.sim.network.SipEnvelope, resp: SipResponse, bc: BroadcastDialog) {
        when (resp.statusCode) {
            in 100..199 -> return
            in 200..299 -> {
                val remoteTag = SipHeaderHelpers.parseTag(resp.toHeader() ?: "")
                val contactUri = resp.firstHeader(SipHeader.CONTACT)?.let { SipHeaderHelpers.parseUri(it) } ?: bc.sourcePlatformUri
                sendBroadcastAck(bc, contactUri, remoteTag)
                val answer = runCatching {
                    com.uvp.sim.sip.SdpParser.parseAnswer(resp.body.decodeToString())
                }.getOrNull()
                val codec = answer?.payloadTypes?.firstNotNullOfOrNull { AudioRxCodec.fromPayloadType(it) }
                if (answer == null || codec == null) {
                    sendBroadcastBye(bc.copy(remoteTag = remoteTag), remoteTag)
                    val dur = nowMs() - bc.createdAtMs
                    if (tearDownIfActive()) {
                        simEventEmit(SimEvent.BroadcastEnded(BroadcastEndReason.CodecRejected, dur))
                    }
                    SystemLogger.emit(
                        LogLevel.Warning, LogTag.Media,
                        "语音广播编码不可接受(payloadTypes=${answer?.payloadTypes}) → BYE"
                    )
                    return
                }
                if (broadcastMode == RtpMode.TCP_ACTIVE) {
                    val connected = runCatching {
                        rtpReceiver?.connect(answer.remoteIp, answer.remotePort)
                    }.isSuccess
                    if (!connected) {
                        sendBroadcastBye(bc.copy(remoteTag = remoteTag), remoteTag)
                        val dur = nowMs() - bc.createdAtMs
                        if (tearDownIfActive()) {
                            simEventEmit(SimEvent.BroadcastEnded(BroadcastEndReason.Error, dur))
                        }
                        SystemLogger.emit(
                            LogLevel.Warning, LogTag.Media,
                            "语音广播 TCP 主动连接平台失败 ${answer.remoteIp}:${answer.remotePort} → BYE"
                        )
                        return
                    }
                    SystemLogger.emit(
                        LogLevel.Info, LogTag.Media,
                        "语音广播 TCP 主动已连平台 ${answer.remoteIp}:${answer.remotePort}"
                    )
                }
                _current.value = bc.copy(
                    state = BroadcastDialogState.Talking,
                    remoteTag = remoteTag,
                    remoteAudioHost = answer.remoteIp,
                    remoteAudioPort = answer.remotePort,
                    codec = codec,
                    remoteSourceIp = envelope.sourceIp,
                )
                _state.value = BroadcastDialogState.Talking
                simEventEmit(SimEvent.BroadcastInvited(bc.sourcePlatformUri, bc.localAudioPort))
                SystemLogger.emit(
                    LogLevel.Info, LogTag.Media,
                    "语音广播建立(Talking)codec=${codec.name} mode=$broadcastMode ← ${answer.remoteIp}:${answer.remotePort}"
                )
                startBroadcastRx()
            }
            in 400..699 -> {
                val dur = nowMs() - bc.createdAtMs
                if (tearDownIfActive()) {
                    simEventEmit(SimEvent.BroadcastEnded(BroadcastEndReason.InviteFailed, dur))
                }
                SystemLogger.emit(
                    LogLevel.Warning, LogTag.Media,
                    "语音广播 INVITE 被拒: ${resp.statusCode} ${resp.reasonPhrase}"
                )
            }
        }
    }

    private suspend fun sendBroadcastAck(bc: BroadcastDialog, contactUri: String, remoteTag: String) {
        runCatching {
            val ack = SipBuilders.buildOutboundAck(
                config = config,
                requestUri = contactUri,
                callId = bc.callId,
                cseq = bc.cseq,
                branch = SipBuilders.randomBranch(),
                deviceUri = "sip:${bc.targetId}@${config.server.domain}",
                fromTag = bc.localTag,
                platformUri = bc.sourcePlatformUri,
                remoteTag = remoteTag,
                localIp = localIp,
                localPort = localPortProvider(),
            )
            outbox.send(ack).getOrThrow()
        }.onFailure {
            simEventEmit(com.uvp.sim.domain.transportErrorOf("send broadcast ACK", it))
        }
    }

    private suspend fun sendBroadcastBye(bc: BroadcastDialog, remoteTag: String) {
        runCatching {
            val bye = SipBuilders.buildBye(
                config = config,
                callId = bc.callId,
                cseq = bc.cseq + 1,
                branch = SipBuilders.randomBranch(),
                localUri = "sip:${bc.targetId}@${config.server.domain}",
                localTag = bc.localTag,
                remoteUri = bc.sourcePlatformUri,
                remoteTag = remoteTag,
                remoteTarget = bc.sourcePlatformUri,
                localIp = localIp,
                localPort = localPortProvider(),
            )
            outbox.send(bye).getOrThrow()
        }.onFailure {
            simEventEmit(com.uvp.sim.domain.transportErrorOf("send broadcast BYE", it))
        }
    }

    private suspend fun handleBroadcastBye(envelope: com.uvp.sim.network.SipEnvelope, bye: SipRequest, bc: BroadcastDialog) {
        // R3 #1 (full preset CRITICAL):mid-dialog BYE 必须通过 dialog identity 校验
        // (callId + remoteTag + sourceIp),跟 InviteCoordinator / PlaybackCoordinator 同款。
        // 仅 Call-ID 匹配可被 LAN 抓包后伪造,任意源就能踢掉活跃 broadcast。
        if (!verifyMidDialogOrReject(envelope, bye, bc, op = "BYE")) {
            return
        }
        runCatching {
            val ok = SipBuilders.buildSimple200(bye, userAgent = config.userAgent)
            outbox.send(ok).getOrThrow()
        }.onFailure {
            simEventEmit(com.uvp.sim.domain.transportErrorOf("send broadcast BYE 200", it))
        }
        // R2 #1:跟 stop / shutdown 共享 stateMutex 防双进 teardown。
        val shouldEmit = stateMutex.withLock {
            if (_current.value == null) return@withLock false
            teardownBroadcastMedia()
            _current.value = null
            true
        }
        if (!shouldEmit) return
        val dur = nowMs() - bc.createdAtMs
        simEventEmit(SimEvent.BroadcastEnded(BroadcastEndReason.Remote, dur))
        SystemLogger.emit(LogLevel.Info, LogTag.Media, "平台 BYE 关闭语音广播")
    }

    /**
     * R3 #1:把 BroadcastDialog 抽成 DialogIdentityVerifier.DialogId 做 mid-dialog 守卫。
     * remoteTag 还没收到 200 OK 时为 null,这种情况下任何 BYE 都该拒(对应 MismatchTag)。
     */
    private fun BroadcastDialog.toDialogId(): com.uvp.sim.sip.DialogIdentityVerifier.DialogId =
        com.uvp.sim.sip.DialogIdentityVerifier.DialogId(
            callId = callId,
            localTag = localTag,
            remoteTag = remoteTag ?: "",
            remoteSourceIp = remoteSourceIp,
        )

    /**
     * cross-review R1 #1(CRITICAL/security):SIP 响应必须来自登记平台且 dialog identity 一致。
     *
     * 仅 Call-ID 匹配 → 攻击者可用 LAN 抓包重放 / 伪造 2xx,
     * 控制 SDP 里的 RTP 地址触发到攻击者的 ACK/TCP/RTP。
     *
     * 校验三层:
     * 1. envelope.sourceIp ∈ {config.server.ip} ∪ allowList(传输层)
     * 2. CSeq number == bc.cseq(会话内交易一致性)
     * 3. 已建立后再来的响应必须 To-tag == bc.remoteTag(dialog identity)
     */
    private fun verifyBroadcastResponseOrDrop(
        envelope: com.uvp.sim.network.SipEnvelope,
        resp: SipResponse,
        bc: BroadcastDialog,
        cseqNum: Int?,
    ): Boolean {
        if (!com.uvp.sim.sip.PlatformAuthorizer.isResponseFromAuthorizedPlatform(envelope, config)) {
            SystemLogger.emit(
                LogLevel.Warning, LogTag.Lifecycle,
                "拒绝 BROADCAST INVITE 响应: 未授权来源 sourceIp=${envelope.sourceIp} " +
                    "(expected=${config.server.ip}) callId=${bc.callId} → 丢弃",
            )
            return false
        }
        if (cseqNum != null && cseqNum != bc.cseq) {
            SystemLogger.emit(
                LogLevel.Warning, LogTag.Lifecycle,
                "拒绝 BROADCAST INVITE 响应: CSeq=$cseqNum 期望=${bc.cseq} callId=${bc.callId} → 丢弃",
            )
            return false
        }
        val establishedTag = bc.remoteTag
        if (establishedTag != null) {
            val respTag = SipHeaderHelpers.parseTag(resp.toHeader() ?: "")
            if (respTag.isEmpty() || respTag != establishedTag) {
                SystemLogger.emit(
                    LogLevel.Warning, LogTag.Lifecycle,
                    "拒绝 BROADCAST INVITE 响应: To-tag=$respTag 已建立=${establishedTag} " +
                        "callId=${bc.callId} → 丢弃",
                )
                return false
            }
        }
        return true
    }

    private suspend fun verifyMidDialogOrReject(
        envelope: com.uvp.sim.network.SipEnvelope,
        req: SipRequest,
        bc: BroadcastDialog,
        op: String,
    ): Boolean {
        val result = com.uvp.sim.sip.DialogIdentityVerifier.verify(envelope, bc.toDialogId())
        if (result == com.uvp.sim.sip.DialogIdentityVerifier.VerifyResult.Match) return true
        SystemLogger.emit(
            LogLevel.Warning, LogTag.Lifecycle,
            "拒绝 BROADCAST $op:dialog identity 不匹配($result) → 481 " +
                "[expected callId=${bc.callId} remoteTag=${bc.remoteTag} sourceIp=${bc.remoteSourceIp}, " +
                "got callId=${req.callId()} sourceIp=${envelope.sourceIp}]",
        )
        runCatching {
            val resp = SipBuilders.buildSimpleResponse(
                req, statusCode = 481, reasonPhrase = "Call/Transaction Does Not Exist",
                toTag = SipBuilders.randomTag(),
                userAgent = config.userAgent,
            )
            outbox.send(resp).getOrThrow()
        }
        return false
    }

    private fun startBroadcastRx() {
        val receiver = rtpReceiver ?: return
        // R3 #6 (full preset MEDIUM/performance):原每包 scope.launch{ handleRxPacket } 在 RTP 热路径
        // 每包起一条 coroutine,sustained stream 下调度抖动 + 创建无界 work。
        // 改成 receiver callback 只 trySend 到 bounded channel(64),单 consumer 顺序消费。
        rxPacketChannel = Channel(capacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        rxJob = receiver.start { rtp ->
            rxPacketChannel?.trySend(rtp)
        }
        rxConsumerJob = scope.launch {
            val ch = rxPacketChannel ?: return@launch
            for (rtp in ch) {
                handleRxPacket(rtp)
            }
        }
        val sink = resolvedAudioSinkFactory(8000, 1)
        sink.start()
        audioPlayback = sink
        audioPlayJob = scope.launch {
            for (pcm in audioChannel) {
                if (_speakerOn.value) audioPlayback?.write(pcm)
            }
        }
        rxStatsJob = scope.launch {
            while (isActive) {
                delay(BROADCAST_STATS_INTERVAL_MS)
                val bc = _current.value ?: break
                _current.value = bc.copy(
                    rxPackets = rxPackets, rxBytes = rxBytes,
                    seqLost = rxSeqLost, decodeErrors = rxDecodeErrors,
                    firstPacketAtMs = rxFirstPacketAtMs
                )
                simEventEmit(SimEvent.BroadcastPacketRx(rxPackets, rxBytes, bc.codec.name))
            }
        }
    }

    /**
     * 处理一个收到的 RTP 包(internal,test 注入)。
     */
    internal suspend fun handleRxPacket(rtp: com.uvp.sim.network.RtpPacket) {
        val bc = _current.value ?: return
        val codec = AudioRxCodec.fromPayloadType(rtp.payloadType)
        if (codec == null) {
            rxDecodeErrors++
            return
        }
        val first = rxPackets == 0L
        if (first) rxFirstPacketAtMs = nowMs()
        val pcm = if (codec == AudioRxCodec.PCMA) {
            com.uvp.sim.media.G711.decodeAlaw(rtp.payload)
        } else {
            com.uvp.sim.media.G711.decodeUlaw(rtp.payload)
        }
        audioChannel.trySend(pcm)
        rxPackets++
        rxBytes += rtp.payload.size
        if (first) {
            simEventEmit(SimEvent.BroadcastStarted(rxFirstPacketAtMs - bc.createdAtMs))
        }
    }

    private suspend fun teardownBroadcastMedia() {
        rxStatsJob?.cancel(); rxStatsJob = null
        rxJob?.cancel(); rxJob = null
        rxConsumerJob?.cancel(); rxConsumerJob = null
        rxPacketChannel?.close(); rxPacketChannel = null
        audioPlayJob?.cancel(); audioPlayJob = null
        runCatching { audioPlayback?.stop() }
        audioPlayback = null
        rtpReceiver?.let { runCatching { it.close() } }
        rtpReceiver = null
        broadcastLocalAudioPort = 0
        while (audioChannel.tryReceive().isSuccess) { /* drain */ }
    }
}
