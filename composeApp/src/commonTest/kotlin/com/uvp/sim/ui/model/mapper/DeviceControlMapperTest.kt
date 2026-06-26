package com.uvp.sim.ui.model.mapper

import com.uvp.sim.domain.DeviceControlState
import com.uvp.sim.domain.DragZoomRect
import com.uvp.sim.domain.LastDeviceCommand
import com.uvp.sim.domain.PtzPose
import com.uvp.sim.domain.UpgradeProgress
import com.uvp.sim.domain.UpgradeResult
import com.uvp.sim.gb28181.FocusDirection
import com.uvp.sim.gb28181.IrisDirection
import com.uvp.sim.gb28181.PanDirection
import com.uvp.sim.gb28181.PtzCommand
import com.uvp.sim.gb28181.TiltDirection
import com.uvp.sim.gb28181.ZoomDirection
import com.uvp.sim.ui.model.FocusDirectionDto
import com.uvp.sim.ui.model.IrisDirectionDto
import com.uvp.sim.ui.model.PanDirectionDto
import com.uvp.sim.ui.model.PtzPoseDto
import com.uvp.sim.ui.model.TiltDirectionDto
import com.uvp.sim.ui.model.UpgradeProgressDto
import com.uvp.sim.ui.model.UpgradeResultDto
import com.uvp.sim.ui.model.ZoomDirectionDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DeviceControlMapperTest {

    @Test
    fun ptzPose_maps_three_floats() {
        assertEquals(PtzPoseDto(1f, 2f, 3f), PtzPose(1f, 2f, 3f).toDto())
    }

    @Test
    fun dragZoomRect_maps_four_ints() {
        val dto = DragZoomRect(100, 200, 50, 60).toDto()
        assertEquals(100, dto.midX)
        assertEquals(200, dto.midY)
        assertEquals(50, dto.lengthX)
        assertEquals(60, dto.lengthY)
    }

    @Test
    fun upgradeResult_all_entries_match() {
        UpgradeResult.entries.forEach {
            assertEquals(it.name, it.toDto().name)
        }
        assertEquals(UpgradeResult.entries.size, UpgradeResultDto.entries.size)
    }

    @Test
    fun upgradeProgress_full_field_mapping() {
        val dto = UpgradeProgress(
            sessionId = "ses-1",
            firmware = "v2.0",
            percent = 50,
            result = UpgradeResult.InProgress,
        ).toDto()
        assertEquals("ses-1", dto.sessionId)
        assertEquals("v2.0", dto.firmware)
        assertEquals(50, dto.percent)
        assertEquals(UpgradeResultDto.InProgress, dto.result)
    }

    @Test
    fun ptzCommand_all_direction_enums_map() {
        PanDirection.entries.forEach { assertEquals(it.name, it.toDto().name) }
        TiltDirection.entries.forEach { assertEquals(it.name, it.toDto().name) }
        ZoomDirection.entries.forEach { assertEquals(it.name, it.toDto().name) }
        FocusDirection.entries.forEach { assertEquals(it.name, it.toDto().name) }
        IrisDirection.entries.forEach { assertEquals(it.name, it.toDto().name) }
        assertEquals(PanDirection.entries.size, PanDirectionDto.entries.size)
        assertEquals(TiltDirection.entries.size, TiltDirectionDto.entries.size)
        assertEquals(ZoomDirection.entries.size, ZoomDirectionDto.entries.size)
        assertEquals(FocusDirection.entries.size, FocusDirectionDto.entries.size)
        assertEquals(IrisDirection.entries.size, IrisDirectionDto.entries.size)
    }

    @Test
    fun ptzCommand_full_field_mapping() {
        val dto = PtzCommand(
            panDirection = PanDirection.LEFT,
            tiltDirection = TiltDirection.UP,
            zoomDirection = ZoomDirection.IN,
            focusDirection = FocusDirection.NEAR,
            irisDirection = IrisDirection.OPEN,
            panSpeed = 5,
            tiltSpeed = 6,
            zoomSpeed = 7,
            focusSpeed = 8,
            irisSpeed = 9,
        ).toDto()
        assertEquals(PanDirectionDto.LEFT, dto.panDirection)
        assertEquals(TiltDirectionDto.UP, dto.tiltDirection)
        assertEquals(ZoomDirectionDto.IN, dto.zoomDirection)
        assertEquals(FocusDirectionDto.NEAR, dto.focusDirection)
        assertEquals(IrisDirectionDto.OPEN, dto.irisDirection)
        assertEquals(5, dto.panSpeed)
        assertEquals(9, dto.irisSpeed)
    }

    @Test
    fun lastDeviceCommand_with_null_ptz() {
        val dto = LastDeviceCommand("DeviceControl", "0xDEADBEEF", 1000L, ptz = null).toDto()
        assertEquals("DeviceControl", dto.type)
        assertEquals("0xDEADBEEF", dto.rawHex)
        assertEquals(1000L, dto.timestampMs)
        assertNull(dto.ptz)
    }

    @Test
    fun deviceControlState_defaults_full_dto() {
        val dto = DeviceControlState().toDto()
        assertEquals(0f, dto.panAngle)
        assertEquals(1f, dto.zoomLevel)
        assertEquals(false, dto.isRecording)
        assertEquals(true, dto.homePositionEnabled)
        assertEquals(emptyMap(), dto.presets)
        assertNull(dto.dragZoomRect)
        assertNull(dto.upgradeProgress)
        assertNull(dto.pendingEffect)
    }

    @Test
    fun deviceControlState_presets_and_cruise_preserved() {
        val state = DeviceControlState(
            presets = mapOf(1 to PtzPose(1f, 2f, 3f)),
            cruiseTracks = mapOf(1 to listOf(1, 2, 3)),
            auxStates = mapOf(1 to true),
            auxTimestamps = mapOf(1 to 1000L),
        )
        val dto = state.toDto()
        assertEquals(PtzPoseDto(1f, 2f, 3f), dto.presets[1])
        assertEquals(listOf(1, 2, 3), dto.cruiseTracks[1])
        assertEquals(true, dto.auxStates[1])
        assertEquals(1000L, dto.auxTimestamps[1])
    }
}
