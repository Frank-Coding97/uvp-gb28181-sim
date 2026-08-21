package com.uvp.sim.domain

import com.uvp.sim.network.Heartbeat
import com.uvp.sim.gb28181.PtzPositionSnapshot
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class SubscriptionLifecycle {
    NotSubscribed,
    Subscribing,
    Subscribed,
    Expired,
    Cancelled,
    Exception,
}

data class SubscriptionDialog(
    val kind: String,
    /** SUBSCRIBE From URI, reused as NOTIFY To URI. */
    val subscriberUri: String,
    /** SUBSCRIBE To URI, reused as NOTIFY From URI. */
    val notifierUri: String,
    /** SUBSCRIBE Contact URI, used as the NOTIFY request target. */
    val notifyRequestUri: String,
    /** Event package (including optional id parameter), echoed by NOTIFY. */
    val event: String,
    val callId: String,
    val fromTag: String,
    val toTag: String,
    val intervalSeconds: Int,
    val expiresSeconds: Int,
    val remainingSeconds: Int,
    val notifyCount: Int = 0,
    val cseqNotify: Int = 0,
    val lifecycle: SubscriptionLifecycle = SubscriptionLifecycle.Subscribed,
    val lastError: String? = null,
    val lastNotifyResult: String? = null,
    val lastNotifyAtMs: Long? = null,
    val lastPtzPosition: PtzPositionSnapshot? = null,
    val lastFailedPtzPosition: PtzPositionSnapshot? = null,
    val inFlightNotifyCseq: Int? = null,
    val pendingPtzPosition: PtzPositionSnapshot? = null,
)

data class SubscriptionSnapshot(
    val active: Boolean = false,
    val lifecycle: SubscriptionLifecycle = SubscriptionLifecycle.NotSubscribed,
    val subscriber: String? = null,
    val expiresSeconds: Int? = null,
    val remainingSeconds: Int? = null,
    val notifyCount: Int = 0,
    val lastError: String? = null,
    val lastNotifyResult: String? = null,
    val lastNotifyAtMs: Long? = null,
    val ptzPosition: PtzPositionSnapshot? = null,
)

sealed class PtzNotifyPreparation {
    data class Send(val dialog: SubscriptionDialog) : PtzNotifyPreparation()
    data object Queued : PtzNotifyPreparation()
    data object Unchanged : PtzNotifyPreparation()
}

sealed class PtzNotifyCompletion {
    data class Confirmed(val nextPosition: PtzPositionSnapshot?) : PtzNotifyCompletion()
    data class Failed(val reason: String) : PtzNotifyCompletion()
}

