package com.uvp.sim.ui.model.mapper

import com.uvp.sim.domain.ClockOffset
import com.uvp.sim.ui.model.ClockOffsetDto

/** PR-A T3.2 实现. drop recvMonotonic 字段(plan §1.5): UI 不需要 monotonic 时延计算. */
fun ClockOffset.toDto(): ClockOffsetDto = ClockOffsetDto(
    platformBaselineMs = platformBaselineMs,
    recvLocalMs = recvLocalMs,
    rawDateHeader = rawDateHeader,
)
