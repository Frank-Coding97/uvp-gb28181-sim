package com.uvp.sim.domain.coord

import com.uvp.sim.config.GbVersion
import com.uvp.sim.config.SimConfig
import com.uvp.sim.domain.ClockOffset
import com.uvp.sim.domain.TimeSyncSource
import com.uvp.sim.gb28181.SignalingCharset
import com.uvp.sim.network.Heartbeat
import com.uvp.sim.network.NetworkState
import com.uvp.sim.network.KtorNtpClient
import com.uvp.sim.network.NtpClient
import com.uvp.sim.network.SipTransport
import com.uvp.sim.observability.LogLevel
import com.uvp.sim.observability.LogTag
import com.uvp.sim.observability.SystemLogger
import com.uvp.sim.sip.DigestAuth
import com.uvp.sim.sip.GbVersionNegotiation
import com.uvp.sim.sip.NatSituation
import com.uvp.sim.sip.RportObservation
import com.uvp.sim.sip.SipBuilders
import com.uvp.sim.sip.SipDateParser
import com.uvp.sim.sip.SipHeader
import com.uvp.sim.sip.SipMessage
import com.uvp.sim.sip.SipMethod
import com.uvp.sim.sip.SipRequest
import com.uvp.sim.sip.SipResponse
import com.uvp.sim.sip.SipOutbox
import com.uvp.sim.sip.ViaObservedEndpoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
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
import kotlinx.coroutines.withTimeoutOrNull

/**
 * [RegistrationCoordinator] 真实现(PR2 T2.2 GREEN)。
 *
 * 迁移自 SimulatorEngine 注册栈相关代码(行号见 git 历史 c384abb 前):
 *   register / cancelRegister / unregister(public)
 *   handleRegisterResponse / handleOptions / armRegisterTimeout / cancelRegisterTimeout /
 *   scheduleRetryOrFail / doRegisterInternal / triggerReregisterIfActive /
 *   startHeartbeat / triggerAutoReregister / applySipDateSync / scheduleExpiresRenewal(private)
 *
 * 跨域决策(plan 第 2.1.1 节):
 *   - 自带 Mutex(不共享 Engine 大锁)
 *   - 自管 _state / _events / _clockOffset(Engine façade 聚合)
 *   - heartbeat 完全归本域
 *   - 心跳超时触发的自动重注册:本类只发 [RegistrationEvent.AutoReregisterTriggered],
 *     由 Engine façade 听 event 后关闭其他域的活跃流(不反向调其他 Coordinator)
 *   - **cseq / callId / fromTag SN 池跨域共享(2026-06-23 T2.3a 修订)**:
 *     这三个字段是 Engine 全局 SN 池 + dialog identity(78/85/47 处跨业务方法引用),
 *     不能下沉到本 Coord 独占。构造期通过 6 个 lambda(provider/setter)注入外部 SN 池
 *     访问通道。默认值给独立 counter,让单元测试隔离假设成立。详见研究文档
 *     `wiki/projects/uvp-gb28181-sim/research/2026-06-23-cseq-sn-pool-coupling.md`。
 */
