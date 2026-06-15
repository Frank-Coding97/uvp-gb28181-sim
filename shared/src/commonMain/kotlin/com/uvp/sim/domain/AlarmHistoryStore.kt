package com.uvp.sim.domain

import com.uvp.sim.gb28181.AlarmPayload
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 一条已发报警的历史记录(本会话内存,不持久化,重启清空)。
 *
 * - [payload] 当时发出的完整报警载荷
 * - [firedAtMs] 发送时刻 epoch ms
 * - [notifiedSubscribers] 当时活跃的 Alarm 订阅人数(各收到一条 NOTIFY)
 */
data class AlarmRecord(
    val payload: AlarmPayload,
    val firedAtMs: Long,
    val notifiedSubscribers: Int
)

/**
 * 报警历史的本会话内存存储,只保留最近 [capacity] 条(spec Q6:最近 10 条扁平列表)。
 * 不持久化 —— 报警是事件流,持久化留 M3。
 */
class AlarmHistoryStore(private val capacity: Int = 10) {

    private val _history = MutableStateFlow<List<AlarmRecord>>(emptyList())
    val history: StateFlow<List<AlarmRecord>> = _history.asStateFlow()

    /** 追加一条,超过 capacity 时丢最旧的(列表按时间升序,末尾最新)。 */
    fun append(record: AlarmRecord) {
        _history.update { (it + record).takeLast(capacity) }
    }

    fun clear() {
        _history.value = emptyList()
    }
}
