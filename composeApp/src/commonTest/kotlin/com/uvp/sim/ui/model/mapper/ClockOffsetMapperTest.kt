package com.uvp.sim.ui.model.mapper

import com.uvp.sim.domain.ClockOffset
import com.uvp.sim.ui.model.ClockOffsetDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ClockOffsetMapperTest {

    @Test
    fun clockOffset_full_value_maps_and_drops_recvMonotonic() {
        val domain = ClockOffset(
            platformBaselineMs = 1_700_000_000_000L,
            recvLocalMs = 1_700_000_000_500L,
            recvMonotonic = null,  // 即使非 null 也会被 drop
            rawDateHeader = "Sat, 18 Nov 2023 12:00:00 GMT",
        )
        val dto = domain.toDto()
        assertEquals(1_700_000_000_000L, dto.platformBaselineMs)
        assertEquals(1_700_000_000_500L, dto.recvLocalMs)
        assertEquals("Sat, 18 Nov 2023 12:00:00 GMT", dto.rawDateHeader)
    }

    @Test
    fun clockOffset_empty_maps_to_dto_empty() {
        val domain = ClockOffset(null, null, null, null)
        val dto = domain.toDto()
        assertNull(dto.platformBaselineMs)
        assertNull(dto.recvLocalMs)
        assertNull(dto.rawDateHeader)
    }
}