internal class RegistrationCoordinatorImpl(
    private val config: SimConfig,
    private val transport: SipTransport,
    private val scope: CoroutineScope,
    private val outbox: SipOutbox,
    private val localIpProvider: () -> String = { "0.0.0.0" },
    private val localPortProvider: () -> Int = { 5060 },
    cseqProvider: (() -> Int)? = null,
    cseqIncrementer: (() -> Int)? = null,
    callIdProvider: (() -> String?)? = null,
    callIdSetter: ((String) -> Unit)? = null,
    fromTagProvider: (() -> String?)? = null,
    fromTagSetter: ((String) -> Unit)? = null,
    private val ntpClient: NtpClient = KtorNtpClient(),
) : RegistrationCoordinator {

    private val _state = MutableStateFlow(RegistrationState.Disconnected)
    override val state: StateFlow<RegistrationState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<RegistrationEvent>(extraBufferCapacity = 32)
    override val events: SharedFlow<RegistrationEvent> = _events.asSharedFlow()

    private val _clockOffset = MutableStateFlow(ClockOffset.Empty)
    override val clockOffset: StateFlow<ClockOffset> = _clockOffset.asStateFlow()

    private val _platformVersion = MutableStateFlow<GbVersion?>(null)
    override val platformVersion: StateFlow<GbVersion?> = _platformVersion.asStateFlow()

    /** 上一次已公告的平台版本原始值 + 是否公告过(null 原始值本身也是有效结论,需区分)。 */
    private var platformVersionAnnounced: Boolean = false
    private var announcedPlatformVersionRaw: String? = null

    private val _rportObservation = MutableStateFlow<RportObservation?>(null)
    override val rportObservation: StateFlow<RportObservation?> = _rportObservation.asStateFlow()

    /** 同 platformVersion:结论未变时不重复打日志(401 + 200 会带着同样的回填来两次)。 */
    private var rportObservationAnnounced: Boolean = false
    private var announcedRportSignature: String? = null

    private val mutex = Mutex()
    private val localIp: String get() = localIpProvider()

    // ---- SN 池 provider 适配(2026-06-23 T2.3a 加,详见 class kdoc) ----
    // 默认走独立 counter,T2.3b 时 Engine 注入会让本 Coord 的 cseq/callId/fromTag
    // 读写直达 Engine 上的全局 SN 池。
    private var internalCseq: Int = 0
    private var internalCallId: String? = null
    private var internalFromTag: String? = null

    private val cseqRead: () -> Int =
        cseqProvider ?: { internalCseq }
    private val cseqIncAndRead: () -> Int = cseqIncrementer ?: {
        internalCseq += 1
        internalCseq
    }
    private val callIdRead: () -> String? =
        callIdProvider ?: { internalCallId }
    private val callIdWrite: (String) -> Unit =
        callIdSetter ?: { internalCallId = it }
    private val fromTagRead: () -> String? =
        fromTagProvider ?: { internalFromTag }
    private val fromTagWrite: (String) -> Unit =
        fromTagSetter ?: { internalFromTag = it }

    /** true 表示 cseq 完全跑独立 counter(单元测试模式),允许 register() 时重置 = 1。 */
    private val ownsCseqPool: Boolean = cseqIncrementer == null

    /** 给本类内部用的 cseq 读访问器(read-only view of SN pool)。 */
    private val cseq: Int get() = cseqRead()
    /** 给本类内部用的 cseq 自增并返回新值(write to SN pool)。 */
    private fun cseqInc(): Int = cseqIncAndRead()
    /** 把 cseq 重置到指定值(register 起始要重置 = 1)。 */
    private fun cseqResetTo(value: Int) {
        // 当 cseqIncrementer 是注入的(指 Engine 的 SN 池),不应该手动重置 —
        // SN 池跨整个 Engine 生命周期单调递增。仅在默认 internal counter 时允许。
        if (ownsCseqPool) internalCseq = value
        // 注入模式下:不重置,等 cseqIncrementer 自然推进
    }
    private val callId: String? get() = callIdRead()
    private val fromTag: String? get() = fromTagRead()
    private fun callIdSet(v: String) = callIdWrite(v)
    private fun fromTagSet(v: String) = fromTagWrite(v)

    // ---- 注册事务字段(从 Engine 迁) ----
    private var pendingRegister: SipRequest? = null
    private var registerJob: Job? = null

    // 心跳
    private var heartbeat: Heartbeat? = null
    private var keepaliveSn: Int = 0
    private var consecutiveKeepaliveTimeouts: Int = 0
    private var lastKeepaliveAcked: Boolean = true

    // 续约
    private var renewalJob: Job? = null
    private var isRenewal: Boolean = false

    private var ntpRefreshJob: Job? = null
    private var sipDateFallback: ClockOffset? = null

    // 注销是一个需要等待平台响应的 REGISTER 事务,不能跟上一次注册共用 pending。
    private var unregisterCompletion: CompletableDeferred<Boolean>? = null

    // 重试退避
    private var registerRetryCount: Int = 0
    private var retryJob: Job? = null

    // ----------------------------------------------------------------------
    // public API
    // ----------------------------------------------------------------------

    override suspend fun register() {
        mutex.withLock {
            if (_state.value == RegistrationState.Registering ||
                _state.value == RegistrationState.Registered) return
            retryJob?.cancel()
            retryJob = null
            registerRetryCount = 0

            cseqResetTo(1)
            callIdSet(SipBuilders.randomCallId(localIp))
            fromTagSet(SipBuilders.randomTag())
            val branch = SipBuilders.randomBranch()
            val req = SipBuilders.buildRegister(
                config, cseq, callId!!, branch, fromTag!!, localIp, localPortProvider(),
            )
            pendingRegister = req
            _state.value = RegistrationState.Registering
            SystemLogger.emit(
                LogLevel.Info, LogTag.Lifecycle,
                "开始注册到 ${config.server.ip}:${config.server.port}",
            )
            try {
                outbox.send(req).getOrThrow()
                armRegisterTimeout()
            } catch (e: Throwable) {
                _state.value = RegistrationState.Failed
                _events.emit(RegistrationEvent.TransportFailed("transport: ${e.message}"))
                SystemLogger.emit(
                    LogLevel.Error, LogTag.Lifecycle,
                    "注册请求发送失败: ${e::class.simpleName}: ${e.message}",
                )
            }
        }
    }

    override suspend fun cancelRegister() {
        mutex.withLock {
            if (_state.value != RegistrationState.Registering) return
            registerJob?.cancel()
            registerJob = null
            retryJob?.cancel()
            retryJob = null
            registerRetryCount = 0
            _state.value = RegistrationState.Disconnected
            _events.emit(RegistrationEvent.Unauthorized(0, "用户取消"))
        }
    }

    override suspend fun unregister() {
        val completion = mutex.withLock {
            // ⛔ Failed 也要早退(2026-10-03):它是"重试配额用尽"的终态,平台侧从没有过一条
            // 活着的注册会话 —— 发 Expires=0 只会落到死链上,让调用方空等
            // UNREGISTER_TIMEOUT_MS(3s)才拿到 false。Disconnected 同理。
            // 与 `com.uvp.sim.sip.hasActiveRegistration()` 是同一口径。
            if (_state.value == RegistrationState.Disconnected ||
                _state.value == RegistrationState.Failed
            ) return@withLock null
            cancelRegisterTimeoutLocked()
            retryJob?.cancel()
            retryJob = null
            registerRetryCount = 0
            renewalJob?.cancel()
            renewalJob = null
            ntpRefreshJob?.cancel()
            ntpRefreshJob = null
            isRenewal = false
            heartbeat?.stop()
            heartbeat = null

            cseqInc()
            val branch = SipBuilders.randomBranch()
            val callIdNow = callId ?: SipBuilders.randomCallId(localIp)
            val fromTagNow = fromTag ?: SipBuilders.randomTag()
            val req = SipBuilders.buildUnregister(
                config, cseq, callIdNow, branch, fromTagNow, localIp, localPortProvider(),
            )
            val waitForResponse = CompletableDeferred<Boolean>()
            unregisterCompletion?.cancel()
            unregisterCompletion = waitForResponse
            // 401/407 必须基于这条 Expires=0 请求做 Digest 重发,不能沿用普通 REGISTER。
            pendingRegister = req
            try {
                outbox.send(req).getOrThrow()
            } catch (e: Throwable) {
                finishUnregisterLocked(false)
                waitForResponse.complete(false)
                SystemLogger.emit(
                    LogLevel.Warning, LogTag.Lifecycle,
                    "注销请求发送失败: ${e::class.simpleName}: ${e.message}",
                )
            }
            waitForResponse
        } ?: return

        // 不能在 mutex 内等待,否则 onIncoming 无法拿锁处理 401/200。
        val acknowledged = withTimeoutOrNull(UNREGISTER_TIMEOUT_MS) { completion.await() } ?: false
        if (!acknowledged) {
            mutex.withLock {
                if (unregisterCompletion === completion) finishUnregisterLocked(false)
            }
        }
    }

    // ----------------------------------------------------------------------
    // Coordinator 接口
    // ----------------------------------------------------------------------

    /**
     * 见 [RegistrationCoordinator.onConnectionLost] 的契约说明。这里只讲实现取舍:
     *
     * - **不动 NTP 刷新任务**:它走自己的 UDP socket([ntpClient]),跟 SIP 长连接是两条命,
     *   一起掐掉会让"平台重启"这种事顺手把校时也停了。[unregister] 会停它是因为那一次真的是
     *   会话终结(用户注销 / 换平台),这里不是。
     * - **未决注销事务要显式 complete(false)**:[unregister] 里有 `withTimeoutOrNull(3s)
     *   { completion.await() }`,不唤醒它就要干等到超时;而且它超时后会回来补调
     *   `finishUnregisterLocked`,那时 `unregisterCompletion` 已被本方法清成 null,判等不成立 ——
     *   所以先 complete 再清引用是安全的,不会双重收尾。
     */
    override suspend fun onConnectionLost() {
        mutex.withLock {
            cancelRegisterTimeoutLocked()
            retryJob?.cancel(); retryJob = null
            registerRetryCount = 0
            renewalJob?.cancel(); renewalJob = null
            isRenewal = false
            heartbeat?.stop(); heartbeat = null
            // 挂在这条连接上的未决事务都不可能再有结果了。
            pendingRegister = null
            unregisterCompletion?.let { it.complete(false) }
            unregisterCompletion = null
            // 版本协商结论与地址转换观察值随本次注册会话一起作废,下轮注册要重新"在注册过程中得知"。
            _platformVersion.value = null
            _rportObservation.value = null
            _state.value = RegistrationState.Disconnected
        }
        SystemLogger.emit(
            LogLevel.Warning,
            LogTag.Lifecycle,
            "传输连接被动断开,注册会话已作废(等重连后重新注册)",
        )
    }

    override suspend fun onIncoming(envelope: com.uvp.sim.network.SipEnvelope): RoutingResult {
        val msg = envelope.message
        return when (msg) {
            is SipResponse -> {
                val msgCallId = msg.firstHeader(SipHeader.CALL_ID)
                if (msgCallId == null || msgCallId != callId || pendingRegister == null) return RoutingResult.Skip
                val cseqRaw = msg.cseqRaw()
                val cseqMethod = cseqRaw?.split(" ")?.getOrNull(1)?.let { SipMethod.fromString(it) }
                when (cseqMethod) {
                    SipMethod.REGISTER -> {
                        handleRegisterResponse(msg)
                        RoutingResult.Handled
                    }
                    SipMethod.MESSAGE -> {
                        if (msg.statusCode in 200..299) {
                            consecutiveKeepaliveTimeouts = 0
                            lastKeepaliveAcked = true
                        }
                        RoutingResult.Handled
                    }
                    else -> RoutingResult.Skip
                }
            }
            is SipRequest -> {
                if (msg.method == SipMethod.OPTIONS) {
                    handleOptions(msg)
                    RoutingResult.Handled
                } else {
                    RoutingResult.Skip
                }
            }
        }
    }

    override suspend fun onNetworkChange(state: NetworkState) {
        when (state) {
            is NetworkState.Bound -> {
                _events.emit(RegistrationEvent.NetworkSwitchedReregister(state.localIp))
                SystemLogger.emit(
                    LogLevel.Info, LogTag.Network,
                    "网络已切到 ${state.preference.name} 接口 ${state.interfaceName} IP=${state.localIp},触发重注册",
                )
                triggerReregisterIfActive()
            }
            NetworkState.Auto -> {
                SystemLogger.emit(
                    LogLevel.Info, LogTag.Network,
                    "网络偏好 → 自动,触发重注册以刷新 Contact 头",
                )
                triggerReregisterIfActive()
            }
            is NetworkState.Unavailable -> {
                SystemLogger.emit(
                    LogLevel.Warning, LogTag.Network,
                    "网络不可用: ${state.reason}",
                )
            }
            is NetworkState.Switching -> Unit
        }
    }

    override suspend fun shutdown() {
        mutex.withLock {
            registerJob?.cancel(); registerJob = null
            retryJob?.cancel(); retryJob = null
            renewalJob?.cancel(); renewalJob = null
            ntpRefreshJob?.cancel(); ntpRefreshJob = null
            heartbeat?.stop(); heartbeat = null
        }
    }

    // ----------------------------------------------------------------------
    // private — 从 Engine 迁
    // ----------------------------------------------------------------------

    private fun armRegisterTimeout() {
        registerJob?.cancel()
        registerJob = scope.launch {
            delay(REGISTER_TIMEOUT_MS)
            mutex.withLock {
                if (_state.value != RegistrationState.Registering) return@withLock
                scheduleRetryOrFail("平台 ${REGISTER_TIMEOUT_MS / 1000}s 未响应")
            }
        }
    }

    private fun cancelRegisterTimeoutLocked() {
        registerJob?.cancel()
        registerJob = null
    }

    private suspend fun scheduleRetryOrFail(reason: String, permanent: Boolean = false) {
        if (permanent || registerRetryCount >= MAX_REGISTER_RETRIES) {
            _state.value = RegistrationState.Failed
            _events.emit(RegistrationEvent.Unauthorized(0, reason))
            SystemLogger.emit(LogLevel.Warning, LogTag.Lifecycle, "注册失败(不重试): $reason")
            registerRetryCount = 0
            return
        }
        registerRetryCount++
        val delayMs = INITIAL_RETRY_DELAY_MS * (1L shl (registerRetryCount - 1))
        SystemLogger.emit(
            LogLevel.Info, LogTag.Lifecycle,
            "注册失败: $reason → 第 $registerRetryCount 次重试,${delayMs}ms 后",
        )
        _state.value = RegistrationState.RetryBackoff
        retryJob?.cancel()
        retryJob = scope.launch {
            delay(delayMs)
            doRegisterInternal()
        }
    }

    private suspend fun doRegisterInternal() {
        mutex.withLock {
            if (_state.value != RegistrationState.RetryBackoff &&
                _state.value != RegistrationState.Failed &&
                _state.value != RegistrationState.Disconnected
            ) return
            cseqResetTo(1)
            callIdSet(SipBuilders.randomCallId(localIp))
            fromTagSet(SipBuilders.randomTag())
            val branch = SipBuilders.randomBranch()
            val req = SipBuilders.buildRegister(
                config, cseq, callId!!, branch, fromTag!!, localIp, localPortProvider(),
            )
            pendingRegister = req
            _state.value = RegistrationState.Registering
            try {
                outbox.send(req).getOrThrow()
                armRegisterTimeout()
            } catch (e: Throwable) {
                scheduleRetryOrFail("transport.send: ${e.message}")
            }
        }
    }

    private suspend fun triggerReregisterIfActive() {
        val current = _state.value
        if (current != RegistrationState.Registered &&
            current != RegistrationState.Registering
        ) {
            return
        }
        runCatching { unregister() }
            .onFailure {
                SystemLogger.emit(
                    LogLevel.Warning, LogTag.Network,
                    "重注册 unregister 抛错(可忽略,旧网卡可能已断): ${it::class.simpleName}: ${it.message}",
                )
            }
        runCatching { register() }
            .onFailure {
                SystemLogger.emit(
                    LogLevel.Error, LogTag.Network,
                    "重注册 register 抛错: ${it::class.simpleName}: ${it.message}",
                )
            }
    }

    /**
     * 附录 I「协议版本标识」:解析平台在注册响应里声明的 X-GB-Ver。
     *
     * 只在结论变化时打日志 —— 一次注册至少两条响应(401 挑战 + 200 OK),同值重复打只会刷屏。
     *
     * 平台未声明 / 不可识别时**不降级**:对面没说清自己能识别什么,贸然按 2016 出站等于白白
     * 丢掉本机能力;这种情况留一条警告,提示联调双方去对齐版本头。
     */
    private fun applyPlatformVersion(resp: SipResponse) {
        val raw = resp.firstHeader(SipHeader.X_GB_VER)
        val parsed = GbVersionNegotiation.parse(raw)

        // ⛔ 值更新必须**无条件**执行,去重只用来抑制重复日志。
        // 注销会把 _platformVersion 清空(协商结论只对一次注册会话成立),而重新注册时平台
        // 报的版本头通常与上一次**完全相同** —— 若把"结论没变就 return"放在最前面,流会永远
        // 停在 null:设置页一直显示"平台版本:注册后协商",而实际早就协商完了。
        _platformVersion.value = parsed

        if (platformVersionAnnounced && announcedPlatformVersionRaw == raw) return
        platformVersionAnnounced = true
        announcedPlatformVersionRaw = raw

        val effective = GbVersionNegotiation.effective(config.gbVersion, parsed)
        if (parsed == null) {
            val shown = if (raw.isNullOrBlank()) "缺失" else raw
            SystemLogger.emit(
                LogLevel.Warning, LogTag.Lifecycle,
                "平台注册响应未携带可识别的 X-GB-Ver($shown),后续仍按本机 ${config.gbVersion.xGbVer} 交互",
            )
            return
        }
        val downgraded = effective != config.gbVersion
        SystemLogger.emit(
            LogLevel.Info, LogTag.Lifecycle,
            "版本协商:本机 ${config.gbVersion.xGbVer} × 平台 ${parsed.xGbVer} → 有效 ${effective.xGbVer}" +
                if (downgraded) "(平台较低,出站报文按 2016 形态)" else "",
        )
    }

    /**
     * 从响应 Via 的 `received` / `rport` 回填得出「平台看到的我方端点」,并判定本机是否在地址转换之后。
     *
     * ⛔ 与 [applyPlatformVersion] 不同,**解析不出来是正常的**:`rport`/`received` 在
     * GB/T 28181-2022 里零出现,平台完全可以不回填(甚至只回填其中一个)。所以无回填时只记
     * Debug —— 否则每台正常设备的每次注册都刷一条"警告",真正的 NAT 判定反而被淹掉。
     */
    private fun applyRportObservation(resp: SipResponse) {
        val observed = ViaObservedEndpoint.parse(resp.firstHeader(SipHeader.VIA))
        val localPort = localPortProvider()
        val observation = ViaObservedEndpoint.assess(localIp, localPort, observed)

        // 同 applyPlatformVersion:值更新无条件,去重只压日志(401 挑战与 200 会带同样的回填各来一次)。
        _rportObservation.value = observation

        val signature = "${observation.observedIp}:${observation.observedPort}/${observation.situation.name}"
        if (rportObservationAnnounced && announcedRportSignature == signature) return
        rportObservationAnnounced = true
        announcedRportSignature = signature

        when (observation.situation) {
            NatSituation.DIRECT -> SystemLogger.emit(
                LogLevel.Info,
                LogTag.Network,
                "平台视角的本机端点 ${observation.observedIp}:${observation.observedPort} 与本机一致(未经过地址转换)",
            )

            NatSituation.NAT -> {
                val seen = "${observation.observedIp}:${observation.observedPort}"
                val advice = if (config.transport.name.equals("TCP", ignoreCase = true)) {
                    "当前以 TCP 注册,符合 §9.1.1 f) 对 NAT 内侧设备的要求"
                } else {
                    "当前以 ${config.transport.name} 注册:NAT 场景下平台的下行报文会被转换设备丢弃" +
                        "(表现为注册成功但点播/云台/查询全部超时),建议改用 TCP"
                }
                SystemLogger.emit(
                    LogLevel.Warning,
                    LogTag.Network,
                    "本机处于地址转换之后:平台看到的端点是 $seen,本机声明 $localIp:$localPort。$advice",
                )
            }

            NatSituation.UNKNOWN -> SystemLogger.emit(
                LogLevel.Debug,
                LogTag.Network,
                "平台未回填 Via 的 received/rport,无法判断本机是否经地址转换(这不影响协议合规)",
            )
        }
    }

    private suspend fun handleRegisterResponse(resp: SipResponse) {
        // 附录 I:注册响应"无论成功或失败"都要带 X-GB-Ver —— 所以先解析再看状态码。
        // 401 挑战里平台就已经报了自己的版本,没必要等到 200 才知道对面是几代。
        applyPlatformVersion(resp)
        // 同理:平台对 401 的响应里就已经回填了它看到的来源端点,不必等 200。
        applyRportObservation(resp)
        when (resp.statusCode) {
            in 200..299 -> {
                cancelRegisterTimeoutLocked()
                registerRetryCount = 0
                unregisterCompletion?.let { completion ->
                    finishUnregisterLocked(true)
                    completion.complete(true)
                    return
                }
                applySipDateSync(resp)
                if (!isRenewal) {
                    _state.value = RegistrationState.Registered
                    _events.emit(RegistrationEvent.Registered)
                    SystemLogger.emit(
                        LogLevel.Info, LogTag.Lifecycle,
                        "已注册,expires=${config.expiresSeconds}s",
                    )
                    startHeartbeat()
                } else {
                    SystemLogger.emit(
                        LogLevel.Info, LogTag.Lifecycle,
                        "续约成功,expires=${config.expiresSeconds}s",
                    )
                    _events.emit(RegistrationEvent.Renewed)
                    isRenewal = false
                }
                scheduleExpiresRenewal()
            }

            401, 407 -> {
                val challenge = resp.firstHeader(SipHeader.WWW_AUTHENTICATE)
                    ?: resp.firstHeader("Proxy-Authenticate")
                val pending = pendingRegister
                if (challenge == null || pending == null) {
                    cancelRegisterTimeoutLocked()
                    unregisterCompletion?.let { completion ->
                        finishUnregisterLocked(false)
                        completion.complete(false)
                        return
                    }
                    _state.value = RegistrationState.Failed
                    _events.emit(RegistrationEvent.Unauthorized(resp.statusCode, "missing challenge"))
                    SystemLogger.emit(
                        LogLevel.Warning, LogTag.Lifecycle,
                        "注册失败: 401 缺少 WWW-Authenticate",
                    )
                    return
                }
                // 401 storm 防御:已经应答过 challenge 的不再重复重发
                val alreadyAuthed = pending.firstHeader(SipHeader.AUTHORIZATION) != null
                if (alreadyAuthed) {
                    SystemLogger.emit(
                        LogLevel.Debug, LogTag.Lifecycle,
                        "忽略重复 401(已应答挑战,等待 200 或超时)",
                    )
                    return
                }
                val parsed = DigestAuth.parseChallenge(challenge)
                _events.emit(RegistrationEvent.AuthChallenged(parsed.realm))
                val authHeader = DigestAuth.buildResponse(
                    challenge = parsed,
                    username = config.device.username,
                    password = config.device.password,
                    method = "REGISTER",
                    uri = pending.requestUri,
                )
                cseqInc()
                val newBranch = SipBuilders.randomBranch()
                val authedReq = SipBuilders.addAuthorization(pending, authHeader, cseq, newBranch)
                pendingRegister = authedReq
                try {
                    outbox.send(authedReq).getOrThrow()
                    // 注销事务由 unregister() 自己等待响应;普通 REGISTER 仍需重置 8s 超时。
                    if (unregisterCompletion == null) armRegisterTimeout()
                } catch (e: Throwable) {
                    unregisterCompletion?.let { completion ->
                        finishUnregisterLocked(false)
                        completion.complete(false)
                        SystemLogger.emit(
                            LogLevel.Warning, LogTag.Lifecycle,
                            "注销鉴权请求发送失败: ${e::class.simpleName}: ${e.message}",
                        )
                        return
                    }
                    throw e
                }
            }

            in 400..699 -> {
                cancelRegisterTimeoutLocked()
                unregisterCompletion?.let { completion ->
                    finishUnregisterLocked(false)
                    completion.complete(false)
                    return
                }
                val reason = "${resp.statusCode} ${resp.reasonPhrase}"
                val permanent = resp.statusCode in 400..499 && resp.statusCode != 401 && resp.statusCode != 407
                scheduleRetryOrFail(reason, permanent)
            }
        }
    }

    /** 完成或放弃注销事务,并清掉旧 REGISTER 上下文,避免迟到响应把状态改回 Registered。 */
    private fun finishUnregisterLocked(success: Boolean) {
        if (success) {
            SystemLogger.emit(LogLevel.Info, LogTag.Lifecycle, "平台确认注销")
        }
        // 协商结论与地址转换观察值只对一次注册会话成立:注销后清空,下轮注册重新"在注册过程中得知"。
        _platformVersion.value = null
        _rportObservation.value = null
        pendingRegister = null
        unregisterCompletion = null
        isRenewal = false
        _state.value = RegistrationState.Disconnected
    }

    private suspend fun handleOptions(req: SipRequest) {
        runCatching {
            val resp = SipBuilders.buildOptionsResponse(
                request = req,
                allowedMethods = ALLOWED_OPTIONS_METHODS,
                userAgent = config.userAgent,
            )
            outbox.send(resp).getOrThrow()
        }.onFailure {
            SystemLogger.emit(
                LogLevel.Warning, LogTag.Lifecycle,
                "send OPTIONS 200 失败: ${it.message}",
            )
        }
    }

    private fun startHeartbeat() {
        heartbeat?.stop()
        consecutiveKeepaliveTimeouts = 0
        lastKeepaliveAcked = true
        heartbeat = Heartbeat(
            intervalMillis = config.keepaliveIntervalSeconds * 1000L,
            scope = scope,
        ) {
            if (!lastKeepaliveAcked) {
                consecutiveKeepaliveTimeouts++
                if (consecutiveKeepaliveTimeouts >= config.maxKeepaliveTimeouts) {
                    SystemLogger.emit(
                        LogLevel.Warning, LogTag.Lifecycle,
                        "心跳连续 ${consecutiveKeepaliveTimeouts} 次未响应,触发重注册",
                    )
                    triggerAutoReregister("heartbeat timeout ×${consecutiveKeepaliveTimeouts}")
                    return@Heartbeat
                }
            }
            lastKeepaliveAcked = false
            keepaliveSn += 1
            cseqInc()
            val branch = SipBuilders.randomBranch()
            val msg = SipBuilders.buildKeepalive(
                config = config,
                sn = keepaliveSn,
                cseq = cseq,
                callId = callId ?: SipBuilders.randomCallId(localIp),
                branch = branch,
                fromTag = fromTag ?: SipBuilders.randomTag(),
                localIp = localIp,
                localPort = localPortProvider(),
                // 心跳报文体是纯 ASCII,取值只影响 XML 声明那个标签;但仍按**有效版本**出站,
                // 免得"对面是 2016 却声明 GB18030"这种口径分裂(§6.10)。
                charset = SignalingCharset.of(
                    GbVersionNegotiation.effective(config.gbVersion, _platformVersion.value),
                ),
            )
            try {
                outbox.send(msg).getOrThrow()
            } catch (e: Throwable) {
                SystemLogger.emit(
                    LogLevel.Warning, LogTag.Lifecycle,
                    "send Keepalive 失败: ${e::class.simpleName}: ${e.message}",
                )
            }
        }
        heartbeat?.start()
    }

    private fun triggerAutoReregister(reason: String) {
        scope.launch {
            // 通知 Engine façade:它会顺序停掉 invite/playback/broadcast 的活跃流
            _events.emit(RegistrationEvent.AutoReregisterTriggered(reason))
            heartbeat?.stop()
            heartbeat = null
            renewalJob?.cancel()
            renewalJob = null
            mutex.withLock {
                _state.value = RegistrationState.Disconnected
            }
            register()
        }
    }

    private fun applySipDateSync(resp: SipResponse) {
        val rawDate = resp.firstHeader(SipHeader.DATE)
        if (!rawDate.isNullOrBlank()) {
            val platformInstant = SipDateParser.parse(rawDate)
            if (platformInstant == null) {
                SystemLogger.emit(
                    LogLevel.Warning, LogTag.Lifecycle,
                    "Date 头解析失败,fallback 本地时钟",
                    detail = rawDate,
                )
            } else {
                val offset = ClockOffset.synced(platformInstant, rawDate, TimeSyncSource.SIP_DATE)
                sipDateFallback = offset
                _clockOffset.value = offset
                SystemLogger.emit(
                    LogLevel.Info, LogTag.Lifecycle,
                    "已校时:SIP Date ${rawDate.trim()}",
                )
            }
        }
        if (config.timeSync.ntpEnabled) startNtpRefresh() else {
            ntpRefreshJob?.cancel()
            ntpRefreshJob = null
        }
    }

    private fun startNtpRefresh() {
        ntpRefreshJob?.cancel()
        val cfg = config.timeSync
        ntpRefreshJob = scope.launch {
            while (true) {
                try {
                    val sample = ntpClient.query(cfg.ntpServer, cfg.ntpPort, NTP_TIMEOUT_MS)
                    _clockOffset.value = ClockOffset.synced(
                        sample.instant,
                        "NTP ${cfg.ntpServer}:${cfg.ntpPort}",
                        TimeSyncSource.NTP,
                    )
                    SystemLogger.emit(
                        LogLevel.Info, LogTag.Lifecycle,
                        "已校时:NTP ${cfg.ntpServer}:${cfg.ntpPort},RTT=${sample.roundTripMillis}ms",
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    _clockOffset.value = sipDateFallback ?: ClockOffset.Empty
                    SystemLogger.emit(
                        LogLevel.Warning, LogTag.Lifecycle,
                        "NTP 校时失败,已回退 SIP Date: ${error.message ?: error::class.simpleName}",
                    )
                }
                delay(cfg.refreshIntervalSeconds.coerceAtLeast(1) * 1_000L)
            }
        }
    }

    private fun scheduleExpiresRenewal() {
        renewalJob?.cancel()
        val renewalDelayMs = (config.expiresSeconds * 800L)
        renewalJob = scope.launch {
            delay(renewalDelayMs)
            mutex.withLock {
                if (_state.value != RegistrationState.Registered) return@withLock
                isRenewal = true
                cseqInc()
                val branch = SipBuilders.randomBranch()
                val req = SipBuilders.buildRegister(
                    config, cseq, callId ?: SipBuilders.randomCallId(localIp),
                    branch, fromTag ?: SipBuilders.randomTag(), localIp, localPortProvider(),
                )
                pendingRegister = req
                SystemLogger.emit(
                    LogLevel.Info, LogTag.Lifecycle,
                    "Expires 续约: 发送 REGISTER(剩余 ${config.expiresSeconds * 200 / 1000}s)",
                )
                try {
                    outbox.send(req).getOrThrow()
                    armRegisterTimeout()
                } catch (e: Throwable) {
                    // R3 #4 (full preset HIGH/correctness):续约 send 失败原先只清 isRenewal,
                    // 状态仍停在 Registered → UI 以为没事,但平台已掉线,过 expiresSeconds 后才以更迷的方式暴露。
                    // 改走跟初次注册同款的 scheduleRetryOrFail:状态降到 RetryBackoff、按退避重试或最终 Failed。
                    // R3 verify-followup:scheduleRetryOrFail 进 backoff 只切状态、不 emit 事件
                    // (Unauthorized 只在 terminal 失败时 emit),UI 还是听不到掉线信号。
                    // 这里在进重试前先 emit 一条 Unauthorized,把"续约失败"立刻广播出去。
                    isRenewal = false
                    pendingRegister = null
                    val reason = "renewal send: ${e.message ?: e::class.simpleName}"
                    SystemLogger.emit(
                        LogLevel.Warning, LogTag.Lifecycle,
                        "Expires 续约 send 失败: ${e.message ?: e::class.simpleName} → 立刻通知 + 进入重试路径",
                    )
                    _events.emit(RegistrationEvent.TransportFailed(reason))
                    scheduleRetryOrFail(reason)
                }
            }
        }
    }

    companion object {
        const val REGISTER_TIMEOUT_MS: Long = 8_000L
        const val UNREGISTER_TIMEOUT_MS: Long = 3_000L
        const val MAX_REGISTER_RETRIES: Int = 3
        const val INITIAL_RETRY_DELAY_MS: Long = 2_000L
        const val NTP_TIMEOUT_MS: Long = 1_500L

        /** OPTIONS 200 OK 的 Allow 头方法集(与 SimulatorEngine.ALLOWED_OPTIONS_METHODS 同步)。 */
        val ALLOWED_OPTIONS_METHODS: List<SipMethod> = listOf(
            SipMethod.INVITE, SipMethod.ACK, SipMethod.BYE, SipMethod.MESSAGE,
            SipMethod.SUBSCRIBE, SipMethod.NOTIFY, SipMethod.CANCEL,
            SipMethod.INFO, SipMethod.OPTIONS,
        )
    }
}
