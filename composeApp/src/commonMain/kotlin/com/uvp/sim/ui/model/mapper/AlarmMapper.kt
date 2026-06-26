package com.uvp.sim.ui.model.mapper

import com.uvp.sim.domain.AlarmRecord
import com.uvp.sim.gb28181.AlarmMethod
import com.uvp.sim.gb28181.AlarmPayload
import com.uvp.sim.gb28181.AlarmPriority
import com.uvp.sim.gb28181.AlarmType
import com.uvp.sim.ui.model.AlarmMethodDto
import com.uvp.sim.ui.model.AlarmPayloadDto
import com.uvp.sim.ui.model.AlarmPriorityDto
import com.uvp.sim.ui.model.AlarmRecordDto
import com.uvp.sim.ui.model.AlarmTypeDto

/** PR-A T3.2 实现. 4 enum 用 valueOf(name), 字段 1:1 映射. */

fun AlarmType.toDto(): AlarmTypeDto = AlarmTypeDto.valueOf(name)

fun AlarmPriority.toDto(): AlarmPriorityDto = AlarmPriorityDto.valueOf(name)

fun AlarmMethod.toDto(): AlarmMethodDto = AlarmMethodDto.valueOf(name)

fun AlarmPayload.toDto(): AlarmPayloadDto = AlarmPayloadDto(
    deviceId = deviceId,
    priority = priority.toDto(),
    method = method.toDto(),
    type = type.toDto(),
    typeParam = typeParam,
    timeMs = timeMs,
    description = description,
    longitude = longitude,
    latitude = latitude,
)

fun AlarmRecord.toDto(): AlarmRecordDto = AlarmRecordDto(
    payload = payload.toDto(),
    firedAtMs = firedAtMs,
    notifiedSubscribers = notifiedSubscribers,
)
