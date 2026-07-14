package com.uvp.sim.domain.coord

import com.uvp.sim.config.CatalogChangeEvent
import com.uvp.sim.config.CatalogNode
import com.uvp.sim.config.SimConfig
import com.uvp.sim.domain.AlarmHistoryStore
import com.uvp.sim.domain.AlarmRecord
import com.uvp.sim.domain.CatalogTreeStore
import com.uvp.sim.domain.ClockOffset
import com.uvp.sim.domain.DeviceControlActions
import com.uvp.sim.domain.DeviceControlDispatcher
import com.uvp.sim.domain.DeviceControlModel
import com.uvp.sim.domain.location.LocationProvider
import com.uvp.sim.domain.SimEvent
import com.uvp.sim.domain.SubscriptionDialog
import com.uvp.sim.domain.SubscriptionRegistry
import com.uvp.sim.domain.UpgradeProgress
import com.uvp.sim.domain.UpgradeResult
import com.uvp.sim.domain.coord.manscdp.AlarmSubRouter
import com.uvp.sim.domain.coord.manscdp.BroadcastSubRouter
import com.uvp.sim.domain.coord.manscdp.CatalogSubRouter
import com.uvp.sim.domain.coord.manscdp.DeviceControlSubRouter
import com.uvp.sim.domain.coord.manscdp.ManscdpContext
import com.uvp.sim.domain.coord.manscdp.ManscdpDispatcher
import com.uvp.sim.domain.coord.manscdp.ManscdpInternals
import com.uvp.sim.domain.coord.manscdp.SubscriptionNotifyHandler
import com.uvp.sim.gb28181.AlarmNotify
import com.uvp.sim.gb28181.AlarmPayload
import com.uvp.sim.gb28181.CatalogNotifyBuilder
import com.uvp.sim.gb28181.MobilePositionNotify
import com.uvp.sim.network.SipTransport
import com.uvp.sim.observability.LogLevel
import com.uvp.sim.observability.LogTag
import com.uvp.sim.observability.SystemLogger
import com.uvp.sim.recording.RecordingService
import com.uvp.sim.sip.SipBuilders
import com.uvp.sim.sip.SipDialogIdentityService
import com.uvp.sim.sip.SipHeader
import com.uvp.sim.sip.SipHeaderHelpers
import com.uvp.sim.sip.SipMessage
import com.uvp.sim.sip.SipMethod
import com.uvp.sim.sip.SipRequest
import com.uvp.sim.sip.SipResponse
import com.uvp.sim.sip.SubscribeHandler
import com.uvp.sim.sip.SubscribeIntent
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Clock

/**
 * [ManscdpRouter] 真实现(Wave 4 PR-D / P2-1)。
 *
 * 重构(Wave 4):
 *  - 原 1536 行单文件中心交换机,按 GB28181 业务大类拆成 4 个 [com.uvp.sim.domain.coord.manscdp.ManscdpSubRouter]:
 *      - [CatalogSubRouter]:Catalog / DeviceInfo / DeviceStatus / ConfigDownload / RecordInfo / MobilePosition
 *      - [AlarmSubRouter]:AlarmStatus(查询路径;主动报警上报仍在本类)
 *      - [DeviceControlSubRouter]:DeviceControl / PresetQuery / PtzPreciseStatusQuery / CruiseTrack 等
 *      - [BroadcastSubRouter]:Broadcast → BroadcastInvoker
 *  - 本类退化为 [ManscdpDispatcher] 装配 + 主动业务发起 + NOTIFY fan-out:
 *      - 主动:reportSnapshot / reportAlarm / localResetAlarm / triggerMediaStatusAbnormal /
 *              attachSnapshotPipeline / pushCatalogNotify / toggleChannelStatus / 设备升级假进度
 *      - 订阅:SUBSCRIBE 处理(Catalog / Alarm / MobilePosition)+ 各 NOTIFY 发送
 *      - dispatch:onIncoming MESSAGE → [ManscdpDispatcher.route]
 *
 * 跨域决策(plans/refactor-pr3-manscdp-router.md)沿用:
 *   - SubscriptionRegistry / catalogTree / AlarmHistoryStore / deviceControlState 唯一写者是本类(+ SubRouter 共享 ctx)
 *   - 2026-06-26 Wave 2 PR-SN-IDENTITY:用 [SipDialogIdentityService] 取 dialog identity,不缓存
 */
