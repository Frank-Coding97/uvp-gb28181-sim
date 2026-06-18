package com.uvp.sim.domain

import kotlin.time.TimeSource
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

/**
 * SIP Date 头校时偏移状态(M5 batch2 §4.15 重定义)。
 *
 * 不修改手机系统时钟 —— 仅记录"平台基准 + 收到时刻"二元组,
 * 后续读"对外时间"时基于单调时钟推进,跟墙钟解耦。
 *
 * 参考:plan §Q1-Q2(校时影响范围 + 时钟模型)。
 *
 * @property platformBaselineMs 平台 Date 头解析出的 epoch ms,null 表示未校时
 * @property recvLocalMs        收到 200 OK 时本地墙钟读数(用于 UI 显示偏移)
 * @property recvMonotonic      收到 200 OK 时单调时钟读数(用于推进对外时间)
 * @property rawDateHeader      原始 Date 头字符串,UI 调试展示用
 */
data class ClockOffset(
    val platformBaselineMs: Long?,
    val recvLocalMs: Long?,
    val recvMonotonic: TimeSource.Monotonic.ValueTimeMark?,
    val rawDateHeader: String?
) {
    val isSynced: Boolean get() = platformBaselineMs != null

    /** 对外时间(epoch ms)= 平台基准 + 单调时钟流逝。未校时降级本地墙钟。 */
    fun adjustedNowMs(): Long {
        val base = platformBaselineMs
        val recv = recvMonotonic
        if (base == null || recv == null) {
            return Clock.System.now().toEpochMilliseconds()
        }
        val elapsedMs = recv.elapsedNow().inWholeMilliseconds
        return base + elapsedMs
    }

    /** 平台时间相对本地墙钟的偏移(ms),正值=平台超前。未校时返回 null。 */
    fun localOffsetMs(): Long? {
        val base = platformBaselineMs ?: return null
        val recv = recvLocalMs ?: return null
        return base - recv
    }

    companion object {
        val Empty = ClockOffset(null, null, null, null)

        /** 注册 200 OK Date 头解析成功后调用,锁定基准 + 当前单调时钟。 */
        fun synced(platformInstant: Instant, rawHeader: String): ClockOffset = ClockOffset(
            platformBaselineMs = platformInstant.toEpochMilliseconds(),
            recvLocalMs = Clock.System.now().toEpochMilliseconds(),
            recvMonotonic = TimeSource.Monotonic.markNow(),
            rawDateHeader = rawHeader
        )
    }
}
