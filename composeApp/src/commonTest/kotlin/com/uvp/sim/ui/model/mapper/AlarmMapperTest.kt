package com.uvp.sim.ui.model.mapper

import com.uvp.sim.domain.AlarmRecord
import com.uvp.sim.gb28181.AlarmMethod
import com.uvp.sim.gb28181.AlarmPayload
import com.uvp.sim.gb28181.AlarmPriority
import com.uvp.sim.gb28181.AlarmType
import com.uvp.sim.ui.model.AlarmMethodDto
import com.uvp.sim.ui.model.AlarmPriorityDto
import com.uvp.sim.ui.model.AlarmTypeDto
import kotlin.test.Test
import kotlin.test.assertEquals

class AlarmMapperTest {

    @Test
    fun alarmType_all_entries_match_code_and_label() {
        AlarmType.entries.forEach { domain ->
            val dto = domain.toDto()
            assertEquals(domain.code, dto.code)
            assertEquals(domain.label, dto.label)
            assertEquals(domain.name, dto.name)
        }
        assertEquals(AlarmType.entries.size, AlarmTypeDto.entries.size)
    }

    @Test
    fun alarmPriority_all_entries_match() {
        AlarmPriority.entries.forEach { domain ->
            val dto = domain.toDto()
            assertEquals(domain.code, dto.code)
            assertEquals(domain.label, dto.label)
        }
        assertEquals(AlarmPriority.entries.size, AlarmPriorityDto.entries.size)
    }

    @Test
    fun alarmMethod_all_entries_match() {
        AlarmMethod.entries.forEach { domain ->
            val dto = domain.toDto()
            assertEquals(domain.code, dto.code)
            assertEquals(domain.label, dto.label)
        }
        assertEquals(AlarmMethod.entries.size, AlarmMethodDto.entries.size)
    }

    @Test
    fun alarmPayload_full_field_mapping() {
        val domain = AlarmPayload(
            deviceId = "34020000",
            priority = AlarmPriority.EmergencyL1,
            method = AlarmMethod.Device,
            type = AlarmType.VideoLost,
            typeParam = "param-1",
            timeMs = 1_700_000_000_000L,
            description = "测试报警",
            longitude = 121.5,
            latitude = 31.2,
        )
        val dto = domain.toDto()
        assertEquals("34020000", dto.deviceId)
        assertEquals(AlarmPriorityDto.EmergencyL1, dto.priority)
        assertEquals(AlarmMethodDto.Device, dto.method)
        assertEquals(AlarmTypeDto.VideoLost, dto.type)
        assertEquals("param-1", dto.typeParam)
        assertEquals(1_700_000_000_000L, dto.timeMs)
        assertEquals("测试报警", dto.description)
        assertEquals(121.5, dto.longitude)
        assertEquals(31.2, dto.latitude)
    }

    @Test
    fun alarmRecord_nests_payload() {
        val payload = AlarmPayload(deviceId = "34020000")
        val domain = AlarmRecord(payload = payload, firedAtMs = 1000L, notifiedSubscribers = 3)
        val dto = domain.toDto()
        assertEquals(1000L, dto.firedAtMs)
        assertEquals(3, dto.notifiedSubscribers)
        assertEquals("34020000", dto.payload.deviceId)
    }
}
