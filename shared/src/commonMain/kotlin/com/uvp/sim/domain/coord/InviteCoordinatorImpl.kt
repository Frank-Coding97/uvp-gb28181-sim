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

    // ----------------------------------------------------------------
    // 通用 helper(InviteCoord 独有的 parseUriHost 留在本类,其它都走 SipHeaderHelpers)
    // ----------------------------------------------------------------

    /** 从 `sip:user@host[:port][;params]` 提取 host 段(不含 port / params)。 */
    private fun parseUriHost(uri: String): String {
        val afterAt = uri.substringAfter("sip:", uri).substringAfter('@', "")
        return afterAt.substringBefore(':').substringBefore(';').substringBefore('>').trim()
    }

    // H-1 / P0-2 来源校验已抽到 com.uvp.sim.sip.PlatformAuthorizer,本类不再持有私有 helper。

    private fun extractInviteTarget(invite: SipRequest): String {
        val ru = invite.requestUri
        val sipBody = ru.substringAfter("sip:", "").substringAfter("sips:", ru.substringAfter("sip:", ""))
        val userHost = if (sipBody.isNotEmpty()) sipBody else ""
        val user = userHost.substringBefore('@', "").substringBefore(';').trim()
        if (user.isNotEmpty()) return user
        val to = invite.toHeader() ?: return ""
        return SipHeaderHelpers.parseUri(to).substringAfter("sip:", "")
            .substringBefore('@', "")
            .substringBefore(';')
            .trim()
    }

    private fun classifyInviteTarget(channelId: String): Pair<Int, String>? {
        if (channelId.isBlank()) return null
        val tree = catalogTree.value
        val node = tree.firstOrNull { it.id == channelId }
            // cross-review R3 #4:未知 channelId 过去返 null → 走接受流,落到默认 channel 推流,
            // SDP/Subject 还原 channelId,模拟器在"不存在"的通道上对外宣称在出流。
            // GB28181 + RFC 3261 应 fail-closed → 404 Not Found 拒绝。
            // 例外:catalogTree 还没拉到 / 主动留空时不强制(node 找不到但 tree 整体也空 = legacy 兜底)。
            ?: return if (tree.isEmpty()) null
                     else 404 to "Channel Not Found"
        return when (node.type) {
            CatalogNodeType.VideoChannel -> null
            CatalogNodeType.AlarmChannel ->
                488 to "Not Acceptable Here (alarm channel does not stream)"
            CatalogNodeType.Device ->
                488 to "Not Acceptable Here (cannot invite device root)"
            CatalogNodeType.BusinessGroup ->
                488 to "Not Acceptable Here (cannot invite business group)"
            CatalogNodeType.VirtualOrg ->
                488 to "Not Acceptable Here (cannot invite virtual org)"
        }
    }

    private suspend fun sendSimpleResponse(req: SipRequest, statusCode: Int, reasonPhrase: String) {
        runCatching {
            val resp = SipBuilders.buildSimpleResponse(
                req,
                statusCode = statusCode,
                reasonPhrase = reasonPhrase,
                toTag = SipBuilders.randomTag(),
            )
            outbox.send(resp).getOrThrow()
        }
    }

    private suspend fun sendBusyResponse(req: SipRequest, reason: String) {
        runCatching {
            val resp = SipBuilders.buildSimpleResponse(
                req, statusCode = 486, reasonPhrase = "Busy Here",
                toTag = SipBuilders.randomTag(),
                userAgent = config.userAgent,
            )
            outbox.send(resp).getOrThrow()
        }
        SystemLogger.emit(LogLevel.Warning, LogTag.Media, "拒绝 INVITE → 486 ($reason)")
    }

    private suspend fun sendNotFoundResponse(req: SipRequest, reason: String) {
        runCatching {
            val resp = SipBuilders.buildSimpleResponse(
                req, statusCode = 487, reasonPhrase = "Request Terminated",
                toTag = SipBuilders.randomTag(),
                userAgent = config.userAgent,
            )
            outbox.send(resp).getOrThrow()
        }
        SystemLogger.emit(LogLevel.Warning, LogTag.Media, "拒绝 INVITE → 487 ($reason)")
    }

    // ----------------------------------------------------------------
    // 占位:大块业务 / broadcast / playback 由后续 Edit 注入
    // ----------------------------------------------------------------
    private suspend fun handleInvite(envelope: com.uvp.sim.network.SipEnvelope, invite: SipRequest) {
        // 按 channelId 类型路由 — 不支持的类型立即 488
        val channelId = extractInviteTarget(invite)
        val rejection = classifyInviteTarget(channelId)
        if (rejection != null) {
            try {
                val resp = SipBuilders.buildSimpleError(
                    request = invite,
                    statusCode = rejection.first,
                    reasonPhrase = rejection.second,
                    toTag = SipBuilders.randomTag(),
                )
                outbox.send(resp).getOrThrow()
                SystemLogger.emit(
                    LogLevel.Info, LogTag.Lifecycle,
                    "拒绝 INVITE: channelId=$channelId → ${rejection.first} ${rejection.second}"
                )
            } catch (e: Throwable) {
                simEventEmit(com.uvp.sim.domain.transportErrorOf("send INVITE reject", e))
            }
            return
        }

        // PR5 T5.4:SDP isPlayback 分流已在 handleInviteMaybe 完成,这里只处理 Play 路径

        // R1 #3:已有活跃流 || 接受中 → 486,两个状态用同一把 mutex 原子检查 + 占用,
        // 防止两路并发 INVITE 同时通过"activeStream == null"检查后各开一路。
        val acquired = mutex.withLock {
            if (activeStream != null || acceptInFlight) {
                false
            } else {
                acceptInFlight = true
                true
            }
        }
        if (!acquired) {
            sendBusyResponse(invite, "已有直播流推送中,拒绝并发第二路")
            return
        }

        try {
            doAcceptInvite(envelope, invite, channelId)
        } finally {
            mutex.withLock { acceptInFlight = false }
        }
    }

    private suspend fun doAcceptInvite(
        envelope: com.uvp.sim.network.SipEnvelope,
        invite: SipRequest,
        channelId: String,
    ) {
        val cid = invite.callId() ?: ""
        simEventEmit(SimEvent.IncomingInvite(cid))

        // cross-review R3 #1:InviteReceived 状态转换过去在函数顶 → 任何"接受失败"
        // (单测无媒体管线 / SDP parse 失败 / RTP bind 失败 / 200 OK 发送失败)都会让
        // SipState 卡在 InCall,阻塞 register/reconnect 状态回归。
        // 现在延后到 200 OK 发送成功后再切 InCall(下面对应行)。SDP / RTP bind 失败路径
        // 在 R2 #4 里已经发了 488/500 错误响应,所以本来就不应再进 InCall;那些路径里的
        // "SipEvent.CallEnded transition"在没有进 InCall 时是 no-op(状态机字面允许)。
        val sender = rtpSenderFactory
        val cam = cameraCapture
        if (sender == null || cam == null) return  // 单测路径,无媒体管线

        cam.setFacing(config.device.facingForChannel(channelId))
        _currentChannelName.value = config.device.channelNameForChannel(channelId)

        val offer = try {
            com.uvp.sim.sip.SdpParser.parseOffer(invite.body)
        } catch (e: Throwable) {
            simEventEmit(com.uvp.sim.domain.transportErrorOf("SDP parse", e))
            // cross-review R2 #4:SDP 解析失败过去裸 return → 平台拿不到任何 SIP 响应,
            // 超时后才放弃,GB28181 协议合规要求"收 INVITE 必有响应"。
            // 发 488 Not Acceptable Here(SDP 描述不可接受),回滚状态到 Registered。
            runCatching {
                outbox.send(SipBuilders.buildSimpleError(invite, 488, "Not Acceptable Here"))
            }
            mutableSipState.value = SipStateMachine.transition(mutableSipState.value, SipEvent.CallEnded)
            return
        }

        val ssrc = offer.ssrc ?: com.uvp.sim.sip.SsrcUtils.generate(
            realtime = true,
            domainCode = config.server.domain.takeLast(5).padStart(5, '0'),
            sequence = (cseq + 1) and 0x0FFF,
        )

        val rtpMode = when (offer.transport) {
            com.uvp.sim.sip.SdpTransport.UDP -> RtpMode.UDP
            com.uvp.sim.sip.SdpTransport.TCP -> when (offer.tcpSetup) {
                com.uvp.sim.sip.SdpTcpSetup.PASSIVE -> RtpMode.TCP_ACTIVE
                com.uvp.sim.sip.SdpTcpSetup.ACTIVE -> RtpMode.TCP_PASSIVE
                com.uvp.sim.sip.SdpTcpSetup.ACTPASS -> RtpMode.TCP_ACTIVE
            }
        }

        // P1-5: TCP_PASSIVE 模式下 expectedClientHost = SDP remote IP(平台真实端点),
        // UDP/TCP_ACTIVE 不需要验证(null)。
        val expectedClientHost = if (rtpMode == RtpMode.TCP_PASSIVE) offer.remoteIp else null
        val rtp = sender(offer.remoteIp, offer.remotePort, rtpMode, expectedClientHost)
        val localRtpPort = try {
            rtp.bindLocalPort()
        } catch (e: Throwable) {
            simEventEmit(com.uvp.sim.domain.transportErrorOf("RTP bind", e))
            // cross-review R2 #4:RTP bind 失败过去裸 return → 平台无 SIP 响应,同款问题。
            // 发 500 Server Internal Error,回滚状态。
            runCatching {
                outbox.send(SipBuilders.buildSimpleError(invite, 500, "Server Internal Error"))
            }
            mutableSipState.value = SipStateMachine.transition(mutableSipState.value, SipEvent.CallEnded)
            return
        }

        val sdpAnswer = com.uvp.sim.sip.SdpAnswer.buildPlayAnswer(
            deviceId = config.device.deviceId,
            localIp = localIp,
            localRtpPort = localRtpPort,
            ssrc = ssrc,
            sessionName = "Play",
            transport = offer.transport,
            tcpSetup = offer.tcpSetup,
            mediaSpec = SipHeaderHelpers.buildSdpMediaSpec(config),
        )
        val deviceContact = "<sip:${config.device.deviceId}@$localIp:${localPortProvider()}>"
        val localToTag = SipBuilders.randomTag()
        val inviteFromUser = SipHeaderHelpers.parseUriUser(
            SipHeaderHelpers.parseUri(invite.fromHeader() ?: ""),
            fallback = config.server.serverId,
        )
        val response = SipBuilders.buildInvite200WithSdp(
            invite = invite,
            deviceContact = deviceContact,
            toTag = localToTag,
            sdpBody = sdpAnswer,
            userAgent = config.userAgent,
            subject = SipBuilders.subject(
                // R2 #7:Subject sender ID 应为通道编码(GB §20.4),
                // 之前用 device.deviceId 导致平台无法关联到真正出流的通道。
                senderId = channelId.ifBlank { config.device.deviceId },
                ssrc = ssrc,
                receiverId = inviteFromUser,
            ),
        )
        val inviteFromHeader = invite.fromHeader() ?: ""
        val inviteToHeader = invite.toHeader() ?: ""
        val inviteContact = invite.firstHeader(SipHeader.CONTACT) ?: ""
        val remoteUri = SipHeaderHelpers.parseUri(inviteFromHeader)
        val remoteTag = SipHeaderHelpers.parseTag(inviteFromHeader)
        val localUri = SipHeaderHelpers.parseUri(inviteToHeader)
        val remoteTarget = SipHeaderHelpers.parseUri(inviteContact).ifEmpty { remoteUri }
        try {
            outbox.send(response).getOrThrow()
        } catch (e: Throwable) {
            simEventEmit(com.uvp.sim.domain.transportErrorOf("send 200 OK", e))
            try { rtp.close() } catch (_: Throwable) {}
            // cross-review R3 #1:200 OK 发送失败也要保证 SipState 不卡 InCall —— 这里
            // InviteReceived 还没切(R3 #1 把切换延后到下面),所以理论无须回滚,但
            // 保留 CallEnded transition 兜底(若状态机已 InCall,会回 Registered;不在 InCall
            // 则 no-op),防御性写法。
            mutableSipState.value = SipStateMachine.transition(mutableSipState.value, SipEvent.CallEnded)
            return
        }

        // cross-review R3 #1:200 OK 发送成功后才正式切 InCall —— 上面所有 pre-publication
        // 失败路径都已发过错误响应 + (隐式)留在 Registered,不会卡 InCall。
        mutableSipState.value = SipStateMachine.transition(mutableSipState.value, SipEvent.InviteReceived)

        // R3 拆分:installAckWatchdog 委派给 dialogHandler,超时回调走主类 cleanupActiveStream
        dialogHandler.installAckWatchdog(cid) { timedOutCid ->
            cleanupActiveStream(
                "ACK timeout (${InviteDialogHandler.ACK_TIMEOUT_MS / 1000}s)",
                sipEvent = SipEvent.CallEnded,
                callId = timedOutCid,
            )
        }

        simEventEmit(SimEvent.StreamStarted(cid, offer.remoteIp, offer.remotePort, ssrc))
        SystemLogger.emit(
            LogLevel.Info, LogTag.Media,
            "开始推流 → ${offer.remoteIp}:${offer.remotePort} ssrc=$ssrc"
        )

        // cross-review R3 #2:media jobs 全部用 LAZY 启动,先**赋 activeStream 发布**,
        // 再 startAll() —— 这样任一 job 立即失败时,onMediaFailure 回调能看到已发布的 activeStream。
        // R3 拆分:launchMediaJobs/launchVideoSendLoop/launchAudioSendLoop/launchRtcpSrLoop
        // 全部迁到 InviteMediaPipeline.build,主类只编排顺序。
        val pipeline = mediaPipeline ?: error("rtpSenderFactory null,不该进 doAcceptInvite 媒体段")
        val media = pipeline.build(cid, rtp, offer, ssrc, cam) { failedCid, reason ->
            cleanupActiveStream(reason, sipEvent = SipEvent.CallEnded, callId = failedCid)
        }

        activeStream = ActiveStream(
            callId = cid,
            ssrc = ssrc,
            rtpSender = rtp,
            streamJob = media.streamJob,
            audioJob = media.audioJob,
            rtcpSender = media.rtcpSender,
            rtcpJob = media.rtcpJob,
            localUri = localUri,
            localTag = localToTag,
            remoteUri = remoteUri,
            remoteTag = remoteTag,
            remoteTarget = remoteTarget,
            channelId = channelId,
            remoteHost = offer.remoteIp,
            remotePort = offer.remotePort,
            // P1-3:记录建立 dialog 时 envelope 真实来源 IP,后续 mid-dialog 校验。
            remoteSourceIp = envelope.sourceIp,
            statsJob = media.statsJob,
        )
        _activeStreamSnapshot.value = ActiveStreamSnapshot(
            callId = cid, channelId = channelId,
            remoteHost = offer.remoteIp, remotePort = offer.remotePort, ssrc = ssrc,
        )
        _state.value = InviteState.Streaming

        // R3 #2:activeStream 发布完成,启动所有 LAZY job。
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