class SubscriptionRegistry(
    private val scope: CoroutineScope,
    onDialogRemoved: ((SubscriptionDialog) -> Unit)? = null,
) {

    /**
     * dialog 从 _dialogs 移除后统一触发的钩子(fix real-gps plan §3.2 P0)。
     *
     * 3 条移除路径都会 invoke 本钩子:cancel / cancelAll / startExpiryCountdown 内部
     * 归零后的 cancel(callId)。router 侧靠这个钩子知道 MobilePosition 订阅是否清空,
     * 从而决定是否 stop 定位监听。
     *
     * 传入的 dialog 是移除前的快照 —— 保证 kind / subscriberUri 等字段可读。
     *
     * 可后置注册:AppEngine 在装配 SubscriptionRegistry 时 ManscdpRouterImpl 尚未构造,
     * 走 [setOnDialogRemoved] 在 Router 构造完成后回填。
     */
    @Volatile
    private var onDialogRemoved: ((SubscriptionDialog) -> Unit)? = onDialogRemoved

    /** 后置注册钩子;传 null 清除。多次调用最后一次胜出(单一使用者假设)。 */
    fun setOnDialogRemoved(cb: ((SubscriptionDialog) -> Unit)?) {
        onDialogRemoved = cb
    }

    private val _dialogs = MutableStateFlow<Map<String, SubscriptionDialog>>(emptyMap())
    private val terminalSnapshots = mutableMapOf<String, SubscriptionSnapshot>()

    private val notifyJobs = mutableMapOf<String, Heartbeat>()
    private val expiryJobs = mutableMapOf<String, Job>()
    /** 自然过期回调,按 callId 存,倒计时归零时(cancel 前)调用一次。 */
    private val expiryCallbacks = mutableMapOf<String, suspend (SubscriptionDialog) -> Unit>()

    private val _subscriptions = MutableStateFlow<Map<String, SubscriptionSnapshot>>(emptyMap())
    val subscriptions: StateFlow<Map<String, SubscriptionSnapshot>> = _subscriptions.asStateFlow()

    fun activate(
        dialog: SubscriptionDialog,
        onExpire: (suspend (SubscriptionDialog) -> Unit)? = null,
        onNotify: suspend (SubscriptionDialog) -> Unit
    ) {
        _dialogs.update { it + (dialog.callId to dialog) }
        terminalSnapshots.remove(dialog.kind)
        if (onExpire != null) expiryCallbacks[dialog.callId] = onExpire
        rebuildSnapshot()

        // Catalog / Alarm / PTZ 精准位置均事件驱动:
        //   Catalog initial NOTIFY 由调用方在 activate 后单独发,后续靠用户编辑触发
        //   Alarm 无 initial NOTIFY(报警是事件流),后续靠 reportAlarm 触发
        // 只有 MobilePosition 走 Heartbeat 周期推。
        if (dialog.kind == "MobilePosition") {
            val heartbeat = Heartbeat(
                intervalMillis = dialog.intervalSeconds * 1000L,
                scope = scope
            ) {
                val current = _dialogs.value[dialog.callId] ?: return@Heartbeat
                val updated = current.copy(
                    notifyCount = current.notifyCount + 1,
                    cseqNotify = current.cseqNotify + 1
                )
                _dialogs.update { it + (dialog.callId to updated) }
                rebuildSnapshot()
                onNotify(updated)
            }
            notifyJobs[dialog.callId]?.stop()
            notifyJobs[dialog.callId] = heartbeat
            heartbeat.start()
        }

        startExpiryCountdown(dialog.callId, dialog.expiresSeconds)
    }

    /**
     * 给调用方在事件驱动场景(Catalog 用户编辑后)主动推送 NOTIFY 用。
     * 自增 notifyCount + cseqNotify,返回更新后的 dialog。返回 null 表示
     * 该 callId 已不在订阅集中。
     */
    fun bumpNotify(callId: String): SubscriptionDialog? {
        val current = _dialogs.value[callId] ?: return null
        val updated = current.copy(
            notifyCount = current.notifyCount + 1,
            cseqNotify = current.cseqNotify + 1
        )
        _dialogs.update { it + (callId to updated) }
        rebuildSnapshot()
        return updated
    }

    /** 拿到所有指定 kind 的 dialog 副本(用于 SimulatorEngine 主动推送)。 */
    fun dialogsByKind(kind: String): List<SubscriptionDialog> =
        _dialogs.value.values.filter { it.kind == kind }

    fun refresh(callId: String, newExpires: Int) {
        _dialogs.update { map ->
            val existing = map[callId] ?: return@update map
            map + (callId to existing.copy(
                expiresSeconds = newExpires,
                remainingSeconds = newExpires
            ))
        }
        rebuildSnapshot()
        expiryJobs[callId]?.cancel()
        startExpiryCountdown(callId, newExpires)
    }

    fun cancel(callId: String, terminalLifecycle: SubscriptionLifecycle? = null) {
        val removed = _dialogs.value[callId]
        notifyJobs[callId]?.stop()
        notifyJobs.remove(callId)
        expiryJobs[callId]?.cancel()
        expiryJobs.remove(callId)
        expiryCallbacks.remove(callId)
        _dialogs.update { it - callId }
        if (removed?.kind == "PtzPrecisePosition" && terminalLifecycle != null) {
            terminalSnapshots[removed.kind] = SubscriptionSnapshot(
                active = false,
                lifecycle = terminalLifecycle,
                subscriber = removed.subscriberUri,
                expiresSeconds = removed.expiresSeconds,
                remainingSeconds = if (terminalLifecycle == SubscriptionLifecycle.Expired) 0 else removed.remainingSeconds,
                notifyCount = removed.notifyCount,
                lastError = removed.lastError,
                lastNotifyResult = removed.lastNotifyResult,
                lastNotifyAtMs = removed.lastNotifyAtMs,
                ptzPosition = removed.lastPtzPosition,
            )
        }
        rebuildSnapshot()
        if (removed != null) onDialogRemoved?.invoke(removed)
    }

    fun cancelAll() {
        val removedList = _dialogs.value.values.toList()
        notifyJobs.values.forEach { it.stop() }
        notifyJobs.clear()
        expiryJobs.values.forEach { it.cancel() }
        expiryJobs.clear()
        expiryCallbacks.clear()
        terminalSnapshots.clear()
        _dialogs.value = emptyMap()
        rebuildSnapshot()
        removedList.forEach { onDialogRemoved?.invoke(it) }
    }

    fun currentDialog(callId: String): SubscriptionDialog? = _dialogs.value[callId]

    fun knownCallIds(): Set<String> = _dialogs.value.keys

    fun preparePtzNotify(callId: String, position: PtzPositionSnapshot): PtzNotifyPreparation {
        val current = _dialogs.value[callId] ?: return PtzNotifyPreparation.Unchanged
        if (current.kind != "PtzPrecisePosition") return PtzNotifyPreparation.Unchanged
        if (current.inFlightNotifyCseq != null) {
            if (current.lastPtzPosition != position) {
                val updated = current.copy(pendingPtzPosition = position)
                _dialogs.update { it + (callId to updated) }
                rebuildSnapshot()
            }
            return PtzNotifyPreparation.Queued
        }
        if (current.lastPtzPosition == position) return PtzNotifyPreparation.Unchanged
        if (current.lastFailedPtzPosition == position) return PtzNotifyPreparation.Unchanged

        val updated = current.copy(
            cseqNotify = current.cseqNotify + 1,
            lifecycle = SubscriptionLifecycle.Subscribing,
            lastError = null,
            lastNotifyResult = "Pending",
            lastPtzPosition = position,
            lastFailedPtzPosition = null,
            inFlightNotifyCseq = current.cseqNotify + 1,
            pendingPtzPosition = null,
        )
        _dialogs.update { it + (callId to updated) }
        rebuildSnapshot()
        return PtzNotifyPreparation.Send(updated)
    }

    fun completePtzNotify(callId: String, cseq: Int, statusCode: Int): PtzNotifyCompletion? {
        val current = _dialogs.value[callId] ?: return null
        if (current.kind != "PtzPrecisePosition" || current.inFlightNotifyCseq != cseq) return null
        if (statusCode !in 200..299) {
            return failPtzNotify(callId, cseq, "platform returned SIP $statusCode")
        }
        val next = current.pendingPtzPosition
        val updated = current.copy(
            lifecycle = SubscriptionLifecycle.Subscribed,
            notifyCount = current.notifyCount + 1,
            lastNotifyResult = "Confirmed",
            lastNotifyAtMs = kotlin.time.Clock.System.now().toEpochMilliseconds(),
            lastFailedPtzPosition = null,
            inFlightNotifyCseq = null,
            pendingPtzPosition = null,
        )
        _dialogs.update { it + (callId to updated) }
        rebuildSnapshot()
        return PtzNotifyCompletion.Confirmed(next)
    }

    fun timeoutPtzNotify(callId: String, cseq: Int): PtzNotifyCompletion? =
        failPtzNotify(callId, cseq, "platform NOTIFY response timeout")

    fun failPtzNotifyTransport(callId: String, cseq: Int, reason: String): PtzNotifyCompletion? =
        failPtzNotify(callId, cseq, reason)

    private fun failPtzNotify(callId: String, cseq: Int, reason: String): PtzNotifyCompletion? {
        val current = _dialogs.value[callId] ?: return null
        if (current.kind != "PtzPrecisePosition" || current.inFlightNotifyCseq != cseq) return null
        val updated = current.copy(
            lifecycle = SubscriptionLifecycle.Exception,
            lastError = reason,
            lastNotifyResult = "Failed",
            lastNotifyAtMs = kotlin.time.Clock.System.now().toEpochMilliseconds(),
            lastFailedPtzPosition = current.lastPtzPosition,
            inFlightNotifyCseq = null,
            pendingPtzPosition = null,
        )
        _dialogs.update { it + (callId to updated) }
        rebuildSnapshot()
        return PtzNotifyCompletion.Failed(reason)
    }

    private fun startExpiryCountdown(callId: String, totalSeconds: Int) {
        expiryJobs[callId]?.cancel()
        expiryJobs[callId] = scope.launch {
            var remaining = totalSeconds
            while (isActive && remaining > 0) {
                delay(1000L)
                remaining--
                _dialogs.update { map ->
                    val d = map[callId] ?: return@update map
                    map + (callId to d.copy(remainingSeconds = remaining))
                }
                rebuildSnapshot()
            }
            if (isActive) {
                val expiring = _dialogs.value[callId]
                val cb = expiryCallbacks[callId]
                if (expiring != null && cb != null) cb(expiring)
                cancel(
                    callId,
                    terminalLifecycle = if (expiring?.kind == "PtzPrecisePosition") {
                        SubscriptionLifecycle.Expired
                    } else {
                        null
                    },
                )
            }
        }
    }

    private fun rebuildSnapshot() {
        val grouped = _dialogs.value.values.groupBy { it.kind }
        val result = terminalSnapshots.toMutableMap()
        for ((kind, dialogs) in grouped) {
            val first = dialogs.first()
            result[kind] = SubscriptionSnapshot(
                active = true,
                lifecycle = first.lifecycle,
                subscriber = first.subscriberUri,
                expiresSeconds = first.expiresSeconds,
                remainingSeconds = first.remainingSeconds,
                notifyCount = dialogs.sumOf { it.notifyCount },
                lastError = first.lastError,
                lastNotifyResult = first.lastNotifyResult,
                lastNotifyAtMs = first.lastNotifyAtMs,
                ptzPosition = first.lastPtzPosition,
            )
        }
        _subscriptions.value = result
    }
}
