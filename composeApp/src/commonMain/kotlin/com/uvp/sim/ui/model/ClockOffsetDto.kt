package com.uvp.sim.ui.model

/**
 * UI 层 SIP Date 校时偏移 DTO. 镜像 com.uvp.sim.domain.ClockOffset.
 * 故意 drop recvMonotonic 字段(plan §1.5): UI 不需要 monotonic 时延计算.
 */
data class ClockOffsetDto(
    val platformBaselineMs: Long?,
    val recvLocalMs: Long?,
    val rawDateHeader: String?,
) {
    companion object {
        val Empty = ClockOffsetDto(null, null, null)
    }
}
