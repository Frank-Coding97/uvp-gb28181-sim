package com.uvp.sim.domain.coord

import com.uvp.sim.config.CatalogChangeEvent
import com.uvp.sim.config.CatalogNode
import com.uvp.sim.config.CatalogNodeType
import com.uvp.sim.config.SimConfig
import com.uvp.sim.domain.AlarmHistoryStore
import com.uvp.sim.domain.AlarmRecord
import com.uvp.sim.domain.CatalogTreeStore
import com.uvp.sim.domain.ClockOffset
import com.uvp.sim.domain.DeviceControlDispatcher
import com.uvp.sim.domain.DeviceControlActions
import com.uvp.sim.domain.DeviceControlState
import com.uvp.sim.domain.MockGpsSource
import com.uvp.sim.domain.PtzPose
import com.uvp.sim.domain.SimEvent
import com.uvp.sim.domain.SubscriptionDialog
import com.uvp.sim.domain.SubscriptionRegistry
import com.uvp.sim.domain.UpgradeProgress
import com.uvp.sim.domain.UpgradeResult
import com.uvp.sim.gb28181.AlarmPayload
import com.uvp.sim.gb28181.MobilePositionNotify
import com.uvp.sim.network.SipTransport
import com.uvp.sim.observability.LogLevel
import com.uvp.sim.observability.LogTag
import com.uvp.sim.observability.SystemLogger
import com.uvp.sim.recording.RecordingService
import com.uvp.sim.sip.SipBuilders
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
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * [ManscdpRouter] 真实现(PR3 T3.2 GREEN)。
 *
 * 接管 Engine 的 MANSCDP 路由 + 19 个主动 / 应答 SIP 路径。
 *
 * 跨域决策(plans/refactor-pr3-manscdp-router.md):
 *   - 决策 1:SubscriptionRegistry / catalogTree(MutableStateFlow)/ AlarmHistoryStore /
 *     deviceControlState(MutableStateFlow)由 Engine 持有注入,Router 是唯一写者;
 *     Engine 只在 unregister/shutdown 调 cancelAll 不构成双写
 *   - 决策 2:fan-out 路径完成后通过 [simEventEmit] lambda 桥接 SimEvent,保证事件顺序
 *   - SN 池跨域共享:6 个 lambda 注入,跟 PR2 RegistrationCoordinator 同模式
 *
 * **stateGuard / clockOffsetProvider / activeStreamHasInvite** 是协议层 guard 依赖:
 *   - stateGuard:reportSnapshot/reportAlarm/triggerMediaStatusAbnormal 仅 Registered/InCall 才发
 *   - clockOffsetProvider:DeviceStatus / MobilePosition 等 Response 用 currentLocalIso 取设备时间
 *   - 无 broadcast / no activeStream — 这些归 InviteCoordinator/BroadcastCoordinator,本类不碰
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
    private val mutableDeviceControlState: MutableStateFlow<DeviceControlState>,
    // DeviceControlDispatcher 内部装配(followup A);2 个 callback 注入由 Engine 提供
    private val rebootCallback: suspend () -> Unit,
    private val requestKeyFrameCallback: () -> Unit,
    private val broadcastInvoker: BroadcastInvoker,
    private val recordingService: RecordingService,
    private val mockGps: MockGpsSource,
    private val clockOffsetProvider: () -> ClockOffset = { ClockOffset.Empty },
    private val stateRegisteredOrInCall: () -> Boolean = { true },
    private val broadcastBusy: () -> Boolean = { false },
    private val simEventEmit: suspend (SimEvent) -> Unit = {},
    cseqProvider: (() -> Int)? = null,
    cseqIncrementer: (() -> Int)? = null,
    callIdProvider: (() -> String?)? = null,
    callIdSetter: ((String) -> Unit)? = null,
    fromTagProvider: (() -> String?)? = null,
    fromTagSetter: ((String) -> Unit)? = null,
) : ManscdpRouter {

    private val _events = MutableSharedFlow<ManscdpEvent>(extraBufferCapacity = 32)
    override val events: SharedFlow<ManscdpEvent> = _events.asSharedFlow()

    override val deviceControlState: StateFlow<DeviceControlState> = mutableDeviceControlState

    // SN 池 provider 适配(跟 PR2 RegistrationCoordinator 同模式)
    private var internalCseq = 0
    private var internalCallId: String? = null
    private var internalFromTag: String? = null

    private val cseqRead: () -> Int = cseqProvider ?: { internalCseq }
    private val cseqIncAndRead: () -> Int = cseqIncrementer ?: {
        internalCseq += 1
        internalCseq
    }
    private val callIdRead: () -> String? = callIdProvider ?: { internalCallId }
    private val callIdWrite: (String) -> Unit = callIdSetter ?: { internalCallId = it }
    private val fromTagRead: () -> String? = fromTagProvider ?: { internalFromTag }
    private val fromTagWrite: (String) -> Unit = fromTagSetter ?: { internalFromTag = it }

    private val cseq: Int get() = cseqRead()
    private fun cseqInc(): Int = cseqIncAndRead()
    private val callId: String? get() = callIdRead()
    private val fromTag: String? get() = fromTagRead()
    private val localIp: String get() = localIpProvider()

    // Router 自管的 NOTIFY 序号
    private var notifySn = 0
    private var catalogNotifySn = 0
    private var alarmNotifySn = 0

    // Snapshot pipeline(由 attachSnapshotPipeline 注入)
    private var snapshotPipeline: com.uvp.sim.snapshot.SnapshotUploadEngine? = null
    private var snapshotCachePipeline: com.uvp.sim.snapshot.JpegLocalCache? = null

    /**
     * DeviceControl 命令分发器(followup A 迁入 Manscdp 域)。
     *
     * 5 个副作用通过 3 个 callback + 本类自有方法解耦:
     *  - reboot:[rebootCallback](Engine 自己 unregister + register)
     *  - snapshot:本类 [reportSnapshot]
     *  - requestKeyFrame:[requestKeyFrameCallback](Engine 透传 cameraCapture)
     *  - triggerSnapshotConfig:本类 [snapshotPipeline]
     *  - startUpgrade:本类 [runUpgradeProgressFlow](followup E,5s 内 4 条 DeviceUpgradeResult NOTIFY 0/30/60/100)
     */
    private val deviceControlDispatcher: DeviceControlDispatcher by lazy {
        DeviceControlDispatcher(
            state = mutableDeviceControlState,
            config = config,
            actions = object : DeviceControlActions {
                override suspend fun reboot() {
                    rebootCallback()
                }
                override suspend fun snapshot() {
                    reportSnapshot()
                }
                override fun requestKeyFrame() {
                    requestKeyFrameCallback()
                }
                override suspend fun triggerSnapshotConfig(cfg: com.uvp.sim.gb28181.SnapShotConfig) {
                    val pipeline = snapshotPipeline
                    if (pipeline == null) {
                        SystemLogger.emit(
                            LogLevel.Warning,
                            LogTag.Lifecycle,
                            "SnapShotConfig 收到但抓拍管线未挂(平台壳未调 attachSnapshotPipeline);忽略 SessionID=${cfg.sessionId}"
                        )
                        return
                    }
                    SystemLogger.emit(
                        LogLevel.Info,
                        LogTag.Lifecycle,
                        "SnapShotConfig 派发 SessionID=${cfg.sessionId} N=${cfg.snapNum} interval=${cfg.intervalMs}ms"
                    )
                    pipeline.start(cfg)
                }
                override fun startUpgrade(sessionId: String, firmware: String, fileUrl: String) {
                    SystemLogger.emit(
                        LogLevel.Info, LogTag.Lifecycle,
                        "DeviceUpgrade → 启动假进度 SessionID=$sessionId Firmware=$firmware URL=$fileUrl"
                    )
                    scope.launch { runUpgradeProgressFlow(sessionId, firmware) }
                }
            },
            scope = scope
        )
    }

    override suspend fun onIncoming(msg: SipMessage): RoutingResult {
        return when (msg) {
            is SipRequest -> when (msg.method) {
                SipMethod.MESSAGE -> {
                    handleMessage(msg)
                    RoutingResult.Handled
                }
                SipMethod.SUBSCRIBE -> {
                    handleSubscribe(msg)
                    RoutingResult.Handled
                }
                else -> RoutingResult.Skip
            }
            is SipResponse -> RoutingResult.Skip
        }
    }

    override suspend fun shutdown() {
        // Router 不持任何独立协程(回调全在 caller scope 上),仅清空 snapshot 引用避免泄漏
        snapshotPipeline = null
        snapshotCachePipeline = null
    }

    // ----------------------------------------------------------------------
    // public 主动业务发起
    // ----------------------------------------------------------------------

    override suspend fun reportSnapshot() {
        if (!stateRegisteredOrInCall()) {
            simEventEmit(SimEvent.TransportError("snapshot: not registered"))
            return
        }
        val sn = cseqInc()
        val branch = SipBuilders.randomBranch()
        val callIdNow = callId ?: SipBuilders.randomCallId(localIp).also { callIdWrite(it) }
        val fromTagNow = fromTag ?: SipBuilders.randomTag().also { fromTagWrite(it) }
        val xml = com.uvp.sim.gb28181.AlarmNotify.buildSnapshotAlarm(config = config, sn = sn.toString())
        val msg = SipBuilders.buildMessage(
            config = config,
            cseq = sn,
            callId = callIdNow,
            branch = branch,
            fromTag = fromTagNow,
            localIp = localIp,
            localPort = localPortProvider(),
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
        val dialogs: List<SubscriptionDialog>
        val sn: String
        if (!stateRegisteredOrInCall()) {
            simEventEmit(SimEvent.TransportError("alarm: not registered"))
            return
        }
        val cseqNow = cseqInc()
        sn = cseqNow.toString()
        val body = com.uvp.sim.gb28181.AlarmNotify.buildAlarm(config, sn, payload)

        // 路 A — MESSAGE 给注册中心
        val branch = SipBuilders.randomBranch()
        val callIdNow = callId ?: SipBuilders.randomCallId(localIp).also { callIdWrite(it) }
        val fromTagNow = fromTag ?: SipBuilders.randomTag().also { fromTagWrite(it) }
        val msg = SipBuilders.buildMessage(
            config = config,
            cseq = cseqNow,
            callId = callIdNow,
            branch = branch,
            fromTag = fromTagNow,
            localIp = localIp,
            localPort = localPortProvider(),
            xmlBody = body,
        )
        try {
            outbox.send(msg).getOrThrow()
        } catch (e: Throwable) {
            simEventEmit(SimEvent.TransportError("alarm MESSAGE send: ${e.message}"))
        }

        // 路 B — NOTIFY 给每个活跃 Alarm 订阅人
        dialogs = subscriptionRegistry.dialogsByKind("Alarm")
        for (d in dialogs) {
            val updated = subscriptionRegistry.bumpNotify(d.callId) ?: continue
            sendAlarmNotify(updated, body, sn)
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

        val dialogs: List<SubscriptionDialog>
        if (!stateRegisteredOrInCall()) {
            simEventEmit(SimEvent.TransportError("MediaStatus: not registered"))
            return
        }
        val cseqNow = cseqInc()
        notifySn += 1

        val branch = SipBuilders.randomBranch()
        val callIdNow = callId ?: SipBuilders.randomCallId(localIp).also { callIdWrite(it) }
        val fromTagNow = fromTag ?: SipBuilders.randomTag().also { fromTagWrite(it) }
        val xmlBody = com.uvp.sim.sip.MediaStatusNotify.buildXml(
            deviceId = config.device.deviceId,
            sn = notifySn,
            notifyType = notifyType,
        )
        val msg = SipBuilders.buildMessage(
            config = config,
            cseq = cseqNow,
            callId = callIdNow,
            branch = branch,
            fromTag = fromTagNow,
            localIp = localIp,
            localPort = localPortProvider(),
            xmlBody = xmlBody,
        )
        try {
            outbox.send(msg).getOrThrow()
        } catch (e: Throwable) {
            simEventEmit(SimEvent.TransportError("MediaStatus MESSAGE send: ${e.message}"))
        }

        dialogs = subscriptionRegistry.dialogsByKind("Alarm")
        for (d in dialogs) {
            val updated = subscriptionRegistry.bumpNotify(d.callId) ?: continue
            sendMediaStatusNotifyToSubscriber(updated, xmlBody)
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
            notifySender = { xml -> sendSnapshotNotify(xml) },
            scope = scope,
            deviceId = config.device.deviceId,
            snAllocator = { cseqInc().toString() },
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
                        is com.uvp.sim.snapshot.SnapshotProgress.CaptureSkipped -> {
                            SystemLogger.emit(
                                LogLevel.Warning, LogTag.Media,
                                "snapshot capture returned null: SessionID=${progress.sessionId} ID=${progress.snapShotId}"
                            )
                        }
                    }
                }
            },
        )
    }

    private fun nowMs(): Long = Clock.System.now().toEpochMilliseconds()

    private fun subscriptionLabel(kind: String?): String = when (kind) {
        "Catalog" -> "目录"
        "Alarm" -> "报警"
        else -> "位置"
    }

    private fun currentLocalIso(): String {
        val ms = clockOffsetProvider().adjustedNowMs()
        val now = Instant.fromEpochMilliseconds(ms)
        val tz = TimeZone.currentSystemDefault()
        val ldt = now.toLocalDateTime(tz)
        return buildString {
            append(ldt.year.toString().padStart(4, '0'))
            append('-')
            append(ldt.monthNumber.toString().padStart(2, '0'))
            append('-')
            append(ldt.dayOfMonth.toString().padStart(2, '0'))
            append('T')
            append(ldt.hour.toString().padStart(2, '0'))
            append(':')
            append(ldt.minute.toString().padStart(2, '0'))
            append(':')
            append(ldt.second.toString().padStart(2, '0'))
        }
    }

    private fun publishableCatalogNodes(): List<CatalogNode> =
        catalogTree.value.filterNot {
            it.type == CatalogNodeType.Device && it.parentId == it.id
        }

    private fun isOwnedBroadcastTarget(targetId: String): Boolean {
        val d = config.device
        if (targetId == d.deviceId) return true
        if (targetId == d.videoChannelId || targetId == d.frontChannelId || targetId == d.alarmChannelId) return true
        return catalogTree.value.any { it.id == targetId }
    }

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
            sendCatalogNotify(updated)
        }
    }

    suspend fun pushCatalogIncremental(events: List<CatalogChangeEvent>) {
        if (events.isEmpty()) return
        val dialogs = subscriptionRegistry.dialogsByKind("Catalog")
        for (d in dialogs) {
            val updated = subscriptionRegistry.bumpNotify(d.callId) ?: continue
            sendCatalogIncrementalNotify(updated, events)
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
            sendCatalogStatusOnlyNotify(updated, channelId, online)
        }
    }

    // 后续 helper 由 Edit 续写(handleMessage / handleDeviceControl / handleBroadcast /
    // handleRecordInfoQuery / handleSubscribe / send*Response / sendSnapshotNotify /
    // sendAlarmNotify / sendMediaStatusNotifyToSubscriber / pushAlarmResetNotify /
    // sendCatalogNotify / sendCatalogIncrementalNotify / sendCatalogStatusOnlyNotify /
    // sendPositionNotify 等 13 个 private suspend)。占位 stub:
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
        val cmd = com.uvp.sim.gb28181.ManscdpParser.cmdType(xml)
        when (cmd) {
            "Catalog" -> sendCatalogResponse(com.uvp.sim.gb28181.ManscdpParser.sn(xml) ?: "0")
            "DeviceInfo" -> sendDeviceInfoResponse(com.uvp.sim.gb28181.ManscdpParser.sn(xml) ?: "0")
            "DeviceStatus" -> sendDeviceStatusResponse(com.uvp.sim.gb28181.ManscdpParser.sn(xml) ?: "0")
            "AlarmStatus" -> sendAlarmStatusResponse(com.uvp.sim.gb28181.ManscdpParser.sn(xml) ?: "0")
            "PresetQuery" -> {
                val sn = com.uvp.sim.gb28181.ManscdpParser.sn(xml) ?: "0"
                val channelId = com.uvp.sim.gb28181.ManscdpParser.deviceId(xml) ?: ""
                sendPresetQueryResponse(sn, channelId)
            }
            "PTZPreciseStatusQuery" -> {
                val sn = com.uvp.sim.gb28181.ManscdpParser.sn(xml) ?: "0"
                val channelId = com.uvp.sim.gb28181.ManscdpParser.deviceId(xml) ?: ""
                sendPtzPreciseStatusResponse(sn, channelId)
            }
            "HomePositionQuery" -> {
                val sn = com.uvp.sim.gb28181.ManscdpParser.sn(xml) ?: "0"
                val channelId = com.uvp.sim.gb28181.ManscdpParser.deviceId(xml) ?: ""
                sendHomePositionQueryResponse(sn, channelId)
            }
            "StorageCardStatusQuery" -> {
                val sn = com.uvp.sim.gb28181.ManscdpParser.sn(xml) ?: "0"
                val channelId = com.uvp.sim.gb28181.ManscdpParser.deviceId(xml) ?: ""
                sendStorageCardStatusResponse(sn, channelId)
            }
            "CruiseTrackListQuery" -> {
                val sn = com.uvp.sim.gb28181.ManscdpParser.sn(xml) ?: "0"
                val channelId = com.uvp.sim.gb28181.ManscdpParser.deviceId(xml) ?: ""
                sendCruiseTrackListResponse(sn, channelId)
            }
            "CruiseTrackQuery" -> {
                val sn = com.uvp.sim.gb28181.ManscdpParser.sn(xml) ?: "0"
                val channelId = com.uvp.sim.gb28181.ManscdpParser.deviceId(xml) ?: ""
                val trackNum = com.uvp.sim.gb28181.ManscdpParser.tagValue(xml, "GroupID")?.toIntOrNull()
                    ?: com.uvp.sim.gb28181.ManscdpParser.tagValue(xml, "TrackNum")?.toIntOrNull()
                    ?: 1
                sendCruiseTrackResponse(sn, channelId, trackNum)
            }
            "ConfigDownload" -> {
                val sn = com.uvp.sim.gb28181.ManscdpParser.sn(xml) ?: "0"
                val types = com.uvp.sim.gb28181.ConfigDownloadResponse.parseConfigTypes(xml)
                sendConfigDownloadResponse(sn, types)
            }
            "MobilePosition" -> sendMobilePositionResponse(com.uvp.sim.gb28181.ManscdpParser.sn(xml) ?: "0")
            "DeviceControl" -> handleDeviceControl(xml, fromUri = message.fromHeader()?.let { SipHeaderHelpers.parseUri(it) })
            "RecordInfo" -> handleRecordInfoQuery(xml)
            "Broadcast" -> handleBroadcast(xml, fromUri = message.fromHeader()?.let { SipHeaderHelpers.parseUri(it) })
            else -> Unit
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
                        "Catalog" -> sendCatalogNotify(d)
                        "Alarm" -> Unit
                        else -> sendPositionNotify(d)
                    }
                }

                when (intent.kind) {
                    "Catalog" -> sendCatalogNotify(dialog)
                    "Alarm" -> Unit
                    else -> sendPositionNotify(dialog)
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
                val toTag = subscriptionRegistry.currentDialog(intent.callId)?.toTag
                    ?: SipBuilders.randomTag()
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
                val d = subscriptionRegistry.currentDialog(intent.callId)
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
                val resp = SipResponse(
                    statusCode = intent.statusCode,
                    reasonPhrase = intent.reason,
                    headers = req.headers.filter {
                        val c = SipHeader.canonicalize(it.name)
                        c == SipHeader.VIA || c == SipHeader.FROM || c == SipHeader.TO ||
                            c == SipHeader.CALL_ID || c == SipHeader.CSEQ
                    },
                )
                outbox.send(resp).getOrThrow()
            }

            is SubscribeIntent.Ignored -> Unit
        }
    }
    private suspend fun sendSnapshotNotify(xml: String) {
        if (!stateRegisteredOrInCall()) {
            simEventEmit(SimEvent.TransportError("snapshot notify: not registered"))
            return
        }
        val cseqNow = cseqInc()
        val branch = SipBuilders.randomBranch()
        val callIdNow = callId ?: SipBuilders.randomCallId(localIp).also { callIdWrite(it) }
        val fromTagNow = fromTag ?: SipBuilders.randomTag().also { fromTagWrite(it) }
        val msg = SipBuilders.buildMessage(
            config = config,
            cseq = cseqNow,
            callId = callIdNow,
            branch = branch,
            fromTag = fromTagNow,
            localIp = localIp,
            localPort = localPortProvider(),
            xmlBody = xml,
        )
        try {
            outbox.send(msg).getOrThrow()
        } catch (e: Throwable) {
            simEventEmit(SimEvent.TransportError("snapshot notify send: ${e.message}"))
        }
    }
    private suspend fun sendAlarmNotify(dialog: SubscriptionDialog, body: String, sn: String) {
        alarmNotifySn++
        val notifyCseq = dialog.cseqNotify + 1
        val remaining = dialog.remainingSeconds
        val ssValue = if (remaining > 0) "active;expires=$remaining" else "terminated"
        val notify = SipBuilders.buildNotify(
            subscriberUri = dialog.subscriberUri,
            callId = dialog.callId,
            fromTag = dialog.toTag,
            toTag = dialog.fromTag,
            event = "presence",
            subscriptionState = ssValue,
            cseq = notifyCseq,
            xmlBody = body,
            localIp = localIp,
            localPort = localPortProvider(),
            transport = config.transport.name,
            userAgent = config.userAgent,
        )
        try {
            outbox.send(notify).getOrThrow()
            simEventEmit(SimEvent.AlarmNotifySent(sn = sn, subscriber = dialog.subscriberUri))
        } catch (e: Throwable) {
            simEventEmit(SimEvent.TransportError("send Alarm NOTIFY: ${e::class.simpleName}: ${e.message}"))
        }
    }
    private suspend fun sendMediaStatusNotifyToSubscriber(dialog: SubscriptionDialog, body: String) {
        val notifyCseq = dialog.cseqNotify + 1
        val remaining = dialog.remainingSeconds
        val ssValue = if (remaining > 0) "active;expires=$remaining" else "terminated"
        val notify = SipBuilders.buildNotify(
            subscriberUri = dialog.subscriberUri,
            callId = dialog.callId,
            fromTag = dialog.toTag,
            toTag = dialog.fromTag,
            event = "presence",
            subscriptionState = ssValue,
            cseq = notifyCseq,
            xmlBody = body,
            localIp = localIp,
            localPort = localPortProvider(),
            transport = config.transport.name,
            userAgent = config.userAgent,
        )
        try {
            outbox.send(notify).getOrThrow()
        } catch (e: Throwable) {
            simEventEmit(SimEvent.TransportError("send MediaStatus NOTIFY: ${e::class.simpleName}: ${e.message}"))
        }
    }
    private suspend fun sendCatalogNotify(dialog: SubscriptionDialog) {
        catalogNotifySn++
        val xml = com.uvp.sim.gb28181.CatalogNotifyBuilder.build(
            deviceId = config.device.deviceId,
            sn = catalogNotifySn,
            tree = publishableCatalogNodes(),
        )
        val notifyCseq = dialog.cseqNotify + 1
        val remaining = dialog.remainingSeconds
        val ssValue = if (remaining > 0) "active;expires=$remaining" else "terminated"
        val notify = SipBuilders.buildNotify(
            subscriberUri = dialog.subscriberUri,
            callId = dialog.callId,
            fromTag = dialog.toTag,
            toTag = dialog.fromTag,
            event = "presence",
            subscriptionState = ssValue,
            cseq = notifyCseq,
            xmlBody = xml,
            localIp = localIp,
            localPort = localPortProvider(),
            transport = config.transport.name,
        )
        try {
            outbox.send(notify).getOrThrow()
            simEventEmit(SimEvent.NotifySent(kind = dialog.kind, sn = catalogNotifySn))
        } catch (e: Throwable) {
            simEventEmit(SimEvent.TransportError("send Catalog NOTIFY: ${e::class.simpleName}: ${e.message}"))
        }
    }
    private suspend fun sendCatalogIncrementalNotify(dialog: SubscriptionDialog, events: List<CatalogChangeEvent>) {
        catalogNotifySn++
        val xml = com.uvp.sim.gb28181.CatalogNotifyBuilder.buildIncremental(
            deviceId = config.device.deviceId,
            sn = catalogNotifySn,
            events = events,
        )
        val notifyCseq = dialog.cseqNotify + 1
        val remaining = dialog.remainingSeconds
        val ssValue = if (remaining > 0) "active;expires=$remaining" else "terminated"
        val notify = SipBuilders.buildNotify(
            subscriberUri = dialog.subscriberUri,
            callId = dialog.callId,
            fromTag = dialog.toTag,
            toTag = dialog.fromTag,
            event = "presence",
            subscriptionState = ssValue,
            cseq = notifyCseq,
            xmlBody = xml,
            localIp = localIp,
            localPort = localPortProvider(),
            transport = config.transport.name,
        )
        try {
            outbox.send(notify).getOrThrow()
            simEventEmit(SimEvent.NotifySent(kind = dialog.kind, sn = catalogNotifySn))
        } catch (e: Throwable) {
            simEventEmit(SimEvent.TransportError("send Catalog incremental NOTIFY: ${e::class.simpleName}: ${e.message}"))
        }
    }
    private suspend fun sendCatalogStatusOnlyNotify(dialog: SubscriptionDialog, channelId: String, online: Boolean) {
        catalogNotifySn++
        val xml = com.uvp.sim.gb28181.CatalogNotifyBuilder.buildStatusOnly(
            deviceId = config.device.deviceId,
            sn = catalogNotifySn,
            channelId = channelId,
            online = online,
        )
        val notifyCseq = dialog.cseqNotify + 1
        val remaining = dialog.remainingSeconds
        val ssValue = if (remaining > 0) "active;expires=$remaining" else "terminated"
        val notify = SipBuilders.buildNotify(
            subscriberUri = dialog.subscriberUri,
            callId = dialog.callId,
            fromTag = dialog.toTag,
            toTag = dialog.fromTag,
            event = "presence",
            subscriptionState = ssValue,
            cseq = notifyCseq,
            xmlBody = xml,
            localIp = localIp,
            localPort = localPortProvider(),
            transport = config.transport.name,
        )
        try {
            outbox.send(notify).getOrThrow()
            simEventEmit(SimEvent.NotifySent(kind = dialog.kind, sn = catalogNotifySn))
        } catch (e: Throwable) {
            simEventEmit(SimEvent.TransportError("send Catalog status-only NOTIFY: ${e::class.simpleName}: ${e.message}"))
        }
    }
    private suspend fun sendPositionNotify(dialog: SubscriptionDialog) {
        notifySn++
        val fix = mockGps.next()
        val xml = MobilePositionNotify.build(
            deviceId = config.device.deviceId,
            sn = notifySn,
            point = fix.point,
            speed = fix.speed,
            direction = fix.direction,
            altitude = fix.altitude,
        )
        val notifyCseq = dialog.cseqNotify + 1
        val remaining = dialog.remainingSeconds
        val ssValue = if (remaining > 0) "active;expires=$remaining" else "terminated"
        val notify = SipBuilders.buildNotify(
            subscriberUri = dialog.subscriberUri,
            callId = dialog.callId,
            fromTag = dialog.toTag,
            toTag = dialog.fromTag,
            event = "presence",
            subscriptionState = ssValue,
            cseq = notifyCseq,
            xmlBody = xml,
            localIp = localIp,
            localPort = localPortProvider(),
            transport = config.transport.name,
            userAgent = config.userAgent,
        )
        try {
            outbox.send(notify).getOrThrow()
            simEventEmit(SimEvent.NotifySent(kind = dialog.kind, sn = notifySn))
        } catch (e: Throwable) {
            simEventEmit(SimEvent.TransportError("send NOTIFY: ${e::class.simpleName}: ${e.message}"))
        }
    }

    private suspend fun sendCatalogResponse(sn: String) {
        try {
            val cseqNow = cseqInc()
            val branch = SipBuilders.randomBranch()
            val callIdNow = callId ?: SipBuilders.randomCallId(localIp).also { callIdWrite(it) }
            val fromTagNow = fromTag ?: SipBuilders.randomTag().also { fromTagWrite(it) }
            val xmlBody = com.uvp.sim.gb28181.CatalogResponse.buildFromTree(
                config = config,
                sn = sn,
                tree = publishableCatalogNodes(),
            )
            val msg = SipBuilders.buildMessage(
                config = config,
                cseq = cseqNow,
                callId = callIdNow,
                branch = branch,
                fromTag = fromTagNow,
                localIp = localIp,
                localPort = localPortProvider(),
                xmlBody = xmlBody,
            )
            outbox.send(msg).getOrThrow()
        } catch (e: Throwable) {
            simEventEmit(SimEvent.TransportError("send Catalog response: ${e.message}"))
        }
    }

    private suspend fun sendDeviceInfoResponse(sn: String) {
        try {
            val cseqNow = cseqInc()
            val branch = SipBuilders.randomBranch()
            val callIdNow = callId ?: SipBuilders.randomCallId(localIp).also { callIdWrite(it) }
            val fromTagNow = fromTag ?: SipBuilders.randomTag().also { fromTagWrite(it) }
            val xmlBody = com.uvp.sim.gb28181.DeviceInfoResponse.build(config, sn)
            val msg = SipBuilders.buildMessage(
                config = config,
                cseq = cseqNow,
                callId = callIdNow,
                branch = branch,
                fromTag = fromTagNow,
                localIp = localIp,
                localPort = localPortProvider(),
                xmlBody = xmlBody,
            )
            outbox.send(msg).getOrThrow()
            SystemLogger.emit(LogLevel.Info, LogTag.Network, "平台查询 DeviceInfo → 已应答 sn=$sn")
        } catch (e: Throwable) {
            simEventEmit(SimEvent.TransportError("send DeviceInfo response: ${e.message}"))
        }
    }

    private suspend fun sendDeviceStatusResponse(sn: String) {
        try {
            val cseqNow = cseqInc()
            val branch = SipBuilders.randomBranch()
            val callIdNow = callId ?: SipBuilders.randomCallId(localIp).also { callIdWrite(it) }
            val fromTagNow = fromTag ?: SipBuilders.randomTag().also { fromTagWrite(it) }
            val ctrl = mutableDeviceControlState.value
            val snapshot = com.uvp.sim.gb28181.DeviceStatusSnapshot(
                online = stateRegisteredOrInCall(),
                deviceTime = currentLocalIso(),
                recording = ctrl.isRecording,
                alarming = ctrl.isAlarming,
                guarded = ctrl.isGuarded,
            )
            val xmlBody = com.uvp.sim.gb28181.DeviceStatusResponse.build(config, sn, snapshot)
            val msg = SipBuilders.buildMessage(
                config = config,
                cseq = cseqNow,
                callId = callIdNow,
                branch = branch,
                fromTag = fromTagNow,
                localIp = localIp,
                localPort = localPortProvider(),
                xmlBody = xmlBody,
            )
            outbox.send(msg).getOrThrow()
            SystemLogger.emit(
                LogLevel.Info, LogTag.Network,
                "平台查询 DeviceStatus → 已应答 sn=$sn online=${snapshot.online} record=${snapshot.recording} alarm=${snapshot.alarming}"
            )
        } catch (e: Throwable) {
            simEventEmit(SimEvent.TransportError("send DeviceStatus response: ${e.message}"))
        }
    }

    private suspend fun sendAlarmStatusResponse(sn: String) {
        try {
            val cseqNow = cseqInc()
            val branch = SipBuilders.randomBranch()
            val callIdNow = callId ?: SipBuilders.randomCallId(localIp).also { callIdWrite(it) }
            val fromTagNow = fromTag ?: SipBuilders.randomTag().also { fromTagWrite(it) }
            val snapshot = com.uvp.sim.gb28181.AlarmStatusSnapshot(
                alarming = mutableDeviceControlState.value.isAlarming,
                alarmChannelId = config.device.alarmChannelId,
            )
            val xmlBody = com.uvp.sim.gb28181.AlarmStatusResponse.build(config, sn, snapshot)
            val msg = SipBuilders.buildMessage(
                config = config,
                cseq = cseqNow,
                callId = callIdNow,
                branch = branch,
                fromTag = fromTagNow,
                localIp = localIp,
                localPort = localPortProvider(),
                xmlBody = xmlBody,
            )
            outbox.send(msg).getOrThrow()
            SystemLogger.emit(LogLevel.Info, LogTag.Network, "平台查询 AlarmStatus → 已应答 sn=$sn alarm=${snapshot.alarming}")
        } catch (e: Throwable) {
            simEventEmit(SimEvent.TransportError("send AlarmStatus response: ${e.message}"))
        }
    }

    private suspend fun sendPresetQueryResponse(sn: String, channelId: String) {
        try {
            val cseqNow = cseqInc()
            val branch = SipBuilders.randomBranch()
            val callIdNow = callId ?: SipBuilders.randomCallId(localIp).also { callIdWrite(it) }
            val fromTagNow = fromTag ?: SipBuilders.randomTag().also { fromTagWrite(it) }
            val xmlBody = com.uvp.sim.gb28181.PresetQueryResponse.build(
                config = config,
                sn = sn,
                channelId = channelId,
                presets = mutableDeviceControlState.value.presets,
            )
            val msg = SipBuilders.buildMessage(
                config = config,
                cseq = cseqNow,
                callId = callIdNow,
                branch = branch,
                fromTag = fromTagNow,
                localIp = localIp,
                localPort = localPortProvider(),
                xmlBody = xmlBody,
            )
            outbox.send(msg).getOrThrow()
            SystemLogger.emit(LogLevel.Info, LogTag.Network, "平台查询 PresetQuery → 已应答(空清单)sn=$sn")
        } catch (e: Throwable) {
            simEventEmit(SimEvent.TransportError("send PresetQuery response: ${e.message}"))
        }
    }

    private suspend fun sendPtzPreciseStatusResponse(sn: String, channelId: String) {
        try {
            val cseqNow = cseqInc()
            val branch = SipBuilders.randomBranch()
            val callIdNow = callId ?: SipBuilders.randomCallId(localIp).also { callIdWrite(it) }
            val fromTagNow = fromTag ?: SipBuilders.randomTag().also { fromTagWrite(it) }
            val s = mutableDeviceControlState.value
            val pose = s.lastPreciseCtrl ?: PtzPose(s.panAngle, s.tiltAngle, s.zoomLevel)
            val xmlBody = com.uvp.sim.gb28181.PtzPreciseStatusResponse.build(
                config = config,
                sn = sn,
                channelId = channelId,
                pose = pose,
            )
            val msg = SipBuilders.buildMessage(
                config = config,
                cseq = cseqNow,
                callId = callIdNow,
                branch = branch,
                fromTag = fromTagNow,
                localIp = localIp,
                localPort = localPortProvider(),
                xmlBody = xmlBody,
            )
            outbox.send(msg).getOrThrow()
            SystemLogger.emit(
                LogLevel.Info, LogTag.Network,
                "平台查询 PTZPreciseStatusQuery → 已应答(${pose.pan},${pose.tilt},${pose.zoom}x)sn=$sn"
            )
        } catch (e: Throwable) {
            simEventEmit(SimEvent.TransportError("send PTZPreciseStatusQuery response: ${e.message}"))
        }
    }

    private suspend fun sendConfigDownloadResponse(sn: String, configTypes: List<String>) {
        try {
            val cseqNow = cseqInc()
            val branch = SipBuilders.randomBranch()
            val callIdNow = callId ?: SipBuilders.randomCallId(localIp).also { callIdWrite(it) }
            val fromTagNow = fromTag ?: SipBuilders.randomTag().also { fromTagWrite(it) }
            val xmlBody = com.uvp.sim.gb28181.ConfigDownloadResponse.build(config, sn, configTypes)
            val msg = SipBuilders.buildMessage(
                config = config,
                cseq = cseqNow,
                callId = callIdNow,
                branch = branch,
                fromTag = fromTagNow,
                localIp = localIp,
                localPort = localPortProvider(),
                xmlBody = xmlBody,
            )
            outbox.send(msg).getOrThrow()
            SystemLogger.emit(
                LogLevel.Info, LogTag.Network,
                "平台查询 ConfigDownload → 已应答 sn=$sn types=${configTypes.joinToString("/")}"
            )
        } catch (e: Throwable) {
            simEventEmit(SimEvent.TransportError("send ConfigDownload response: ${e.message}"))
        }
    }

    private suspend fun sendMobilePositionResponse(sn: String) {
        try {
            val cseqNow = cseqInc()
            val branch = SipBuilders.randomBranch()
            val callIdNow = callId ?: SipBuilders.randomCallId(localIp).also { callIdWrite(it) }
            val fromTagNow = fromTag ?: SipBuilders.randomTag().also { fromTagWrite(it) }
            val fix = mockGps.next()
            val xmlBody = com.uvp.sim.gb28181.MobilePositionResponse.build(
                deviceId = config.device.deviceId,
                sn = sn,
                point = fix.point,
                speed = fix.speed,
                direction = fix.direction,
                altitude = fix.altitude,
                timestamp = currentLocalIso(),
            )
            val msg = SipBuilders.buildMessage(
                config = config,
                cseq = cseqNow,
                callId = callIdNow,
                branch = branch,
                fromTag = fromTagNow,
                localIp = localIp,
                localPort = localPortProvider(),
                xmlBody = xmlBody,
            )
            outbox.send(msg).getOrThrow()
            SystemLogger.emit(
                LogLevel.Info, LogTag.Network,
                "平台查询 MobilePosition → 已应答 sn=$sn lng=${fix.point.longitude} lat=${fix.point.latitude}"
            )
        } catch (e: Throwable) {
            simEventEmit(SimEvent.TransportError("send MobilePosition response: ${e.message}"))
        }
    }

    private suspend fun sendMansResponseMessage(xmlBody: String, label: String) {
        val cseqNow = cseq  // already incremented by caller (sendHomePosition... etc)
        val branch = SipBuilders.randomBranch()
        val callIdNow = callId ?: SipBuilders.randomCallId(localIp).also { callIdWrite(it) }
        val fromTagNow = fromTag ?: SipBuilders.randomTag().also { fromTagWrite(it) }
        val msg = SipBuilders.buildMessage(
            config = config,
            cseq = cseqNow,
            callId = callIdNow,
            branch = branch,
            fromTag = fromTagNow,
            localIp = localIp,
            localPort = localPortProvider(),
            xmlBody = xmlBody,
        )
        outbox.send(msg).getOrThrow()
        SystemLogger.emit(LogLevel.Info, LogTag.Network, "$label → 已应答")
    }

    private suspend fun sendHomePositionQueryResponse(sn: String, channelId: String) {
        try {
            cseqInc()
            val s = mutableDeviceControlState.value
            val responseDeviceId = channelId.ifBlank { config.device.deviceId }
            val presetIndex = if (s.homePosition != null) 1 else 0
            val xmlBody = "<?xml version=\"1.0\" encoding=\"GB2312\"?>\r\n" +
                "<Response>\r\n" +
                "<CmdType>HomePositionQuery</CmdType>\r\n" +
                "<SN>$sn</SN>\r\n" +
                "<DeviceID>$responseDeviceId</DeviceID>\r\n" +
                "<Enabled>${if (s.homePositionEnabled) 1 else 0}</Enabled>\r\n" +
                "<ResetTime>30</ResetTime>\r\n" +
                "<PresetIndex>$presetIndex</PresetIndex>\r\n" +
                "</Response>\r\n"
            sendMansResponseMessage(xmlBody, "HomePositionQuery sn=$sn")
        } catch (e: Throwable) {
            simEventEmit(SimEvent.TransportError("send HomePositionQuery response: ${e.message}"))
        }
    }

    private suspend fun sendStorageCardStatusResponse(sn: String, channelId: String) {
        try {
            cseqInc()
            val responseDeviceId = channelId.ifBlank { config.device.deviceId }
            val xmlBody = "<?xml version=\"1.0\" encoding=\"GB2312\"?>\r\n" +
                "<Response>\r\n" +
                "<CmdType>StorageCardStatusQuery</CmdType>\r\n" +
                "<SN>$sn</SN>\r\n" +
                "<DeviceID>$responseDeviceId</DeviceID>\r\n" +
                "<SumNum>1</SumNum>\r\n" +
                "<StorageList Num=\"1\">\r\n" +
                "<Item><CardNum>0</CardNum><Status>Normal</Status><TotalCapacity>32768</TotalCapacity><RemainingSpace>24576</RemainingSpace></Item>\r\n" +
                "</StorageList>\r\n" +
                "</Response>\r\n"
            sendMansResponseMessage(xmlBody, "StorageCardStatusQuery sn=$sn")
        } catch (e: Throwable) {
            simEventEmit(SimEvent.TransportError("send StorageCardStatus response: ${e.message}"))
        }
    }

    private suspend fun sendCruiseTrackListResponse(sn: String, channelId: String) {
        try {
            cseqInc()
            val s = mutableDeviceControlState.value
            val responseDeviceId = channelId.ifBlank { config.device.deviceId }
            val sumNum = s.cruiseTracks.size
            val items = s.cruiseTracks.toSortedMap().keys.joinToString("\r\n") { trackNum ->
                "<Item><GroupID>$trackNum</GroupID><Name>巡航 $trackNum</Name></Item>"
            }
            val itemsBlock = if (sumNum == 0) "<TrackList Num=\"0\"/>"
                else "<TrackList Num=\"$sumNum\">\r\n$items\r\n</TrackList>"
            val xmlBody = "<?xml version=\"1.0\" encoding=\"GB2312\"?>\r\n" +
                "<Response>\r\n" +
                "<CmdType>CruiseTrackListQuery</CmdType>\r\n" +
                "<SN>$sn</SN>\r\n" +
                "<DeviceID>$responseDeviceId</DeviceID>\r\n" +
                "<SumNum>$sumNum</SumNum>\r\n" +
                "$itemsBlock\r\n" +
                "</Response>\r\n"
            sendMansResponseMessage(xmlBody, "CruiseTrackList sn=$sn N=$sumNum")
        } catch (e: Throwable) {
            simEventEmit(SimEvent.TransportError("send CruiseTrackList response: ${e.message}"))
        }
    }

    private suspend fun sendCruiseTrackResponse(sn: String, channelId: String, trackNum: Int) {
        try {
            cseqInc()
            val s = mutableDeviceControlState.value
            val responseDeviceId = channelId.ifBlank { config.device.deviceId }
            val track = s.cruiseTracks[trackNum] ?: emptyList()
            val sumNum = track.size
            val items = track.joinToString("\r\n") { presetNum ->
                "<Item><PresetID>$presetNum</PresetID><Speed>5</Speed><DwellTime>3</DwellTime></Item>"
            }
            val itemsBlock = if (sumNum == 0) "<PresetList Num=\"0\"/>"
                else "<PresetList Num=\"$sumNum\">\r\n$items\r\n</PresetList>"
            val xmlBody = "<?xml version=\"1.0\" encoding=\"GB2312\"?>\r\n" +
                "<Response>\r\n" +
                "<CmdType>CruiseTrackQuery</CmdType>\r\n" +
                "<SN>$sn</SN>\r\n" +
                "<DeviceID>$responseDeviceId</DeviceID>\r\n" +
                "<GroupID>$trackNum</GroupID>\r\n" +
                "<SumNum>$sumNum</SumNum>\r\n" +
                "$itemsBlock\r\n" +
                "</Response>\r\n"
            sendMansResponseMessage(xmlBody, "CruiseTrack #$trackNum sn=$sn")
        } catch (e: Throwable) {
            simEventEmit(SimEvent.TransportError("send CruiseTrack response: ${e.message}"))
        }
    }

    private suspend fun handleRecordInfoQuery(xml: String) {
        val tz = "Asia/Shanghai"
        val query = com.uvp.sim.gb28181.RecordInfoQuery.parse(xml, tz) ?: run {
            SystemLogger.emit(LogLevel.Warning, LogTag.Media, "RecordInfo 查询解析失败")
            return
        }
        if (query.indistinctQuery == 1 || query.filePath != null ||
            query.address != null || query.recorderId != null
        ) {
            SystemLogger.emit(
                LogLevel.Info, LogTag.Media,
                "RecordInfo 高级过滤(已解析,sim 单通道 mock 不参与命中): " +
                    "indistinct=${query.indistinctQuery} path=${query.filePath} " +
                    "addr=${query.address} recId=${query.recorderId}"
            )
        }
        val files = recordingService.files.value
        val hits = files.filter {
            query.startMs <= it.endTimeMs && query.endMs >= it.startTimeMs &&
                (query.type == null || it.type == query.type)
        }
        val packets = com.uvp.sim.gb28181.RecordInfoNotify.buildAll(
            sn = query.sn,
            deviceId = config.device.deviceId,
            deviceName = config.device.name,
            items = hits,
            timeZoneId = tz,
        )
        SystemLogger.emit(
            LogLevel.Info, LogTag.Media,
            "平台查询录像 → 命中 ${hits.size} 条 / 分 ${packets.size} 包"
        )
        for (xmlBody in packets) {
            try {
                val cseqNow = cseqInc()
                val branch = SipBuilders.randomBranch()
                val callIdNow = callId ?: SipBuilders.randomCallId(localIp).also { callIdWrite(it) }
                val fromTagNow = fromTag ?: SipBuilders.randomTag().also { fromTagWrite(it) }
                val msg = SipBuilders.buildMessage(
                    config = config,
                    cseq = cseqNow,
                    callId = callIdNow,
                    branch = branch,
                    fromTag = fromTagNow,
                    localIp = localIp,
                    localPort = localPortProvider(),
                    xmlBody = xmlBody,
                )
                outbox.send(msg).getOrThrow()
            } catch (e: Throwable) {
                simEventEmit(SimEvent.TransportError("send RecordInfo: ${e.message}"))
            }
        }
    }

    private suspend fun handleDeviceControl(xml: String, fromUri: String? = null) {
        val ack = deviceControlDispatcher.dispatch(xml, fromUri = fromUri)
        val lastCmd = mutableDeviceControlState.value.lastCommand
        if (lastCmd != null) {
            simEventEmit(
                SimEvent.DeviceControlReceived(
                    commandType = lastCmd.type,
                    detail = lastCmd.rawHex,
                )
            )
        }
        if (ack.alarmReset) {
            simEventEmit(SimEvent.AlarmReset(SimEvent.ResetSource.Remote(ack.by ?: "platform")))
            SystemLogger.emit(
                LogLevel.Info, LogTag.Network,
                "平台 AlarmCmd 复位报警 by=${ack.by ?: "platform"}",
            )
            pushAlarmResetNotify(ack.by)
        }
        val recordCmd = com.uvp.sim.gb28181.ManscdpParser.recordCmd(xml) ?: return
        val sn = com.uvp.sim.gb28181.ManscdpParser.sn(xml) ?: "0"
        val deviceId = com.uvp.sim.gb28181.ManscdpParser.deviceId(xml) ?: config.device.deviceId
        var result = "OK"
        when (recordCmd.equals("Record", ignoreCase = true) to recordCmd.equals("StopRecord", ignoreCase = true)) {
            true to false -> {
                SystemLogger.emit(LogLevel.Info, LogTag.Media, "平台下发 Record → 启动录像 source=PlatformCmd")
                runCatching {
                    recordingService.start(
                        com.uvp.sim.recording.RecordSource.PlatformCmd,
                        config.device.videoChannelId,
                    )
                }.onFailure {
                    SystemLogger.emit(LogLevel.Error, LogTag.Media, "RecordCmd 启动录像异常: ${it.message}")
                    result = "ERROR"
                }
            }
            false to true -> {
                SystemLogger.emit(LogLevel.Info, LogTag.Media, "平台下发 StopRecord → 停止录像")
                runCatching { recordingService.stop() }
                    .onFailure {
                        SystemLogger.emit(LogLevel.Error, LogTag.Media, "RecordCmd 停止录像异常: ${it.message}")
                        result = "ERROR"
                    }
            }
            else -> {
                SystemLogger.emit(LogLevel.Warning, LogTag.Media, "平台 RecordCmd 未识别 → '$recordCmd'")
                result = "ERROR"
            }
        }
        sendDeviceControlResponse(sn = sn, deviceId = deviceId, result = result)
    }

    private suspend fun sendDeviceControlResponse(sn: String, deviceId: String, result: String) {
        val xmlBody = "<?xml version=\"1.0\" encoding=\"GB2312\"?>\r\n" +
            "<Response>\r\n" +
            "<CmdType>DeviceControl</CmdType>\r\n" +
            "<SN>$sn</SN>\r\n" +
            "<DeviceID>$deviceId</DeviceID>\r\n" +
            "<Result>$result</Result>\r\n" +
            "</Response>\r\n"
        runCatching {
            val cseqNow = cseqInc()
            val branch = SipBuilders.randomBranch()
            val callIdNow = callId ?: SipBuilders.randomCallId(localIp).also { callIdWrite(it) }
            val fromTagNow = fromTag ?: SipBuilders.randomTag().also { fromTagWrite(it) }
            val msg = SipBuilders.buildMessage(
                config = config,
                cseq = cseqNow,
                callId = callIdNow,
                branch = branch,
                fromTag = fromTagNow,
                localIp = localIp,
                localPort = localPortProvider(),
                xmlBody = xmlBody,
            )
            outbox.send(msg).getOrThrow()
        }.onFailure {
            simEventEmit(SimEvent.TransportError("send DeviceControl Response: ${it.message}"))
        }
    }

    private suspend fun pushAlarmResetNotify(by: String?) {
        val dialogs = subscriptionRegistry.dialogsByKind("Alarm")
        if (dialogs.isEmpty()) return
        val sn = cseqInc().toString()
        val resetPayload = AlarmPayload(
            deviceId = config.device.alarmChannelId,
            description = "报警已复位 (AlarmCmd by ${by ?: "platform"})",
        )
        val body = com.uvp.sim.gb28181.AlarmNotify.buildAlarm(config, sn, resetPayload)
        for (d in dialogs) {
            val updated = subscriptionRegistry.bumpNotify(d.callId) ?: continue
            sendAlarmNotify(updated, body, sn)
        }
    }

    private suspend fun handleBroadcast(xml: String, fromUri: String? = null) {
        val query = com.uvp.sim.gb28181.BroadcastQuery.parse(xml)
        val sn = query.sn ?: "0"
        val myId = config.device.deviceId

        // 并发拒绝(spec Q1):已持有一路 broadcast → ERROR busy,不发 INVITE
        if (broadcastBusy()) {
            sendBroadcastResponseMessage(
                com.uvp.sim.gb28181.BroadcastResponse.build(
                    deviceId = myId, sn = sn,
                    result = com.uvp.sim.gb28181.BroadcastResponse.Result.ERROR,
                    reason = "busy",
                )
            )
            SystemLogger.emit(LogLevel.Warning, LogTag.Network, "已有语音广播进行中 → 拒绝第二路(busy)")
            return
        }

        val targetId = query.targetId
        if (targetId.isNullOrBlank() || !isOwnedBroadcastTarget(targetId)) {
            sendBroadcastResponseMessage(
                com.uvp.sim.gb28181.BroadcastResponse.build(
                    deviceId = myId, sn = sn,
                    result = com.uvp.sim.gb28181.BroadcastResponse.Result.ERROR,
                    reason = "target mismatch",
                )
            )
            SystemLogger.emit(
                LogLevel.Warning, LogTag.Network,
                "语音广播 TargetID 不属于本设备: 收到 '$targetId'(deviceId=$myId)→ ERROR",
            )
            return
        }
        sendBroadcastResponseMessage(
            com.uvp.sim.gb28181.BroadcastResponse.build(
                deviceId = myId, sn = sn,
                result = com.uvp.sim.gb28181.BroadcastResponse.Result.OK,
            )
        )
        val sourceId = query.sourceId ?: ""
        simEventEmit(SimEvent.BroadcastReceived(sourceId = sourceId, targetId = targetId))
        SystemLogger.emit(
            LogLevel.Info, LogTag.Network,
            "收到语音广播请求 source=$sourceId target=$targetId → 已回 OK,主动 INVITE 平台",
        )
        val platformUri = "sip:$sourceId@${config.server.domain}"
        broadcastInvoker.fireBroadcastInvite(sourceId, platformUri, targetId)
    }

    private suspend fun sendBroadcastResponseMessage(xmlBody: String) {
        runCatching {
            val cseqNow = cseqInc()
            val branch = SipBuilders.randomBranch()
            val callIdNow = callId ?: SipBuilders.randomCallId(localIp).also { callIdWrite(it) }
            val fromTagNow = fromTag ?: SipBuilders.randomTag().also { fromTagWrite(it) }
            val msg = SipBuilders.buildMessage(
                config = config,
                cseq = cseqNow,
                callId = callIdNow,
                branch = branch,
                fromTag = fromTagNow,
                localIp = localIp,
                localPort = localPortProvider(),
                xmlBody = xmlBody,
            )
            outbox.send(msg).getOrThrow()
        }.onFailure {
            simEventEmit(SimEvent.TransportError("send Broadcast Response: ${it.message}"))
        }
    }

    /**
     * GB-2022 §9.13 设备升级假进度 — 5s 内每秒推一次 DeviceUpgradeResult NOTIFY (0/30/60/100).
     * 完成时推 result=1 + percent=100,同步写 mutableDeviceControlState.upgradeProgress.
     * (followup E 从 Engine 迁入)
     */
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
            val cseqNow = cseqInc()
            val branch = SipBuilders.randomBranch()
            val callIdNow = SipBuilders.randomCallId(localIp)
            val fromTagNow = SipBuilders.randomTag()
            val msg = com.uvp.sim.sip.DeviceUpgradeResultNotify.build(
                config = config,
                cseq = cseqNow,
                callId = callIdNow,
                branch = branch,
                fromTag = fromTagNow,
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
}
