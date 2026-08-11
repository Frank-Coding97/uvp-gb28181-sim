package com.uvp.sim.ui.model.mapper

import com.uvp.sim.domain.SubscriptionSnapshot
import com.uvp.sim.domain.SubscriptionLifecycle
import com.uvp.sim.gb28181.PtzPositionSnapshot
import com.uvp.sim.ui.model.SubscriptionStatusDto
import com.uvp.sim.ui.model.SubscriptionLifecycleDto
import com.uvp.sim.ui.SubscriptionKind
import com.uvp.sim.ui.notifyCountLabel
import com.uvp.sim.ui.uiDescription
import com.uvp.sim.ui.uiLabel
import kotlin.test.Test
import kotlin.test.assertEquals

class SubscriptionMapperTest {

    @Test
    fun subscriptionSnapshot_full_field_mapping() {
        val domain = SubscriptionSnapshot(
            active = true,
            subscriber = "sip:34020000@example.com",
            expiresSeconds = 3600,
            remainingSeconds = 1800,
            notifyCount = 42,
        )
        val dto = domain.toDto()
        assertEquals(true, dto.active)
        assertEquals("sip:34020000@example.com", dto.subscriber)
        assertEquals(3600, dto.expiresSeconds)
        assertEquals(1800, dto.remainingSeconds)
        assertEquals(42, dto.notifyCount)
    }

    @Test
    fun subscriptionSnapshot_defaults_map_correctly() {
        val dto = SubscriptionSnapshot().toDto()
        assertEquals(SubscriptionStatusDto(), dto)
    }

    @Test
    fun ptz_subscription_maps_lifecycle_result_and_six_fields() {
        val dto = SubscriptionSnapshot(
            active = true,
            lifecycle = SubscriptionLifecycle.Exception,
            lastError = "platform returned SIP 500",
            lastNotifyResult = "Failed",
            lastNotifyAtMs = 1234L,
            ptzPosition = PtzPositionSnapshot(1.0, 2.0, 3.0, 20.0, 12.0, 900.0),
        ).toDto()

        assertEquals(SubscriptionLifecycleDto.Exception, dto.lifecycle)
        assertEquals("platform returned SIP 500", dto.lastError)
        assertEquals("Failed", dto.lastNotifyResult)
        assertEquals(1234L, dto.lastNotifyAtMs)
        assertEquals(20.0, dto.ptzPosition?.horizontalFieldAngle)
        assertEquals(900.0, dto.ptzPosition?.maxViewDistance)
    }

    @Test
    fun lifecycle_unknown_name_falls_back_to_exception() {
        assertEquals(SubscriptionLifecycleDto.Exception, SubscriptionLifecycleDto.fromName("FutureState"))
        assertEquals("已过期", SubscriptionLifecycleDto.Expired.uiLabel())
    }

    @Test
    fun subscription_detail_copy_distinguishes_ptz_ack_semantics() {
        assertEquals(
            "PTZPosition 订阅有效，通知完成以平台 2xx 为准",
            SubscriptionLifecycleDto.Subscribed.uiDescription(SubscriptionKind.PtzPrecisePosition),
        )
        assertEquals(
            "订阅有效，等待业务事件触发通知",
            SubscriptionLifecycleDto.Subscribed.uiDescription(SubscriptionKind.Catalog),
        )
        assertEquals("平台 2xx 完成", SubscriptionKind.PtzPrecisePosition.notifyCountLabel())
        assertEquals("Notify 计数", SubscriptionKind.MobilePosition.notifyCountLabel())
    }
}
