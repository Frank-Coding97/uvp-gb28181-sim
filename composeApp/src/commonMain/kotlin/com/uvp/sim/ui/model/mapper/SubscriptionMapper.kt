package com.uvp.sim.ui.model.mapper

import com.uvp.sim.domain.SubscriptionSnapshot
import com.uvp.sim.ui.model.SubscriptionLifecycleDto
import com.uvp.sim.ui.model.PtzPositionDto
import com.uvp.sim.ui.model.SubscriptionStatusDto

/** PR-A T3.2 实现. */
fun SubscriptionSnapshot.toDto(): SubscriptionStatusDto = SubscriptionStatusDto(
    active = active,
    lifecycle = SubscriptionLifecycleDto.fromName(lifecycle.name),
    subscriber = subscriber,
    expiresSeconds = expiresSeconds,
    remainingSeconds = remainingSeconds,
    notifyCount = notifyCount,
    lastError = lastError,
    lastNotifyResult = lastNotifyResult,
    lastNotifyAtMs = lastNotifyAtMs,
    ptzPosition = ptzPosition?.let {
        PtzPositionDto(
            pan = it.pan,
            tilt = it.tilt,
            zoom = it.zoom,
            horizontalFieldAngle = it.horizontalFieldAngle,
            verticalFieldAngle = it.verticalFieldAngle,
            maxViewDistance = it.maxViewDistance,
        )
    },
)
