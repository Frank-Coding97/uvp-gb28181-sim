package com.uvp.sim.domain.coord

import com.uvp.sim.config.CatalogNode
import com.uvp.sim.config.CatalogNodeType
import com.uvp.sim.config.SimConfig
import com.uvp.sim.domain.ClockOffset
import com.uvp.sim.domain.SimEvent
import com.uvp.sim.network.RtpMode
import com.uvp.sim.network.RtpSender
import com.uvp.sim.network.SipTransport
import com.uvp.sim.observability.LogLevel
import com.uvp.sim.observability.LogTag
import com.uvp.sim.observability.SystemLogger
import com.uvp.sim.recording.RecordingService
import com.uvp.sim.sip.SipBuilders
import com.uvp.sim.sip.SipEvent
import com.uvp.sim.sip.SipHeader
import com.uvp.sim.sip.SipHeaderHelpers
import com.uvp.sim.sip.SipMessage
import com.uvp.sim.sip.SipMethod
import com.uvp.sim.sip.SipRequest
import com.uvp.sim.sip.SipResponse
import com.uvp.sim.sip.SipState
import com.uvp.sim.sip.SipStateMachine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock

/**
 * [InviteCoordinator] 真实现(PR4 T4.2 GREEN)。
 *
 * 接管 Engine 上 INVITE / ACK / BYE / CANCEL / INFO(MANSRTSP)+ 直播 / 回放推流 +
 * 反向广播 INVITE handshake,共 ~28 个符号 / ~1100 行迁移。
 *
 * 跨域决策(plans/refactor-pr4-invite-coordinator.md):
 *   - 决策 1:实现 [BroadcastInvoker.fireBroadcastInvite],ManscdpRouter 直接注入本类
 *   - 决策 2:Engine.unregister 直接调 invite.stopStream
 *   - 决策 3:Play vs Playback 二分留 Invite 内部(PR5 split 给 PlaybackCoordinator)
 *   - 决策 4:InviteEvent 13 类,Engine 桥接翻译 SimEvent
 *   - 决策 6:RX 媒体链留 Engine,通过 [BroadcastDialogHandshakeListener] 临时桥反向调
 *
 * **守约**:严格按 Engine 现有 early-return 顺序复制,SDP 协商 / SSRC 生成 / RTCP 字段 1:1。
 *
 * 跟 PR2 / PR3 一样,SN 池 / dialog identity 通过 6 个 lambda 跨域共享:
 * cseq / callId / fromTag 三件套 78/85/47 处既有引用通过 Engine 注入读写直达全局池。
 */