internal class ManscdpRouterImpl(
    private val config: SimConfig,
    private val transport: SipTransport,
    private val outbox: com.uvp.sim.sip.SipOutbox,
    private val scope: CoroutineScope,
    private val localIpProvider: () -> String = { "0.0.0.0" },
    private val localPortProvider: () -> Int = { 5060 },
    private val subscriptionRegistry: SubscriptionRegistry,
    private val catalogTree: MutableStateFlow<List<CatalogNode>>,
    private val alarmHistoryStore: AlarmHistoryStore,
    private val mutableDeviceControlState: MutableStateFlow<DeviceControlModel>,
    private val rebootCallback: suspend () -> Unit,
    private val requestKeyFrameCallback: () -> Unit,
    private val broadcastInvoker: BroadcastInvoker,
    private val recordingService: RecordingService,
    private val mockGps: LocationProvider,
    private val clockOffsetProvider: () -> ClockOffset = { ClockOffset.Empty },
    private val stateRegisteredOrInCall: () -> Boolean = { true },
    private val broadcastBusy: () -> Boolean = { false },
    private val simEventEmit: suspend (SimEvent) -> Unit = {},
    private val identityService: SipDialogIdentityService,
) : ManscdpRouter {

    private val _events = MutableSharedFlow<ManscdpEvent>(extraBufferCapacity = 32)
    override val events: SharedFlow<ManscdpEvent> = _events.asSharedFlow()

    override val deviceControlState: StateFlow<DeviceControlModel> = mutableDeviceControlState

    private val localIp: String get() = localIpProvider()

    // 自管 NOTIFY 序号 — 主动 NOTIFY 路径用(SubRouter 不持有 NOTIFY 序号,只发 Response)
    private var snapshotProtocolSn = 0

    private var snapshotPipeline: com.uvp.sim.snapshot.SnapshotUploadEngine? = null
    private var snapshotCachePipeline: com.uvp.sim.snapshot.JpegLocalCache? = null
    private var upgradeJob: kotlinx.coroutines.Job? = null

    /**
     * DeviceControl 命令分发器(followup A 迁入 Manscdp 域)。
     * 5 个副作用通过 3 个 callback + 本类自有方法解耦。
     */
    private val deviceControlDispatcher: DeviceControlDispatcher by lazy {
        DeviceControlDispatcher(
            state = mutableDeviceControlState,
            config = config,
            actions = object : DeviceControlActions {
                override suspend fun reboot() { rebootCallback() }
                override suspend fun snapshot() { reportSnapshot() }
                override fun requestKeyFrame() { requestKeyFrameCallback() }
                override suspend fun triggerSnapshotConfig(cfg: com.uvp.sim.gb28181.SnapShotConfig) {
                    val pipeline = snapshotPipeline
                    if (pipeline == null) {
                        SystemLogger.emit(
                            LogLevel.Warning, LogTag.Lifecycle,
                            "SnapShotConfig 收到但抓拍管线未挂(平台壳未调 attachSnapshotPipeline);忽略 SessionID=${cfg.sessionId}"
                        )
                        return
                    }
                    SystemLogger.emit(
                        LogLevel.Info, LogTag.Lifecycle,
                        "SnapShotConfig 派发 SessionID=${cfg.sessionId} N=${cfg.snapNum} interval=${cfg.intervalMs}ms"
                    )
                    pipeline.start(cfg)
                }
                override fun startUpgrade(sessionId: String, firmware: String, fileUrl: String) {
                    SystemLogger.emit(
                        LogLevel.Info, LogTag.Lifecycle,
                        "DeviceUpgrade → 启动假进度 SessionID=$sessionId Firmware=$firmware URL=$fileUrl"
                    )
                    upgradeJob?.cancel()
                    upgradeJob = scope.launch { runUpgradeProgressFlow(sessionId, firmware) }
                }
            },
            scope = scope
        )
    }

    /** SubRouter 共享 ctx — 装配 dispatcher 用。 */
    private val subRouterContext: ManscdpContext = ManscdpContext(
        config = config,
        outbox = outbox,
        identityService = identityService,
        subscriptionRegistry = subscriptionRegistry,
        deviceControlState = mutableDeviceControlState,
        catalogTree = catalogTree,
        mockGps = mockGps,
        localIpProvider = localIpProvider,
        localPortProvider = localPortProvider,
        clockOffsetProvider = clockOffsetProvider,
        stateRegisteredOrInCall = stateRegisteredOrInCall,
        simEventEmit = simEventEmit,
    )

    /** NOTIFY 扇出收口到独立 handler(cross-review R1 #3),共享同一份 [subRouterContext]。 */
    private val notifyHandler = SubscriptionNotifyHandler(subRouterContext)

    init {
        // real-gps plan §3.2 — MobilePosition 订阅生命周期与 LocationProvider 启停对齐:
        //  · dialog 移除(cancel / cancelAll / 自然过期)→ 若无 MobilePosition dialog 则 stop
        //  · activate 后主动 syncLocationLifecycle()(见下方 handleSubscribe 处)
        //  · start()/stop() 幂等,重复调无副作用
        subscriptionRegistry.setOnDialogRemoved { removed ->
            if (removed.kind == "MobilePosition") syncLocationLifecycle()
        }
    }

    /**
     * plan §3.2.3 幂等启停策略 —
     * 有 MobilePosition dialog 就 start location provider,否则 stop。start()/stop() 幂等。
     */
    private fun syncLocationLifecycle() {
        val hasMobilePositionSub = subscriptionRegistry
            .dialogsByKind("MobilePosition").isNotEmpty()
        if (hasMobilePositionSub) mockGps.start() else mockGps.stop()
    }

    /** 4 个 SubRouter + dispatcher 装配(lazy 让 deviceControlDispatcher 提前 by lazy 不撞冲突)。 */
    private val dispatcher: ManscdpDispatcher by lazy {
        ManscdpDispatcher(
            routers = listOf(
                CatalogSubRouter(ctx = subRouterContext, recordingService = recordingService),
                AlarmSubRouter(ctx = subRouterContext),
                DeviceControlSubRouter(
                    ctx = subRouterContext,
                    recordingService = recordingService,
                    dispatcher = deviceControlDispatcher,
                    alarmResetCallback = { by -> notifyHandler.pushAlarmResetNotify(by) },
                ),
                BroadcastSubRouter(
                    ctx = subRouterContext,
                    broadcastInvoker = broadcastInvoker,
                    broadcastBusy = broadcastBusy,
                ),
            ),
        )
    }

    override suspend fun onIncoming(envelope: com.uvp.sim.network.SipEnvelope): RoutingResult {
        val msg = envelope.message
        return when (msg) {
            is SipRequest -> when (msg.method) {
                SipMethod.MESSAGE, SipMethod.SUBSCRIBE -> {
                    // P1-3:MANSCDP MESSAGE / SUBSCRIBE 入口业务级来源授权(codex 第二轮 audit §3)。
                    //
                    // 引用:"未授权 MESSAGE/SUBSCRIBE 不应先回 200 再忽略,应返回 403 或直接丢弃"
                    //       "应在 200 之前 ingress 拦截"
                    //
                    // 不通过 → 直接 drop(不发 200 / 403)避免暴露设备存在
                    // (reconnaissance 防御:LAN 扫描器无法用 200 / 403 区分 MANSCDP-capable 设备)。
                    if (!com.uvp.sim.sip.PlatformAuthorizer.isManscdpFromAuthorizedPlatform(envelope, config)) {
                        SystemLogger.emit(
                            LogLevel.Warning, LogTag.Lifecycle,
                            "丢弃未授权 ${msg.method.name}:sourceIp=${envelope.sourceIp} fromUser=${
                                msg.fromHeader()?.let { SipHeaderHelpers.parseUriUser(SipHeaderHelpers.parseUri(it), fallback = "") }
                            }",
                        )
                        return RoutingResult.Handled
                    }
                    when (msg.method) {
                        SipMethod.MESSAGE -> { handleMessage(msg); RoutingResult.Handled }
                        SipMethod.SUBSCRIBE -> { handleSubscribe(msg); RoutingResult.Handled }
                        else -> RoutingResult.Skip
                    }
                }
                else -> RoutingResult.Skip
            }
            is SipResponse -> RoutingResult.Skip
        }
    }

    override suspend fun shutdown() {
        snapshotPipeline = null
        snapshotCachePipeline = null
        upgradeJob?.cancel()
        upgradeJob = null
    }

    // ----------------------------------------------------------------------
    // public 主动业务发起
    // ----------------------------------------------------------------------

    override suspend fun reportSnapshot() {
        if (!stateRegisteredOrInCall()) {
            simEventEmit(SimEvent.TransportError("snapshot: not registered"))
            return
        }
        val id = identityService.nextMessageNotify()
        val sn = id.cseq.toInt()
        val xml = AlarmNotify.buildSnapshotAlarm(config = config, sn = sn.toString())
        val msg = SipBuilders.buildMessage(
            config = config, cseq = sn, callId = id.callId,
            branch = SipBuilders.randomBranch(), fromTag = id.fromTag,
            localIp = localIp, localPort = localPortProvider(),
            xmlBody = xml,
        )
        try {
            outbox.send(msg).getOrThrow()
            simEventEmit(SimEvent.SnapshotReported(sn.toString()))
        } catch (e: Throwable) {
            simEventEmit(SimEvent.TransportError("snapshot send: ${e.message}"))
        }
    }

    override suspend fun reportAlarm(payload: AlarmPayload) {
        if (!stateRegisteredOrInCall()) {
            simEventEmit(SimEvent.TransportError("alarm: not registered"))
            return
        }
        val id = identityService.nextMessageNotify()
        val cseqNow = id.cseq.toInt()
        val sn = cseqNow.toString()
        val body = AlarmNotify.buildAlarm(config, sn, payload)

        val msg = SipBuilders.buildMessage(
            config = config, cseq = cseqNow, callId = id.callId,
            branch = SipBuilders.randomBranch(), fromTag = id.fromTag,
            localIp = localIp, localPort = localPortProvider(),
            xmlBody = body,
        )
        try {
            outbox.send(msg).getOrThrow()
        } catch (e: Throwable) {
            simEventEmit(SimEvent.TransportError("alarm MESSAGE send: ${e.message}"))
        }

        val dialogs = subscriptionRegistry.dialogsByKind("Alarm")
        for (d in dialogs) {
            val updated = subscriptionRegistry.bumpNotify(d.callId) ?: continue
            notifyHandler.sendAlarmNotify(updated, body, sn)
        }

        alarmHistoryStore.append(
            AlarmRecord(payload = payload, firedAtMs = nowMs(), notifiedSubscribers = dialogs.size)
        )
        mutableDeviceControlState.update { it.copy(isAlarming = true) }
        simEventEmit(SimEvent.AlarmFired(payload.type, payload.priority, payload.description))
        SystemLogger.emit(
            LogLevel.Info, LogTag.Network,
            "主动报警 → MESSAGE + ${dialogs.size} 订阅 NOTIFY · ${payload.type.label}/${payload.priority.label}"
        )
    }

    override suspend fun localResetAlarm() {
        mutableDeviceControlState.update { it.copy(isAlarming = false) }
        simEventEmit(SimEvent.AlarmReset(SimEvent.ResetSource.Local))
        SystemLogger.emit(LogLevel.Info, LogTag.Network, "本地复位报警(不走 SIP)")
    }

    override suspend fun triggerMediaStatusAbnormal(notifyType: Int) {
        if (notifyType != com.uvp.sim.sip.MediaStatusNotify.NOTIFY_TYPE_RECORDING_ABNORMAL &&
            notifyType != com.uvp.sim.sip.MediaStatusNotify.NOTIFY_TYPE_STORAGE_FULL
        ) {
            SystemLogger.emit(
                LogLevel.Warning, LogTag.Network,
                "triggerMediaStatusAbnormal: 非法 NotifyType=$notifyType — 仅支持 122/123,忽略"
            )
            return
        }

        if (!stateRegisteredOrInCall()) {
            simEventEmit(SimEvent.TransportError("MediaStatus: not registered"))
            return
        }
        val id = identityService.nextMessageNotify()
        val cseqNow = id.cseq.toInt()
        val mediaStatusSn = notifyHandler.nextNotifySn()

        val xmlBody = com.uvp.sim.sip.MediaStatusNotify.buildXml(
            deviceId = config.device.deviceId,
            sn = mediaStatusSn,
            notifyType = notifyType,
        )
        val msg = SipBuilders.buildMessage(
            config = config, cseq = cseqNow, callId = id.callId,
            branch = SipBuilders.randomBranch(), fromTag = id.fromTag,
            localIp = localIp, localPort = localPortProvider(),
            xmlBody = xmlBody,
        )
        try {
            outbox.send(msg).getOrThrow()
        } catch (e: Throwable) {
            simEventEmit(SimEvent.TransportError("MediaStatus MESSAGE send: ${e.message}"))
        }

        val dialogs = subscriptionRegistry.dialogsByKind("Alarm")
        for (d in dialogs) {
            val updated = subscriptionRegistry.bumpNotify(d.callId) ?: continue
            notifyHandler.sendMediaStatusNotifyToSubscriber(updated, xmlBody)
        }

        simEventEmit(SimEvent.MediaStatusSent(notifyType, dialogs.size))
        SystemLogger.emit(
            LogLevel.Info, LogTag.Network,
            "MediaStatus 通知 → MESSAGE + ${dialogs.size} Alarm 订阅 NOTIFY · NotifyType=$notifyType"
        )
    }

    override fun attachSnapshotPipeline(
        capture: com.uvp.sim.snapshot.SnapshotCapture,
        cache: com.uvp.sim.snapshot.JpegLocalCache,
        httpClient: HttpClient,
    ) {
        snapshotCachePipeline = cache
        val uploader = com.uvp.sim.snapshot.SnapshotHttpUploader(httpClient)
        snapshotPipeline = com.uvp.sim.snapshot.SnapshotUploadEngine(
            takeJpeg = { capture.takeJpeg() },
            writeCache = { id, bytes -> cache.write(id, bytes) },
            uploader = uploader,
            notifySender = { xml -> notifyHandler.sendSnapshotNotify(xml) },
            scope = scope,
            deviceId = config.device.deviceId,
            snAllocator = {
                snapshotProtocolSn += 1
                snapshotProtocolSn.toString()
            },
            uploadAllowList = config.snapshot.uploadAllowList,
            onProgress = { progress ->
                scope.launch {
                    when (progress) {
                        is com.uvp.sim.snapshot.SnapshotProgress.NotifySent ->
                            simEventEmit(
                                SimEvent.SnapshotUploaded(
                                    sessionId = progress.sessionId,
                                    snapShotId = progress.snapShotId,
                                    count = progress.count,
                                    total = progress.total,
                                )
                            )
                        is com.uvp.sim.snapshot.SnapshotProgress.UploadFailedFinal ->
                            simEventEmit(
                                SimEvent.SnapshotUploadFailed(
                                    sessionId = progress.sessionId,
                                    snapShotId = progress.snapShotId,
                                )
                            )
                        is com.uvp.sim.snapshot.SnapshotProgress.CaptureSkipped ->
                            SystemLogger.emit(
                                LogLevel.Warning, LogTag.Media,
                                "snapshot capture returned null: SessionID=${progress.sessionId} ID=${progress.snapShotId}"
                            )
                        is com.uvp.sim.snapshot.SnapshotProgress.UrlRejected ->
                            SystemLogger.emit(
                                LogLevel.Error, LogTag.Network,
                                "SnapShotConfig rejected: URL not in allow list: SessionID=${progress.sessionId} URL=${progress.uploadUrl}"
                            )
                        is com.uvp.sim.snapshot.SnapshotProgress.PerShotError ->
                            SystemLogger.emit(
                                LogLevel.Warning, LogTag.Media,
                                "snapshot 单帧失败,继续下一帧: SessionID=${progress.sessionId} idx=${progress.idx} cause=${progress.cause}"
                            )
                    }
                }
            },
        )
    }

    // ----------------------------------------------------------------------
    // Catalog 主动维护 / NOTIFY fan-out
    // ----------------------------------------------------------------------

    suspend fun updateCatalogTree(tree: List<CatalogNode>) {
        val oldTree = catalogTree.value
        catalogTree.value = tree
        val events = CatalogTreeStore.diff(oldTree, tree)
        if (events.isEmpty()) return
        if (CatalogTreeStore.shouldUseIncremental(events, oldSize = oldTree.size)) {
            pushCatalogIncremental(events)
        } else {
            pushCatalogNotify()
        }
    }

    suspend fun pushCatalogNotify() {
        val dialogs = subscriptionRegistry.dialogsByKind("Catalog")
        for (d in dialogs) {
            val updated = subscriptionRegistry.bumpNotify(d.callId) ?: continue
            notifyHandler.sendCatalogNotify(updated)
        }
    }

    suspend fun pushCatalogIncremental(events: List<CatalogChangeEvent>) {
        if (events.isEmpty()) return
        val dialogs = subscriptionRegistry.dialogsByKind("Catalog")
        for (d in dialogs) {
            val updated = subscriptionRegistry.bumpNotify(d.callId) ?: continue
            notifyHandler.sendCatalogIncrementalNotify(updated, events)
        }
    }

    suspend fun toggleChannelStatus(channelId: String, online: Boolean) {
        val current = catalogTree.value
        val target = current.firstOrNull { it.id == channelId } ?: run {
            SystemLogger.emit(
                LogLevel.Warning, LogTag.Subscription,
                "通道状态切换失败:找不到 channelId=$channelId"
            )
            return
        }
        val newStatus = if (online) "ON" else "OFF"
        if (target.fields["Status"] == newStatus) return

        val updatedNode = target.copy(fields = target.fields + ("Status" to newStatus))
        catalogTree.value = current.map { if (it.id == channelId) updatedNode else it }
        SystemLogger.emit(
            LogLevel.Info, LogTag.Subscription,
            "通道 $channelId Status → $newStatus(简化 NOTIFY fan-out)"
        )
        pushCatalogStatusChange(channelId, online)
    }

    private suspend fun pushCatalogStatusChange(channelId: String, online: Boolean) {
        val dialogs = subscriptionRegistry.dialogsByKind("Catalog")
        for (d in dialogs) {
            val updated = subscriptionRegistry.bumpNotify(d.callId) ?: continue
            notifyHandler.sendCatalogStatusOnlyNotify(updated, channelId, online)
        }
    }

    // ----------------------------------------------------------------------
    // 路由入口
    // ----------------------------------------------------------------------

    private suspend fun handleMessage(message: SipRequest) {
        try {
            val ok = SipBuilders.buildSimple200(
                message, toTag = SipBuilders.randomTag(),
                userAgent = config.userAgent,
            )
            outbox.send(ok).getOrThrow()
        } catch (e: Throwable) {
            simEventEmit(SimEvent.TransportError("send MESSAGE 200: ${e.message}"))
        }
        val xml = message.body.decodeToString()
        val fromUri = message.fromHeader()?.let { SipHeaderHelpers.parseUri(it) }
        // P2-4(2026-06-28):dispatcher.route 返回 false 表示未识别 cmdType。
        // 协议上 200 已先回避免平台死循环重发,但日志 + SimEvent 告警让未知报文可观测。
        val handled = dispatcher.route(xml, fromUri)
        if (!handled) {
            val cmdType = com.uvp.sim.gb28181.ManscdpParser.cmdType(xml)
            val cmdLabel = cmdType ?: "<unparseable>"
            val bodySnippet = xml.take(MANSCDP_UNKNOWN_LOG_SNIPPET_MAX)
            val fromLabel = fromUri ?: "<unknown>"
            SystemLogger.emit(
                LogLevel.Warning, LogTag.Subscription,
                "未识别 MANSCDP cmdType=$cmdLabel from=$fromLabel,已回 200 但不会处理",
                detail = bodySnippet,
                category = com.uvp.sim.observability.ErrorCategory.ProtocolViolation,
            )
            simEventEmit(
                SimEvent.TransportError(
                    "未识别 MANSCDP cmdType=$cmdLabel from=$fromLabel",
                    category = com.uvp.sim.observability.ErrorCategory.ProtocolViolation,
                ),
            )
        }
    }

    private suspend fun handleSubscribe(req: SipRequest) {
        val intent = SubscribeHandler.parse(req, subscriptionRegistry.knownCallIds())
        when (intent) {
            is SubscribeIntent.NewSubscription -> {
                val toTag = SipBuilders.randomTag()
                val ok = SipBuilders.buildSubscribe200(req, toTag, intent.expiresSeconds, userAgent = config.userAgent)
                outbox.send(ok).getOrThrow()

                val dialog = SubscriptionDialog(
                    kind = intent.kind,
                    subscriberUri = intent.subscriberUri,
                    callId = intent.callId,
                    fromTag = intent.fromTag,
                    toTag = toTag,
                    intervalSeconds = intent.intervalSeconds,
                    expiresSeconds = intent.expiresSeconds,
                    remainingSeconds = intent.expiresSeconds,
                )
                subscriptionRegistry.activate(
                    dialog,
                    onExpire = { d ->
                        if (d.kind == "Alarm") {
                            simEventEmit(SimEvent.AlarmSubscriptionExpired(subscriber = d.subscriberUri))
                            SystemLogger.emit(
                                LogLevel.Info, LogTag.Subscription,
                                "报警订阅自然过期: ${d.subscriberUri}",
                            )
                        }
                    },
                ) { d ->
                    when (d.kind) {
                        "Catalog" -> notifyHandler.sendCatalogNotify(d)
                        "Alarm" -> Unit
                        else -> notifyHandler.sendPositionNotify(d)
                    }
                }

                // plan §3.2 — MobilePosition dialog 新加入,同步启动位置监听。
                //   注:onDialogRemoved 只处理移除路径,加入路径要显式调 sync。
                //   MobilePosition 走这条路径最典型,其他 kind 命中 sync 内部判 isEmpty 保持无位置监听。
                if (intent.kind == "MobilePosition") syncLocationLifecycle()

                when (intent.kind) {
                    "Catalog" -> notifyHandler.sendCatalogNotify(dialog)
                    "Alarm" -> Unit
                    else -> notifyHandler.sendPositionNotify(dialog)
                }

                if (intent.kind == "Alarm") {
                    simEventEmit(
                        SimEvent.AlarmSubscribed(
                            subscriber = intent.subscriberUri,
                            expires = intent.expiresSeconds,
                        )
                    )
                } else {
                    simEventEmit(
                        SimEvent.SubscribeReceived(
                            subscriber = intent.subscriberUri,
                            kind = intent.kind,
                            expiresSeconds = intent.expiresSeconds,
                            intervalSeconds = intent.intervalSeconds,
                        )
                    )
                }
                SystemLogger.emit(
                    LogLevel.Info, LogTag.Subscription,
                    "收到${subscriptionLabel(intent.kind)}订阅: from=${intent.subscriberUri}, expires=${intent.expiresSeconds}s, interval=${intent.intervalSeconds}s",
                )
            }

            is SubscribeIntent.Refresh -> {
                // R2 #6:仅靠 Call-ID 不够,fromTag 必须跟原 dialog 一致;否则按 RFC 3261 § 12 视为不同 dialog。
                val existing = subscriptionRegistry.currentDialog(intent.callId)
                if (existing != null && existing.fromTag != intent.fromTag) {
                    SystemLogger.emit(
                        LogLevel.Warning, LogTag.Subscription,
                        "拒绝 SUBSCRIBE refresh: From tag 不匹配 (期望=${existing.fromTag}, 实际=${intent.fromTag})",
                    )
                    sendSubscribeError(req, 481, "Call/Transaction Does Not Exist")
                    return
                }
                val toTag = existing?.toTag ?: SipBuilders.randomTag()
                val ok = SipBuilders.buildSubscribe200(req, toTag, intent.newExpiresSeconds, userAgent = config.userAgent)
                outbox.send(ok).getOrThrow()
                subscriptionRegistry.refresh(intent.callId, intent.newExpiresSeconds)

                val d = subscriptionRegistry.currentDialog(intent.callId)
                simEventEmit(
                    SimEvent.SubscribeRefreshed(
                        subscriber = d?.subscriberUri ?: "",
                        newExpiresSeconds = intent.newExpiresSeconds,
                    )
                )
                SystemLogger.emit(
                    LogLevel.Info, LogTag.Subscription,
                    "${subscriptionLabel(d?.kind)}订阅已刷新: expires=${intent.newExpiresSeconds}s",
                )
            }

            is SubscribeIntent.Cancel -> {
                // R2 #6:同 Refresh,fromTag 不匹配拒绝。
                val d = subscriptionRegistry.currentDialog(intent.callId)
                if (d != null && d.fromTag != intent.fromTag) {
                    SystemLogger.emit(
                        LogLevel.Warning, LogTag.Subscription,
                        "拒绝 SUBSCRIBE cancel: From tag 不匹配 (期望=${d.fromTag}, 实际=${intent.fromTag})",
                    )
                    sendSubscribeError(req, 481, "Call/Transaction Does Not Exist")
                    return
                }
                val toTag = d?.toTag ?: SipBuilders.randomTag()
                val ok = SipBuilders.buildSubscribe200(req, toTag, 0, terminated = true, userAgent = config.userAgent)
                outbox.send(ok).getOrThrow()
                subscriptionRegistry.cancel(intent.callId)

                if (d?.kind == "Alarm") {
                    simEventEmit(SimEvent.AlarmSubscriptionExpired(subscriber = d.subscriberUri))
                } else {
                    simEventEmit(
                        SimEvent.SubscribeExpired(
                            subscriber = d?.subscriberUri ?: "",
                            kind = d?.kind ?: "MobilePosition",
                        )
                    )
                }
                SystemLogger.emit(
                    LogLevel.Info, LogTag.Subscription,
                    "${subscriptionLabel(d?.kind)}订阅已取消: ${d?.subscriberUri}",
                )
            }

            is SubscribeIntent.Reject -> {
                sendSubscribeError(req, intent.statusCode, intent.reason)
            }

            is SubscribeIntent.Ignored -> Unit
        }
    }

    /**
     * R2 #6 helper:回 SUBSCRIBE 错误响应,复制 Via/From/To/Call-ID/CSeq 头。
     * R2 #5:补 Date + User-Agent,跟其他 builder 行为对齐(满足 GB §6.3.3 / RFC 3261 § 20.16)。
     */
    private suspend fun sendSubscribeError(req: SipRequest, statusCode: Int, reason: String) {
        val baseHeaders = req.headers.filter {
            val c = SipHeader.canonicalize(it.name)
            c == SipHeader.VIA || c == SipHeader.FROM || c == SipHeader.TO ||
                c == SipHeader.CALL_ID || c == SipHeader.CSEQ
        }
        val resp = SipResponse(
            statusCode = statusCode,
            reasonPhrase = reason,
            headers = baseHeaders + listOf(
                SipMessage.Header(SipHeader.DATE, com.uvp.sim.sip.SipHeaders.rfc1123Date()),
                SipMessage.Header(SipHeader.USER_AGENT, config.userAgent),
            ),
        )
        outbox.send(resp).getOrThrow()
    }


    // ----------------------------------------------------------------------
    // GB-2022 §9.13 设备升级假进度(本类持有 — 跟 DeviceControlSubRouter 经回调触发)
    // ----------------------------------------------------------------------

    private suspend fun runUpgradeProgressFlow(sessionId: String, firmware: String) {
        try {
            val steps = listOf(0, 30, 60, 100)
            for ((i, percent) in steps.withIndex()) {
                mutableDeviceControlState.update {
                    it.copy(
                        upgradeProgress = UpgradeProgress(
                            sessionId = sessionId,
                            firmware = firmware,
                            percent = percent,
                            result = if (percent < 100) UpgradeResult.InProgress else UpgradeResult.Success,
                        )
                    )
                }
                sendDeviceUpgradeResultNotify(
                    sessionId = sessionId, firmware = firmware, percent = percent,
                    result = if (percent < 100)
                        com.uvp.sim.sip.DeviceUpgradeResultNotify.RESULT_IN_PROGRESS
                    else
                        com.uvp.sim.sip.DeviceUpgradeResultNotify.RESULT_SUCCESS,
                )
                if (i < steps.lastIndex) delay(1_500L)
            }
            delay(5_000L)
            mutableDeviceControlState.update { it.copy(upgradeProgress = null) }
        } catch (e: Throwable) {
            SystemLogger.emit(LogLevel.Warning, LogTag.Lifecycle, "DeviceUpgrade 假进度异常: ${e.message}")
        }
    }

    private suspend fun sendDeviceUpgradeResultNotify(
        sessionId: String, firmware: String, percent: Int, result: Int,
    ) {
        try {
            val id = identityService.nextMessageNotify()
            val cseqNow = id.cseq.toInt()
            val msg = com.uvp.sim.sip.DeviceUpgradeResultNotify.build(
                config = config,
                cseq = cseqNow,
                callId = id.callId,
                branch = SipBuilders.randomBranch(),
                fromTag = id.fromTag,
                localIp = localIp,
                localPort = localPortProvider(),
                sn = (cseqNow and 0xFFFF),
                sessionId = sessionId,
                firmware = firmware,
                result = result,
                percent = percent,
            )
            outbox.send(msg).getOrThrow()
            SystemLogger.emit(
                LogLevel.Info, LogTag.Network,
                "DeviceUpgradeResult NOTIFY → 进度 $percent% result=$result session=$sessionId"
            )
        } catch (e: Throwable) {
            simEventEmit(SimEvent.TransportError("send DeviceUpgradeResult NOTIFY: ${e.message}"))
        }
    }

    // ----------------------------------------------------------------------
    // small utils
    // ----------------------------------------------------------------------

    private fun nowMs(): Long = Clock.System.now().toEpochMilliseconds()

    private fun subscriptionLabel(kind: String?): String = when (kind) {
        "Catalog" -> "目录"
        "Alarm" -> "报警"
        else -> "位置"
    }
}

/** P2-4:未知 cmdType 日志正文截断长度(避免日志爆炸)。 */
private const val MANSCDP_UNKNOWN_LOG_SNIPPET_MAX = 200

