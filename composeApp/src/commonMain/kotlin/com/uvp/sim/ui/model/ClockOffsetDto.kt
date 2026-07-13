package com.uvp.sim.ui.model

import kotlin.time.Clock

/**
 * UI 层 SIP Date 校时偏移 DTO. 镜像 com.uvp.sim.domain.ClockOffset.
 *
 * **drop recvMonotonic 字段**(plan §1.5): UI 不需要 monotonic 时延计算.
 *
 * 派生 property 保留(UI 大量使用):
 * - [isSynced] 看 platformBaselineMs != null
 * - [localOffsetMs] 平台基准减本地接收时刻
 * - [adjustedNowMs] 校时后近似当前平台时间(用 recvLocalMs 替代 monotonic, UI 显示精度足够)
 */
data class ClockOffsetDto(
    val platformBaselineMs: Long?,
    val recvLocalMs: Long?,
    val rawDateHeader: String?,
) {
    val isSynced: Boolean get() = platformBaselineMs != null

    /** 对外时间(epoch ms)= 平台基准 + 自接收以来流逝(近似, UI 显示用). */
    fun adjustedNowMs(): Long {
        val base = platformBaselineMs
        val recv = recvLocalMs
        val now = Clock.System.now().toEpochMilliseconds()
        if (base == null || recv == null) return now
        return base + (now - recv)
    }

    /** 平台时间相对本地墙钟的偏移(ms), 正值=平台超前. 未校时返回 null. */
    fun localOffsetMs(): Long? {
        val base = platformBaselineMs ?: return null
        val recv = recvLocalMs ?: return null
        return base - recv
    }

    companion object {
        val Empty = ClockOffsetDto(null, null, null)
    }
}
