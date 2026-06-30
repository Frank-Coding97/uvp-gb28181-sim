package com.uvp.sim.domain.coord

import com.uvp.sim.domain.SimEvent
import com.uvp.sim.domain.transportErrorOf
import com.uvp.sim.network.SipEnvelope
import com.uvp.sim.observability.LogLevel
import com.uvp.sim.observability.LogTag
import com.uvp.sim.observability.SystemLogger
import com.uvp.sim.sip.DialogIdentityVerifier
import com.uvp.sim.sip.SipBuilders
import com.uvp.sim.sip.SipRequest
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * cross-review R3 拆分 — 直播 INVITE 的 mid-dialog handler:ACK / CANCEL / BYE / dialog
 * identity 校验 / ACK watchdog。
 *
 * 设计原则(spec §4 / plan §2.3):
 * - 自管 `ackTimeoutJob` + `_awaitingAckCallId`(handler 内部 state,主类**只读**)
 * - 自带 [ackMutex] 保护 ack 状态,跟主类的 `mutex`(守 acceptInFlight/activeStream)解耦
 * - 完成 ACK/CANCEL/BYE 后返回 [DialogResult],**不**直接动 SipState / activeStream
 * - `handleCancel` 用 [awaitingAckCallId] 判 pending-phase,RFC 3261 § 9.2 语义:
 *   * pending(200 已发 / awaitingAckCallId == cid)→ TerminateDialog
 *   * late(ACK 已收 / awaitingAckCallId == null)→ KeepDialog
 *
 * **R3 verify-残留 race 治本(RG-1)**:`handleCancel` 不再硬要求 `active != null`,
 * 改用 `awaitingAckCallId == cid` 单独判 pending —— 这覆盖了 "200 已发但 activeStream
 * 还没赋值"的 pre-publication 窗口,让主类的 cleanupActiveStream 可以容忍 null active。
 */
