package com.uvp.sim.domain.coord

import com.uvp.sim.config.SimConfig
import com.uvp.sim.domain.ClockOffset
import com.uvp.sim.network.Heartbeat
import com.uvp.sim.network.NetworkState
import com.uvp.sim.network.SipTransport
import com.uvp.sim.observability.LogLevel
import com.uvp.sim.observability.LogTag
import com.uvp.sim.observability.SystemLogger
import com.uvp.sim.sip.DigestAuth
import com.uvp.sim.sip.SipBuilders
import com.uvp.sim.sip.SipDateParser
import com.uvp.sim.sip.SipHeader
import com.uvp.sim.sip.SipMessage
import com.uvp.sim.sip.SipMethod
import com.uvp.sim.sip.SipRequest
import com.uvp.sim.sip.SipResponse
import com.uvp.sim.sip.SipOutbox
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
) : RegistrationCoordinator {

    private val _state = MutableStateFlow(RegistrationState.Disconnected)
    override val state: StateFlow<RegistrationState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<RegistrationEvent>(extraBufferCapacity = 32)
    override val events: SharedFlow<RegistrationEvent> = _events.asSharedFlow()

    private val _clockOffset = MutableStateFlow(ClockOffset.Empty)
    override val clockOffset: StateFlow<ClockOffset> = _clockOffset.asStateFlow()

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
                _events.emit(RegistrationEvent.Unauthorized(0, "transport: ${e.message}"))
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
        mutex.withLock {
            if (_state.value == RegistrationState.Disconnected) return
            cancelRegisterTimeoutLocked()
            retryJob?.cancel()
            retryJob = null
            registerRetryCount = 0
            renewalJob?.cancel()
            renewalJob = null
            heartbeat?.stop()
            heartbeat = null

            cseqInc()
            val branch = SipBuilders.randomBranch()
            val callIdNow = callId ?: SipBuilders.randomCallId(localIp)
            val fromTagNow = fromTag ?: SipBuilders.randomTag()
            val req = SipBuilders.buildUnregister(
                config, cseq, callIdNow, branch, fromTagNow, localIp, localPortProvider(),
            )
            try {
                outbox.send(req).getOrThrow()
            } catch (_: Throwable) {
                // 网络不可达时发不出去是正常的(旧网卡已断 / NetworkUnavailable)
            }
            _state.value = RegistrationState.Disconnected
        }
    }

    // ----------------------------------------------------------------------
    // Coordinator 接口
    // ----------------------------------------------------------------------

    override suspend fun onIncoming(envelope: com.uvp.sim.network.SipEnvelope): RoutingResult {
        val msg = envelope.message
        return when (msg) {
            is SipResponse -> {
                val msgCallId = msg.firstHeader(SipHeader.CALL_ID)
                if (msgCallId == null || msgCallId != callId) return RoutingResult.Skip
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

    private suspend fun handleRegisterResponse(resp: SipResponse) {
        when (resp.statusCode) {
            in 200..299 -> {
                cancelRegisterTimeoutLocked()
                registerRetryCount = 0
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
                outbox.send(authedReq).getOrThrow()
                armRegisterTimeout()
            }

            in 400..699 -> {
                cancelRegisterTimeoutLocked()
                val reason = "${resp.statusCode} ${resp.reasonPhrase}"
                val permanent = resp.statusCode in 400..499 && resp.statusCode != 401 && resp.statusCode != 407
                scheduleRetryOrFail(reason, permanent)
            }
        }
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
        if (rawDate.isNullOrBlank()) return
        val platformInstant = SipDateParser.parse(rawDate)
        if (platformInstant == null) {
            SystemLogger.emit(
                LogLevel.Warning, LogTag.Lifecycle,
                "Date 头解析失败,fallback 本地时钟",
                detail = rawDate,
            )
            return
        }
        val offset = ClockOffset.synced(platformInstant, rawDate)
        _clockOffset.value = offset
        SystemLogger.emit(
            LogLevel.Info, LogTag.Lifecycle,
            "已校时:平台 ${rawDate.trim()}",
        )
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
                    isRenewal = false
                }
            }
        }
    }

    companion object {
        const val REGISTER_TIMEOUT_MS: Long = 8_000L
        const val MAX_REGISTER_RETRIES: Int = 3
        const val INITIAL_RETRY_DELAY_MS: Long = 2_000L

        /** OPTIONS 200 OK 的 Allow 头方法集(与 SimulatorEngine.ALLOWED_OPTIONS_METHODS 同步)。 */
        val ALLOWED_OPTIONS_METHODS: List<SipMethod> = listOf(
            SipMethod.INVITE, SipMethod.ACK, SipMethod.BYE, SipMethod.MESSAGE,
            SipMethod.SUBSCRIBE, SipMethod.NOTIFY, SipMethod.CANCEL,
            SipMethod.INFO, SipMethod.OPTIONS,
        )
    }
}
