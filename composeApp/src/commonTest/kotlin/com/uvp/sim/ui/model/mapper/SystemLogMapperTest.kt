package com.uvp.sim.ui.model.mapper

import com.uvp.sim.observability.LogLevel
import com.uvp.sim.observability.LogTag
import com.uvp.sim.observability.SessionMarker
import com.uvp.sim.observability.SystemLog
import kotlin.test.Test
import kotlin.test.assertEquals

class SystemLogMapperTest {

    @Test
    fun systemLog_full_field_mapping_with_api_level_tag() {
        val domain = SystemLog(
            seq = 1L,
            timestampMs = 1000L,
            sessionId = 0,
            level = LogLevel.Info,
            tag = LogTag.Network,
            message = "test",
            detail = "stack...",
        )
        val dto = domain.toDto()
        assertEquals(1L, dto.seq)
        assertEquals(1000L, dto.timestampMs)
        assertEquals(0, dto.sessionId)
        assertEquals(LogLevel.Info, dto.level)  // B 档 api/, 直传(typealias 等价)
        assertEquals(LogTag.Network, dto.tag)
        assertEquals("test", dto.message)
        assertEquals("stack...", dto.detail)
    }

    @Test
    fun systemLog_null_detail_preserved() {
        val dto = SystemLog(1L, 1000L, 0, LogLevel.Debug, LogTag.Lifecycle, "m", null).toDto()
        assertEquals(null, dto.detail)
    }

    @Test
    fun sessionMarker_two_field_mapping() {
        val dto = SessionMarker(sessionId = 42, startedAtMs = 1000L).toDto()
        assertEquals(42, dto.sessionId)
        assertEquals(1000L, dto.startedAtMs)
    }
}