internal class InviteDialogHandler(
    private val shared: InviteSharedState,
    private val ackTimeoutMs: Long = ACK_TIMEOUT_MS,
) {
    private val ackMutex = Mutex()
    private var ackTimeoutJob: Job? = null
    private var _awaitingAckCallId: String? = null

    /**
     * 当前等待 ACK 的 callId(null = 不在等)。
     *
     * 主类用本字段判 CANCEL pending-phase(R3 #3 + verify-残留 race 治本):
     * - `awaitingAckCallId == cid` → CANCEL 仍在 pending,可销毁 dialog
     * - `awaitingAckCallId != cid` → ACK 已到 dialog 完全建立,后续 CANCEL 只发 200 不销毁
     */
    val awaitingAckCallId: String? get() = _awaitingAckCallId

    /**
     * 在 200 OK 发出后调用,启动 32s ACK watchdog。
     *
     * @param onTimeout watchdog 超时回调,由主类决定怎么清理(典型:cleanupActiveStream)
     */
    suspend fun installAckWatchdog(
        cid: String,
        onTimeout: suspend (cid: String) -> Unit,
    ) {
        ackMutex.withLock {
            _awaitingAckCallId = cid
            ackTimeoutJob?.cancel()
            ackTimeoutJob = shared.scope.launch {
                delay(ackTimeoutMs)
                val shouldFire = ackMutex.withLock {
                    if (_awaitingAckCallId == cid) {
                        _awaitingAckCallId = null
                        true
                    } else false
                }
                if (shouldFire) {
                    shared.simEventEmit(SimEvent.InviteAckTimeout(cid))
                    SystemLogger.emit(
                        LogLevel.Warning, LogTag.Lifecycle,
                        "INVITE 200 OK 未收到 ACK (${ackTimeoutMs / 1000}s) — 平台可能已断开,释放媒体管线"
                    )
                    onTimeout(cid)
                }
            }
        }
    }

    /**
     * 收到 ACK 时调用,如果 cid 匹配当前 watchdog 则取消并返回 true。
     *
     * 返回值给主类用 — 主类决定后续动作(典型:仅日志 + 事件,不动 state)。
     */
    suspend fun cancelAckWatchdogIfMatches(cid: String): Boolean = ackMutex.withLock {
        if (cid == _awaitingAckCallId) {
            ackTimeoutJob?.cancel()
            ackTimeoutJob = null
            _awaitingAckCallId = null
            true
        } else false
    }

    /**
     * 手动取消 watchdog(典型:cleanupActiveStream / shutdown 路径)。
     */
    suspend fun cancelAckWatchdog() {
        ackMutex.withLock {
            ackTimeoutJob?.cancel()
            ackTimeoutJob = null
            _awaitingAckCallId = null
        }
    }

    /**
     * P1-3 mid-dialog dialog identity 校验。
     *
     * 跟 [com.uvp.sim.domain.coord.PlaybackCoordinatorImpl] 同款语义:
     * - [DialogIdentityVerifier.VerifyResult.Match] → 返回 true
     * - 其它 mismatch → CANCEL/BYE 返 481 + Warning 日志,ACK 仅日志(无响应)
     *
     * @param respondOn481 ACK 没有响应(RFC 3261 § 17.1.1.3),传 false 跳过 481 发送。
     */
    suspend fun verifyMidDialogOrReject(
        envelope: SipEnvelope,
        req: SipRequest,
        active: InviteCoordinatorImpl.ActiveStream,
        op: String,
        respondOn481: Boolean = true,
    ): Boolean {
        val dialogId = DialogIdentityVerifier.DialogId(
            callId = active.callId,
            localTag = active.localTag,
            remoteTag = active.remoteTag,
            remoteSourceIp = active.remoteSourceIp,
        )
        val result = DialogIdentityVerifier.verify(envelope, dialogId)
        if (result == DialogIdentityVerifier.VerifyResult.Match) return true
        SystemLogger.emit(
            LogLevel.Warning, LogTag.Lifecycle,
            "拒绝 INVITE $op:dialog identity 不匹配($result) → ${if (respondOn481) "481" else "丢弃"} " +
                "[expected callId=${active.callId} remoteTag=${active.remoteTag} sourceIp=${active.remoteSourceIp}, " +
                "got callId=${req.callId()} sourceIp=${envelope.sourceIp}]",
        )
        if (respondOn481) {
            runCatching {
                val resp = SipBuilders.buildSimpleResponse(
                    req, statusCode = 481, reasonPhrase = "Call/Transaction Does Not Exist",
                    toTag = SipBuilders.randomTag(),
                    userAgent = shared.config.userAgent,
                )
                shared.outbox.send(resp).getOrThrow()
            }
        }
        return false
    }

    /**
     * 处理 ACK 请求。主路径:校验 dialog identity → 命中则 cancelAckWatchdogIfMatches。
     *
     * 总是返回 [DialogResult.KeepDialog] —— ACK 不终止 dialog。
     */
    suspend fun handleAck(envelope: SipEnvelope, ack: SipRequest): DialogResult {
        val cid = ack.callId() ?: return DialogResult.KeepDialog
        val active = shared.currentActiveStream()
        // P1-3:有活跃流时 ACK 必须通过 dialog identity 校验;ACK 无响应,失败丢弃。
        if (active != null && active.callId == cid) {
            if (!verifyMidDialogOrReject(envelope, ack, active, op = "ACK", respondOn481 = false)) {
                return DialogResult.KeepDialog
            }
        }
        cancelAckWatchdogIfMatches(cid)
        return DialogResult.KeepDialog
    }

    /**
     * 处理 CANCEL 请求。RFC 3261 § 9.2 语义:
     *
     * 1. 校验 dialog identity(若有 active 命中 Call-ID)
     * 2. 发 CANCEL 的 200(无论 pending 与否)
     * 3. 判 pending phase:`awaitingAckCallId == cid` → [DialogResult.TerminateDialog]
     * 4. late phase(ACK 已收)→ [DialogResult.KeepDialog](日志记 late CANCEL,留给 BYE)
     *
     * **R3 verify-残留 race 治本(RG-1)**:pending 判定**只看 awaitingAckCallId**,不要求
     * `active != null` —— 这覆盖 "200 已发 / activeStream 还没赋值"的 pre-publication 窗口。
     * 主类的 cleanupActiveStream 需容忍 active 为 null。
     */
    suspend fun handleCancel(envelope: SipEnvelope, cancel: SipRequest): DialogResult {
        val cid = cancel.callId() ?: ""
        val active = shared.currentActiveStream()
        // P1-3:Call-ID 对得上 activeStream 才进 verifier;Call-ID 不对沿用旧行为(发 200 不动状态)
        if (active != null && active.callId == cid) {
            if (!verifyMidDialogOrReject(envelope, cancel, active, op = "CANCEL")) {
                return DialogResult.KeepDialog
            }
        }
        try {
            val ok = SipBuilders.buildSimple200(cancel, userAgent = shared.config.userAgent)
            shared.outbox.send(ok).getOrThrow()
        } catch (e: Throwable) {
            shared.simEventEmit(transportErrorOf("send CANCEL 200", e))
        }

        // RG-1 治本:pending 判定**只看 awaitingAckCallId**,不依赖 active != null。
        // pre-publication 路径(200 已发出 / activeStream 还没赋值)也算 pending。
        val inPendingPhase = awaitingAckCallId == cid
        return if (inPendingPhase) {
            DialogResult.TerminateDialog("remote CANCEL")
        } else {
            if (active != null && active.callId == cid) {
                SystemLogger.emit(
                    LogLevel.Info, LogTag.Lifecycle,
                    "忽略 late CANCEL: callId=$cid dialog 已建立(ACK 已收),等 BYE 销毁"
                )
            }
            DialogResult.KeepDialog
        }
    }

    /**
     * 处理 BYE 请求。语义:
     *
     * 1. 校验 dialog identity(若有 active),不匹配 → 481 + KeepDialog
     * 2. 发 BYE 的 200
     * 3. 总是 [DialogResult.TerminateDialog]
     *
     * 主类拿 TerminateDialog 后调 cleanupActiveStream + 切 SipState。
     */
    suspend fun handleBye(envelope: SipEnvelope, bye: SipRequest): DialogResult {
        val cid = bye.callId() ?: ""
        val active = shared.currentActiveStream()
        // P1-3:进入本 handler 时主类已判过 active.callId == cid,这里把全维度校验补齐。
        if (active != null) {
            if (!verifyMidDialogOrReject(envelope, bye, active, op = "BYE")) {
                return DialogResult.KeepDialog
            }
        }
        // 先发 200 OK
        try {
            val ok = SipBuilders.buildSimple200(bye, userAgent = shared.config.userAgent)
            shared.outbox.send(ok).getOrThrow()
        } catch (e: Throwable) {
            shared.simEventEmit(transportErrorOf("send BYE 200", e))
        }
        return DialogResult.TerminateDialog("remote BYE")
    }

    companion object {
        /** 默认 ACK 等待超时 = 32s(SIP T1 * 64,RFC 3261 §17.1.1.2)*/
        const val ACK_TIMEOUT_MS: Long = 32_000L
    }
}
