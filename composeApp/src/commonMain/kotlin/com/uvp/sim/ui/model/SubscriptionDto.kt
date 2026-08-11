package com.uvp.sim.ui.model

/**
 * UI 层 订阅状态 DTO. 合并 ui.SubscriptionStatus + domain.SubscriptionSnapshot.
 * AppState.subscriptions 字段会切到这个类型.
 */
data class SubscriptionStatusDto(
    val active: Boolean = false,
    val lifecycle: SubscriptionLifecycleDto = SubscriptionLifecycleDto.NotSubscribed,
    val subscriber: String? = null,
    val expiresSeconds: Int? = null,
    val remainingSeconds: Int? = null,
    val notifyCount: Int = 0,
    val lastError: String? = null,
    val lastNotifyResult: String? = null,
    val lastNotifyAtMs: Long? = null,
    val ptzPosition: PtzPositionDto? = null,
)

data class PtzPositionDto(
    val pan: Double,
    val tilt: Double,
    val zoom: Double,
    val horizontalFieldAngle: Double,
    val verticalFieldAngle: Double,
    val maxViewDistance: Double,
)

enum class SubscriptionLifecycleDto {
    NotSubscribed,
    Subscribing,
    Subscribed,
    Expired,
    Cancelled,
    Exception;

    companion object {
        fun fromName(name: String?): SubscriptionLifecycleDto =
            entries.firstOrNull { it.name == name } ?: Exception
    }
}
