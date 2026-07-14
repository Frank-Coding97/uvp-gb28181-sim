package com.uvp.sim.domain

import com.uvp.sim.network.Heartbeat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class SubscriptionDialog(
    val kind: String,
    val subscriberUri: String,
    val callId: String,
    val fromTag: String,
    val toTag: String,
    val intervalSeconds: Int,
    val expiresSeconds: Int,
    val remainingSeconds: Int,
    val notifyCount: Int = 0,
    val cseqNotify: Int = 0
)

data class SubscriptionSnapshot(
    val active: Boolean = false,
    val subscriber: String? = null,
    val expiresSeconds: Int? = null,
    val remainingSeconds: Int? = null,
    val notifyCount: Int = 0
)

class SubscriptionRegistry(
    private val scope: CoroutineScope,
    /**
     * dialog 从 _dialogs 移除后统一触发的钩子(fix real-gps plan §3.2 P0)。
     *
     * 3 条移除路径都会 invoke 本钩子:cancel / cancelAll / startExpiryCountdown 内部
     * 归零后的 cancel(callId)。router 侧靠这个钩子知道 MobilePosition 订阅是否清空,
     * 从而决定是否 stop 定位监听。
     *
     * 传入的 dialog 是移除前的快照 —— 保证 kind / subscriberUri 等字段可读。
     */
    private val onDialogRemoved: ((SubscriptionDialog) -> Unit)? = null,
) {

    private val _dialogs = MutableStateFlow<Map<String, SubscriptionDialog>>(emptyMap())

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
        if (onExpire != null) expiryCallbacks[dialog.callId] = onExpire
        rebuildSnapshot()

        // Catalog / Alarm 不周期推送 — 事件驱动:
        //   Catalog initial NOTIFY 由调用方在 activate 后单独发,后续靠用户编辑触发
        //   Alarm 无 initial NOTIFY(报警是事件流),后续靠 reportAlarm 触发
        // 只有 MobilePosition 走 Heartbeat 周期推。
        if (dialog.kind != "Catalog" && dialog.kind != "Alarm") {
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

    fun cancel(callId: String) {
        val removed = _dialogs.value[callId]
        notifyJobs[callId]?.stop()
        notifyJobs.remove(callId)
        expiryJobs[callId]?.cancel()
        expiryJobs.remove(callId)
        expiryCallbacks.remove(callId)
        _dialogs.update { it - callId }
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
        _dialogs.value = emptyMap()
        rebuildSnapshot()
        removedList.forEach { onDialogRemoved?.invoke(it) }
    }

    fun currentDialog(callId: String): SubscriptionDialog? = _dialogs.value[callId]

    fun knownCallIds(): Set<String> = _dialogs.value.keys

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
                cancel(callId)
            }
        }
    }

    private fun rebuildSnapshot() {
        val grouped = _dialogs.value.values.groupBy { it.kind }
        val result = mutableMapOf<String, SubscriptionSnapshot>()
        for ((kind, dialogs) in grouped) {
            val first = dialogs.first()
            result[kind] = SubscriptionSnapshot(
                active = true,
                subscriber = first.subscriberUri,
                expiresSeconds = first.expiresSeconds,
                remainingSeconds = first.remainingSeconds,
                notifyCount = dialogs.sumOf { it.notifyCount }
            )
        }
        _subscriptions.value = result
    }
}
