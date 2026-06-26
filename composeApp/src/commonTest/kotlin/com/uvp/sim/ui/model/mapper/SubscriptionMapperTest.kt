package com.uvp.sim.ui.model.mapper

import com.uvp.sim.domain.SubscriptionSnapshot
import com.uvp.sim.ui.model.SubscriptionStatusDto
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
}
