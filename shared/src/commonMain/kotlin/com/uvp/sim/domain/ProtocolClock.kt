package com.uvp.sim.domain

import kotlin.concurrent.Volatile
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * 所有对外 SIP/MANSCDP 时间的唯一入口。内部超时、日志和状态机仍使用系统/单调时钟。
 * AppEngine 绑定当前会话的 [ClockOffset]；未绑定或未校时时自然退回系统墙钟。
 */
object ProtocolClock {
    @Volatile
    private var provider: (() -> Instant)? = null

    fun now(): Instant = provider?.invoke() ?: Clock.System.now()

    fun install(nowProvider: () -> Instant) {
        provider = nowProvider
    }

    fun reset() {
        provider = null
    }
}
