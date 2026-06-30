package com.uvp.sim.domain.coord

import com.uvp.sim.domain.SimEvent
import com.uvp.sim.observability.LogLevel
import com.uvp.sim.observability.LogTag
import com.uvp.sim.observability.SystemLogger
import kotlinx.coroutines.CoroutineScope
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
 *   * pending(200 已发但 ACK 还没来)→ TerminateDialog
 *   * late(ACK 已收 / pre-publication)→ 主类决定(KeepDialog 或 TerminateDialog
 *     by pre-publication race 治本逻辑)
 *
 * 本 task(PR1 T1.2)只迁 watchdog + 字段骨架,verify/ACK/CANCEL/BYE 在 T1.3 / T1.4 迁。
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

    companion object {
        /** 默认 ACK 等待超时 = 32s(SIP T1 * 64,RFC 3261 §17.1.1.2)*/
        const val ACK_TIMEOUT_MS: Long = 32_000L
    }
}
