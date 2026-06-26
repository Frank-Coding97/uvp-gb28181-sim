package com.uvp.sim.ui.model

/**
 * UI 层 订阅状态 DTO. 合并 ui.SubscriptionStatus + domain.SubscriptionSnapshot.
 * AppState.subscriptions 字段会切到这个类型.
 */
data class SubscriptionStatusDto(
    val active: Boolean = false,
    val subscriber: String? = null,
    val expiresSeconds: Int? = null,
    val remainingSeconds: Int? = null,
    val notifyCount: Int = 0,
)
