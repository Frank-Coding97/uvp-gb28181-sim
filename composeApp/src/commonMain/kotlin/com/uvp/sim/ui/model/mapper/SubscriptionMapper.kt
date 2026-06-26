package com.uvp.sim.ui.model.mapper

import com.uvp.sim.domain.SubscriptionSnapshot
import com.uvp.sim.ui.model.SubscriptionStatusDto

/** PR-A T3.2 实现. */
fun SubscriptionSnapshot.toDto(): SubscriptionStatusDto = SubscriptionStatusDto(
    active = active,
    subscriber = subscriber,
    expiresSeconds = expiresSeconds,
    remainingSeconds = remainingSeconds,
    notifyCount = notifyCount,
)