internal class InviteCoordinatorImpl(
    private val config: SimConfig,
    private val transport: SipTransport,
    private val outbox: com.uvp.sim.sip.SipOutbox,
    private val scope: CoroutineScope,
    private val localIpProvider: () -> String = { "0.0.0.0" },
    private val localPortProvider: () -> Int = { 5060 },
    private val cameraCapture: com.uvp.sim.camera.CameraCapture? = null,
    private val audioCapture: com.uvp.sim.camera.AudioCapture? = null,
    private val rtpSenderFactory: ((host: String, port: Int, mode: RtpMode, expectedClientHost: String?) -> RtpSender)? = null,
    private val catalogTree: StateFlow<List<CatalogNode>> = MutableStateFlow(emptyList()),
    private val clockOffsetProvider: () -> ClockOffset = { ClockOffset.Empty },
    private val mutableSipState: MutableStateFlow<SipState> = MutableStateFlow(SipState.Disconnected),
    private val simEventEmit: suspend (SimEvent) -> Unit = {},
    cseqProvider: (() -> Int)? = null,
    cseqIncrementer: (() -> Int)? = null,
    callIdProvider: (() -> String?)? = null,
    callIdSetter: ((String) -> Unit)? = null,
    fromTagProvider: (() -> String?)? = null,
    fromTagSetter: ((String) -> Unit)? = null,
) : InviteCoordinator {

    // ---- 公开 state / events ----
    private val _state = MutableStateFlow(InviteState.Idle)
    override val state: StateFlow<InviteState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<InviteEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<InviteEvent> = _events.asSharedFlow()

    private val _activeStreamSnapshot = MutableStateFlow<ActiveStreamSnapshot?>(null)
    override val activeStreamSnapshot: StateFlow<ActiveStreamSnapshot?> =
        _activeStreamSnapshot.asStateFlow()

    private val _currentChannelName = MutableStateFlow(config.device.videoChannelName)
    override val currentChannelName: StateFlow<String> = _currentChannelName.asStateFlow()

    // ---- SN 池 provider 适配(跟 PR2 / PR3 同模式) ----
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
    private fun cseqInc(): Int = cseqIncAndRead()

    private val localIp: String get() = localIpProvider()

    // ---- 内部状态(从 Engine 搬过来) ----
    private val mutex = Mutex()
    private var activeStream: ActiveStream? = null
    // R1 #3:并发 INVITE 竞态守卫。
    // 接受路径从"过滤通过"到"activeStream 赋值"之间没有锁,两路并发 INVITE 都能通过
    // `activeStream == null` 检查并各开一路 RTP / 200 OK。用 mutex 原子检查
    // (activeStream || acceptInFlight),第二路立刻 486 Busy。
    private var acceptInFlight: Boolean = false

    // ---- Handler(R3 拆分:mid-dialog 委派给独立 handler)----
    private val sharedState: InviteSharedState = object : InviteSharedState {
        override val config: SimConfig get() = this@InviteCoordinatorImpl.config
        override val outbox: com.uvp.sim.sip.SipOutbox get() = this@InviteCoordinatorImpl.outbox
        override val scope: CoroutineScope get() = this@InviteCoordinatorImpl.scope
        override val catalogTree: StateFlow<List<CatalogNode>> get() = this@InviteCoordinatorImpl.catalogTree
        override val localIp: String get() = this@InviteCoordinatorImpl.localIp
        override val localPort: Int get() = localPortProvider()
        override fun currentActiveStream(): ActiveStream? = activeStream
        override fun currentSipState(): SipState = mutableSipState.value
        override suspend fun simEventEmit(event: SimEvent) =
            this@InviteCoordinatorImpl.simEventEmit(event)
    }
    private val dialogHandler = InviteDialogHandler(sharedState)
    private val mediaPipeline: InviteMediaPipeline? = rtpSenderFactory?.let { factory ->
        InviteMediaPipeline(
            shared = sharedState,
            rtpSenderFactory = factory,
            audioCapture = audioCapture,
            clockOffsetProvider = clockOffsetProvider,
        )
    }
    private val acceptHandler: InviteAcceptHandler? = rtpSenderFactory?.let { factory ->
        InviteAcceptHandler(
            shared = sharedState,
            rtpSenderFactory = factory,
            cseqProvider = cseqRead,
            localPortProvider = localPortProvider,
        )
    }

    /**
     * cross-review R3 拆分:`internal`(原 private)— 让同 package 的
     * `InviteHandlerResults.AcceptResult.Success(ActiveStream)` 等 handler 能引用。
     * 这是 `InviteCoordinatorImpl` 内部状态,**不**对外暴露(声明仍嵌套于本 Impl),
     * `InviteCoordinator` interface 用 `ActiveStreamSnapshot` 对外。
     */
    internal data class ActiveStream(
        val callId: String,
        val ssrc: String,
        val rtpSender: RtpSender,
        val streamJob: Job,
        val audioJob: Job? = null,
        val statsJob: Job? = null,
        val rtcpSender: RtpSender? = null,
        val rtcpJob: Job? = null,
        val rtpTimestampProvider: () -> Long = { 0L },
        var frameCount: Int = 0,
        var packetCount: Int = 0,
        var octetCount: Long = 0L,
        var lastRtpTimestamp: Long = 0L,
        val localUri: String = "",
        val localTag: String = "",
        val remoteUri: String = "",
        val remoteTag: String = "",
        val remoteTarget: String = "",
        val channelId: String = "",
        val remoteHost: String = "",
        val remotePort: Int = 0,
        // P1-3(Wave 7B):dialog identity 四元组,mid-dialog 请求校验用。
        // 跟 PlaybackCoordinatorImpl.ActivePlayback 同款 — 建立 INVITE 200 时记录 envelope.sourceIp,
        // 后续 ACK / CANCEL / BYE 必须从同一来源进入并带正确 remoteTag,否则 RFC 3261 § 12.2.1.1 → 481。
        val remoteSourceIp: String? = null,
    )

    private fun nowMs(): Long = Clock.System.now().toEpochMilliseconds()

    companion object {
        const val ACK_TIMEOUT_MS: Long = 32_000L
        const val MEDIA_STATS_INTERVAL_MS: Long = 30_000L
        const val RTCP_SR_INTERVAL_MS: Long = 5_000L
    }

    // ---- onIncoming 总分发(T4.2 实装,T4.1 stub 已删) ----
    override suspend fun onIncoming(envelope: com.uvp.sim.network.SipEnvelope): RoutingResult {
        val msg = envelope.message
        return when (msg) {
            is SipResponse -> handleResponse(msg)
            is SipRequest -> handleRequest(envelope, msg)
        }
    }

    override suspend fun shutdown() {
        // R1 #4 (verify-2 follow-up):跟 stopActiveStream 共享 mutex 防双进。
        // R3 拆分:ackTimeoutJob / awaitingAckCallId 现归 dialogHandler 管。
        val active = mutex.withLock {
            val cur = activeStream
            activeStream = null
            cur
        }
        dialogHandler.cancelAckWatchdog()
        active?.let {
            it.statsJob?.cancel()
            it.streamJob.cancel()
            it.audioJob?.cancel()
            it.rtcpJob?.cancel()
            try { it.rtpSender.close() } catch (_: Throwable) {}
            try { it.rtcpSender?.close() } catch (_: Throwable) {}
        }
    }

    // ---- PR5 T5.4:Invite 退出广播域 + 回放域,onIncoming 只处理 INVITE / ACK / BYE / CANCEL ----
    // 真实方法体由后续 Edit 注入,这里先放占位 stub 防编译破
    private suspend fun handleResponse(resp: SipResponse): RoutingResult {
        // PR5 T5.4:广播 INVITE 响应归 BroadcastCoordinator,Invite 不再吃响应
        return RoutingResult.Skip
    }

    private suspend fun handleRequest(envelope: com.uvp.sim.network.SipEnvelope, req: SipRequest): RoutingResult {
        return when (req.method) {
            // PR5 T5.4:Invite 处理 INVITE,内部 SDP isPlayback 时 Skip 让 Engine 路由给 Playback
            SipMethod.INVITE -> handleInviteMaybe(envelope, req)
            SipMethod.ACK -> {
                dialogHandler.handleAck(envelope, req)
                RoutingResult.Handled
            }
            SipMethod.BYE -> {
                val cid = req.callId() ?: ""
                val active = activeStream
                if (active != null && active.callId == cid) {
                    val result = dialogHandler.handleBye(envelope, req)
                    if (result is DialogResult.TerminateDialog) {
                        cleanupActiveStream(result.reason, sipEvent = SipEvent.ByeReceived)
                    }
                    RoutingResult.Handled
                } else RoutingResult.Skip
            }
            SipMethod.CANCEL -> {
                val cid = req.callId() ?: ""
                val result = dialogHandler.handleCancel(envelope, req)
                if (result is DialogResult.TerminateDialog) {
                    // RG-1 race 治本:cleanupActiveStream 容忍 active 为 null
                    // (pre-publication CANCEL 走 awaitingAckCallId 判 pending,
                    //  此时 activeStream 可能还没赋值)
                    cleanupActiveStream(result.reason, sipEvent = SipEvent.CallEnded, callId = cid)
                }
                RoutingResult.Handled
            }
            // INFO 全部 Skip(回放归 Playback,MANSCDP 归 Mans)
            SipMethod.INFO -> RoutingResult.Skip
            else -> RoutingResult.Skip
        }
    }

    /**
     * 统一销毁口(R3 拆分 + RG-1 race 治本):
     * - active != null:正常 teardown(关 RTP / cancel jobs / stop cam+audio + 发 StreamStopped)
     * - active == null:仅清 dialogHandler watchdog(pre-publication CANCEL 走这里)
     *
     * @param reason 销毁原因(给日志 + SimEvent 用)
     * @param sipEvent 切 SipState 用的事件(典型:CallEnded / ByeReceived)
     * @param callId 销毁的 callId(用于 active==null 时仍能 emit 事件)
     */
    private suspend fun cleanupActiveStream(
        reason: String,
        sipEvent: SipEvent,
        callId: String? = null,
    ) {
        val active = mutex.withLock {
            val cur = activeStream
            activeStream = null
            cur
        }
        dialogHandler.cancelAckWatchdog()
        if (active != null) {
            _activeStreamSnapshot.value = null
            active.streamJob.cancel()
            active.audioJob?.cancel()
            active.statsJob?.cancel()
            active.rtcpJob?.cancel()
            try { active.rtpSender.close() } catch (_: Throwable) {}
            try { active.rtcpSender?.close() } catch (_: Throwable) {}
            try { cameraCapture?.stop() } catch (_: Throwable) {}
            try { audioCapture?.stop() } catch (_: Throwable) {}
            simEventEmit(
                SimEvent.StreamStopped(
                    callId = active.callId,
                    frameCount = active.frameCount,
                    packetCount = active.packetCount,
                    reason = reason,
                )
            )
            SystemLogger.emit(
                LogLevel.Info, LogTag.Media,
                "停止推流 ($reason): ${active.frameCount} 帧 / ${active.packetCount} 包"
            )
        }
        if (mutableSipState.value == SipState.InCall) {
            mutableSipState.value = SipStateMachine.transition(mutableSipState.value, sipEvent)
        }
        val cidForEvent = active?.callId ?: callId ?: ""
        if (cidForEvent.isNotEmpty()) {
            simEventEmit(SimEvent.CallEnded(cidForEvent, reason))
        }
        _state.value = InviteState.Idle
    }

    /**
     * INVITE 路由:SDP probe 后 Playback 类 Skip 给 Engine 路由 PlaybackCoordinator,
     * 直播路径在本类完成。
     *
     * H-1 + P0-2:在路由前先校验 envelope.sourceIp + From host,LAN 内任何攻击者
     * 伪造的 INVITE 直接 403 拒绝,不进入后续 SDP 解析 / 推流流程。
     *
     * Wave 7B P0-2:guard 改走共享 [PlatformAuthorizer],跟 Playback 复用同一份判定。
     */
    private suspend fun handleInviteMaybe(envelope: com.uvp.sim.network.SipEnvelope, req: SipRequest): RoutingResult {
        if (!com.uvp.sim.sip.PlatformAuthorizer.isInviteFromAuthorizedPlatform(envelope, config)) {
            SystemLogger.emit(
                LogLevel.Warning, LogTag.Lifecycle,
                "拒绝 INVITE: 未授权来源(sourceIp=${envelope.sourceIp}, expected domain=${config.server.domain}) → 403"
            )
            sendSimpleResponse(req, statusCode = 403, reasonPhrase = "Forbidden")
            return RoutingResult.Handled
        }
        val isPlayback = try {
            com.uvp.sim.sip.SdpPlaybackParser.parse(req.body).isPlayback
        } catch (_: Throwable) {
            // cross-review R1 #5:SDP 解析失败过去 `catch { false }` 把畸形 INVITE 当 live 处理 →
            // 路由边界塌陷(畸形输入静默走直播分支,不回任何 SIP 错误)。
            // fail-closed:解析失败回 400 Bad Request 并 Handled,不进任何媒体路径。
            SystemLogger.emit(
                LogLevel.Warning, LogTag.Lifecycle,
                "拒绝 INVITE: SDP 解析失败(sourceIp=${envelope.sourceIp}) → 400"
            )
            sendSimpleResponse(req, statusCode = 400, reasonPhrase = "Bad Request")
            return RoutingResult.Handled
        }
        return if (isPlayback) RoutingResult.Skip
        else { handleInvite(envelope, req); RoutingResult.Handled }
    }

    /**
     * 通道分类内联版(无媒体管线时也可用)。
     * 跟 AcceptHandler.classifyInviteTarget 等价 — 后者读 InviteSharedState.catalogTree,
     * 这里读主类 catalogTree,内容一样。
     */
    private fun classifyInviteTargetInline(channelId: String): Pair<Int, String>? {
        if (channelId.isBlank()) return null
        val tree = catalogTree.value
        val node = tree.firstOrNull { it.id == channelId }
            ?: return if (tree.isEmpty()) null else 404 to "Channel Not Found"
        return when (node.type) {
            CatalogNodeType.VideoChannel -> null
            CatalogNodeType.AlarmChannel -> 488 to "Not Acceptable Here (alarm channel does not stream)"
            CatalogNodeType.Device -> 488 to "Not Acceptable Here (cannot invite device root)"
            CatalogNodeType.BusinessGroup -> 488 to "Not Acceptable Here (cannot invite business group)"
            CatalogNodeType.VirtualOrg -> 488 to "Not Acceptable Here (cannot invite virtual org)"
        }
    }

    /** 从 INVITE 请求 URI 提取 channel ID(GB28181 §20.4 / §C.2.3)。 */
    private fun extractInviteTargetInline(invite: SipRequest): String {
        val uri = invite.requestUri ?: return ""
        val atIdx = uri.indexOf('@')
        val schemeIdx = uri.indexOf(':')
        return if (schemeIdx >= 0 && atIdx > schemeIdx) {
            uri.substring(schemeIdx + 1, atIdx)
        } else ""
    }

    /**
     * 403 Forbidden / 400 Bad Request 等顶层拒绝,保留给 handleInviteMaybe 用。
     */
    private suspend fun sendSimpleResponse(req: SipRequest, statusCode: Int, reasonPhrase: String) {
        runCatching {
            val resp = SipBuilders.buildSimpleResponse(
                req,
                statusCode = statusCode,
                reasonPhrase = reasonPhrase,
                toTag = SipBuilders.randomTag(),
                userAgent = config.userAgent,
            )
            outbox.send(resp).getOrThrow()
        }
    }

    /**
     * R3 拆分 + spec G1 主类壳化:直播 INVITE 接受路径委派 [InviteAcceptHandler]。
     *
     * 编排顺序(R3 #1 #2 race 治本):
     *   1. accept 守卫(acceptInFlight)+ 通道分类拒绝
     *   2. acceptHandler.handleInvite → AcceptResult
     *   3. Success → 启动 media(LAZY)→ 先发布 activeStream → startAll() → 切 SipState.InCall
     *   4. Rejected/Failed → 不切 InCall,不赋 activeStream(保留 Registered)
     */
    private suspend fun handleInvite(envelope: com.uvp.sim.network.SipEnvelope, invite: SipRequest) {
        // 通道分类拒绝(R3 #4: 未知 channel 404)— **不依赖媒体管线**,单测路径也要走。
        // 直接用主类内联版而非 acceptHandler(因为 handler 可能为 null);classifyInviteTarget
        // 只读 catalogTree + config,无副作用,所以内联跟 handler 版等价。
        val channelId = extractInviteTargetInline(invite)
        classifyInviteTargetInline(channelId)?.let { (code, reason) ->
            runCatching {
                val resp = SipBuilders.buildSimpleError(
                    request = invite,
                    statusCode = code,
                    reasonPhrase = reason,
                    toTag = SipBuilders.randomTag(),
                )
                outbox.send(resp).getOrThrow()
                SystemLogger.emit(
                    LogLevel.Info, LogTag.Lifecycle,
                    "拒绝 INVITE: channelId=$channelId → $code $reason"
                )
            }.onFailure { e ->
                simEventEmit(com.uvp.sim.domain.transportErrorOf("send INVITE reject", e))
            }
            return
        }

        val handler = acceptHandler
        val pipeline = mediaPipeline
        val cam = cameraCapture
        if (handler == null || pipeline == null || cam == null) {
            // 单测路径:无媒体管线,通道已分类通过,只发 IncomingInvite 事件
            simEventEmit(SimEvent.IncomingInvite(invite.callId() ?: ""))
            return
        }

        // R1 #3:accept 守卫,防止并发第二路
        val acquired = mutex.withLock {
            if (activeStream != null || acceptInFlight) false
            else { acceptInFlight = true; true }
        }
        if (!acquired) {
            handler.sendRejection(invite, 486, "Busy Here")
            SystemLogger.emit(
                LogLevel.Warning, LogTag.Media,
                "拒绝 INVITE → 486 (已有直播流推送中,拒绝并发第二路)"
            )
            return
        }

        simEventEmit(SimEvent.IncomingInvite(invite.callId() ?: ""))

        try {
            val result = handler.handleInvite(envelope, invite, channelId, cam) { newName ->
                _currentChannelName.value = newName
            }
            when (result) {
                is AcceptResult.Success -> finalizeAcceptedInvite(envelope, result.accepted, pipeline)
                is AcceptResult.Rejected, is AcceptResult.Failed -> {
                    // 不切 InCall,不赋 activeStream(主类保持 Registered)
                }
            }
        } finally {
            mutex.withLock { acceptInFlight = false }
        }
    }

    /**
     * accept 成功后的编排:启动 media(LAZY)→ 先发布 activeStream → startAll() → 切 SipState.InCall。
     * R3 #2 race 治本核心:activeStream 在 startAll 之前发布,任一 job 立即失败时 onMediaFailure
     * 回调能看到已发布的 activeStream。
     */
    private suspend fun finalizeAcceptedInvite(
        envelope: com.uvp.sim.network.SipEnvelope,
        accepted: AcceptedInvite,
        pipeline: InviteMediaPipeline,
    ) {
        val media = pipeline.build(accepted.cid, accepted.rtp, accepted.offer, accepted.ssrc, accepted.cam) { failedCid, reason ->
            cleanupActiveStream(reason, sipEvent = SipEvent.CallEnded, callId = failedCid)
        }

        // 先发布 activeStream(R3 #2 race 治本)
        activeStream = ActiveStream(
            callId = accepted.cid,
            ssrc = accepted.ssrc,
            rtpSender = accepted.rtp,
            streamJob = media.streamJob,
            audioJob = media.audioJob,
            rtcpSender = media.rtcpSender,
            rtcpJob = media.rtcpJob,
            localUri = accepted.localUri,
            localTag = accepted.localTag,
            remoteUri = accepted.remoteUri,
            remoteTag = accepted.remoteTag,
            remoteTarget = accepted.remoteTarget,
            channelId = accepted.channelId,
            remoteHost = accepted.offer.remoteIp,
            remotePort = accepted.offer.remotePort,
            remoteSourceIp = accepted.remoteSourceIp,
            statsJob = media.statsJob,
        )
        _activeStreamSnapshot.value = ActiveStreamSnapshot(
            callId = accepted.cid, channelId = accepted.channelId,
            remoteHost = accepted.offer.remoteIp, remotePort = accepted.offer.remotePort, ssrc = accepted.ssrc,
        )
        _state.value = InviteState.Streaming

        // R3 #1:200 OK 已发(handler 内),activeStream 发布完成,现在切 SipState + start media
        mutableSipState.value = SipStateMachine.transition(mutableSipState.value, SipEvent.InviteReceived)

        dialogHandler.installAckWatchdog(accepted.cid) { timedOutCid ->
            cleanupActiveStream(
                "ACK timeout (${InviteDialogHandler.ACK_TIMEOUT_MS / 1000}s)",
                sipEvent = SipEvent.CallEnded,
                callId = timedOutCid,
            )
        }

        simEventEmit(SimEvent.StreamStarted(accepted.cid, accepted.offer.remoteIp, accepted.offer.remotePort, accepted.ssrc))
        SystemLogger.emit(
            LogLevel.Info, LogTag.Media,
            "开始推流 → ${accepted.offer.remoteIp}:${accepted.offer.remotePort} ssrc=${accepted.ssrc}"
        )

        // R3 #2:activeStream 已发布,启动所有 LAZY job
        media.startAll()
    }

    /**
     * R3 拆分 — stopActiveStream 是 cleanupActiveStream 的薄封装,保留供 stopStream 内部调。
     * 行为完全等价于 cleanupActiveStream(reason, CallEnded, callId)。
     */
    private suspend fun stopActiveStream(callId: String, reason: String) {
        cleanupActiveStream(reason, sipEvent = SipEvent.CallEnded, callId = callId)
    }

    override suspend fun stopStream(reason: String) {
        val active = activeStream ?: return
        if (active.remoteUri.isEmpty() || active.remoteTag.isEmpty()) {
            stopActiveStream(active.callId, reason)
            return
        }
        try {
            val cseqLocal = cseqInc()
            val branch = SipBuilders.randomBranch()
            val bye = SipBuilders.buildBye(
                config = config,
                callId = active.callId,
                cseq = cseqLocal,
                branch = branch,
                localUri = active.localUri,
                localTag = active.localTag,
                remoteUri = active.remoteUri,
                remoteTag = active.remoteTag,
                remoteTarget = active.remoteTarget,
                localIp = localIp,
                localPort = localPortProvider(),
            )
            outbox.send(bye).getOrThrow()
            SystemLogger.emit(
                LogLevel.Info, LogTag.Lifecycle,
                "主动 BYE 终止推流: $reason"
            )
        } catch (e: Throwable) {
            simEventEmit(com.uvp.sim.domain.transportErrorOf("send BYE", e))
        }
        stopActiveStream(active.callId, reason)
        if (mutableSipState.value == SipState.InCall) {
            mutableSipState.value = SipStateMachine.transition(mutableSipState.value, SipEvent.CallEnded)
        }
    }
}

