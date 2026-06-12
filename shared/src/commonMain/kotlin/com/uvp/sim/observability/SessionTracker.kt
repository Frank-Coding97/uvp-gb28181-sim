package com.uvp.sim.observability

import kotlinx.datetime.Clock

/**
 * 当前进程会话标识。每次进程冷启动 sessionId 自增。
 *
 * 持久化策略:
 * - 平台壳启动时调用 [install] 注入 [SessionStore](Android: SharedPreferences,
 *   iOS: UserDefaults,JVM 测试: 内存)。
 * - 未 install 时使用内存 store(测试默认 / 没接的平台兜底)。
 *
 * 老板每次冷启动看到"会话 #3 #4 #5..."单调递增,有时间感(spec Q7);
 * 不持久化 log 内容(P0 内存),那是 P1 的事。
 */
object SessionTracker {

    private var store: SessionStore = MemorySessionStore()
    private var initialized = false
    private var _currentId: Int = 1
    private var _startedAtMs: Long = 0L

    val currentId: Int
        get() = ensureInit()._currentId

    val startedAtMs: Long
        get() = ensureInit()._startedAtMs

    val current: SessionMarker get() = SessionMarker(currentId, startedAtMs)

    /** 平台壳启动时注入持久化 store。重复调用以新 store 重建。 */
    fun install(newStore: SessionStore) {
        store = newStore
        initialized = false
        ensureInit()
    }

    private fun ensureInit(): SessionTracker {
        if (!initialized) {
            _currentId = store.readLastSessionId() + 1
            _startedAtMs = Clock.System.now().toEpochMilliseconds()
            store.writeLastSessionId(_currentId)
            initialized = true
        }
        return this
    }

    /** 仅供测试 — 重置到未初始化状态。 */
    internal fun resetForTest(newStore: SessionStore = MemorySessionStore()) {
        store = newStore
        initialized = false
        _currentId = 1
        _startedAtMs = 0L
    }
}

/** 会话计数器持久化抽象。每个平台一个 actual 实现。 */
interface SessionStore {
    fun readLastSessionId(): Int
    fun writeLastSessionId(id: Int)
}

/** 进程内 store — JVM 测试 / 未注入时的兜底。 */
class MemorySessionStore : SessionStore {
    private var value: Int = 0
    override fun readLastSessionId(): Int = value
    override fun writeLastSessionId(id: Int) { value = id }
}
