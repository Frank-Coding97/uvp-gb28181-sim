package com.uvp.sim.ui.model.mapper

import com.uvp.sim.config.DeviceConfig
import com.uvp.sim.config.ServerConfig
import com.uvp.sim.config.SimConfig
import com.uvp.sim.domain.CruiseTrackState
import com.uvp.sim.domain.DeviceCommandCategory
import com.uvp.sim.domain.DeviceControlModel
import com.uvp.sim.domain.DragZoomRect
import com.uvp.sim.domain.LastDeviceCommand
import com.uvp.sim.domain.PtzPose
import com.uvp.sim.domain.UpgradeProgress
import com.uvp.sim.domain.UpgradeResult
import com.uvp.sim.domain.deriveRenderState
import com.uvp.sim.gb28181.FocusDirection
import com.uvp.sim.gb28181.IrisDirection
import com.uvp.sim.gb28181.PanDirection
import com.uvp.sim.gb28181.PtzCommand
import com.uvp.sim.gb28181.TiltDirection
import com.uvp.sim.gb28181.ZoomDirection
import com.uvp.sim.ui.model.CruiseTrackDto
import com.uvp.sim.ui.model.DeviceCommandCategoryDto
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

    /**
     * Mapper 需要本机 [SimConfig]（设备配置族的像素归一化要用视频帧尺寸）。
     *
     * ⛔ 这里的尺寸刻意选 **1920×1080 之外**的小值：坐标归一化的守卫是"分母必须是帧尺寸"，
     * 用一个恰好等于常见分辨率的配置会让"拿错了基准"这类缺陷蒙混过关。
     */
    private val config = SimConfig(
        server = ServerConfig(ip = "127.0.0.1", serverId = "34020000002000000001", domain = "3402000000"),
        device = DeviceConfig(
            deviceId = "34020000001110000001",
            videoChannelId = "34020000001320000001",
            alarmChannelId = "34020000001340000001",
            username = "admin",
            password = "test-password",
        ),
    )

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
    fun deviceControlModel_defaults_full_dto() {
        val dto = DeviceControlModel().toDto(config)
        assertEquals(0f, dto.panAngle)
        assertEquals(1f, dto.zoomLevel)
        assertEquals(false, dto.isRecording)
        assertEquals(true, dto.homePositionEnabled)
        assertEquals(emptyMap(), dto.presets)
        assertNull(dto.dragZoomRect)
        assertNull(dto.upgradeProgress)
        assertNull(dto.pendingEffect)
        assertNull(dto.lastCommandCategory)
    }

    @Test
    fun deviceControlModel_presets_and_cruise_preserved() {
        val model = DeviceControlModel(
            presets = mapOf(1 to PtzPose(1f, 2f, 3f)),
            cruiseTracks = mapOf(1 to CruiseTrackState(points = listOf(1, 2, 3), speed = 300, dwellTime = 45)),
            auxStates = mapOf(1 to true),
            auxTimestamps = mapOf(1 to 1000L),
        )
        val dto = model.toDto(config)
        assertEquals(PtzPoseDto(1f, 2f, 3f), dto.presets[1])
        // 点位链的顺序、以及组级速度/停留时间都要原样过到 UI 层 ——
        // 设备端巡航卡片就是靠这三样做"平台到底设了什么"的对账回显。
        assertEquals(CruiseTrackDto(points = listOf(1, 2, 3), speed = 300, dwellTime = 45), dto.cruiseTracks[1])
        assertEquals(true, dto.auxStates[1])
        assertEquals(1000L, dto.auxTimestamps[1])
    }

    @Test
    fun deviceCommandCategory_maps_all_entries() {
        DeviceCommandCategory.entries.forEach {
            assertEquals(it.name, it.toDto().name)
        }
        assertEquals(DeviceCommandCategory.entries.size, DeviceCommandCategoryDto.entries.size)
    }

    @Test
    fun mapper_carries_lastCommandCategory_from_render_state() {
        // PTZCmd 普通运动 → Ptz 分类
        val ptzModel = DeviceControlModel(
            lastCommand = LastDeviceCommand("PTZCmd", "A50F0102320000DE", 100L),
        )
        assertEquals(DeviceCommandCategoryDto.Ptz, ptzModel.toDto(config).lastCommandCategory)

        // PTZCmd 辅助 → Aux 分类(rawHex 以 "雨刷 ON" 等中文开头)
        val auxModel = DeviceControlModel(
            lastCommand = LastDeviceCommand("PTZCmd", "雨刷 ON", 200L),
        )
        assertEquals(DeviceCommandCategoryDto.Aux, auxModel.toDto(config).lastCommandCategory)

        // SnapShotCmd → Image 分类
        val snapModel = DeviceControlModel(
            lastCommand = LastDeviceCommand("SnapShotCmd", "Start", 300L),
        )
        assertEquals(DeviceCommandCategoryDto.Image, snapModel.toDto(config).lastCommandCategory)

        // RecordCmd → Status 分类
        val recModel = DeviceControlModel(
            lastCommand = LastDeviceCommand("RecordCmd", "Start", 400L),
        )
        assertEquals(DeviceCommandCategoryDto.Status, recModel.toDto(config).lastCommandCategory)
    }

    @Test
    fun mapper_explicit_render_state_overrides_derive() {
        // 直接走 (model, render) 入口,确认渲染派生层的字段优先于 model 派生
        val model = DeviceControlModel(
            lastCommand = LastDeviceCommand("PTZCmd", "A50F", 100L),
        )
        val render = deriveRenderState(model)
        val dto = toDeviceControlDto(model, render, config)
        assertEquals(DeviceCommandCategoryDto.Ptz, dto.lastCommandCategory)
    }
}
