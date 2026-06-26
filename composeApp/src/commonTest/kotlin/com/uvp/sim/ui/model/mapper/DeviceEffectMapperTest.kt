package com.uvp.sim.ui.model.mapper

import com.uvp.sim.domain.DeviceEffect
import com.uvp.sim.domain.PtzPose
import com.uvp.sim.ui.model.DeviceEffectDto
import com.uvp.sim.ui.model.PtzPoseDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class DeviceEffectMapperTest {

    @Test
    fun iFrameFlash_maps_to_object() {
        assertEquals(DeviceEffectDto.IFrameFlash, DeviceEffect.IFrameFlash.toDto())
    }

    @Test
    fun reboot_maps_to_object() {
        assertEquals(DeviceEffectDto.Reboot, DeviceEffect.Reboot.toDto())
    }

    @Test
    fun snapshotFlash_maps_to_object() {
        assertEquals(DeviceEffectDto.SnapshotFlash, DeviceEffect.SnapshotFlash.toDto())
    }

    @Test
    fun homePositionReturn_preserves_pose() {
        val pose = PtzPose(1f, 2f, 3f)
        val dto = DeviceEffect.HomePositionReturn(pose).toDto()
        assertIs<DeviceEffectDto.HomePositionReturn>(dto)
        assertEquals(PtzPoseDto(1f, 2f, 3f), dto.targetPose)
    }

    @Test
    fun presetRecall_preserves_index_and_pose() {
        val dto = DeviceEffect.PresetRecall(5, PtzPose(0f, 0f, 1f)).toDto()
        assertIs<DeviceEffectDto.PresetRecall>(dto)
        assertEquals(5, dto.index)
        assertEquals(PtzPoseDto(0f, 0f, 1f), dto.targetPose)
    }

    @Test
    fun precisePoseGoto_preserves_pose() {
        val dto = DeviceEffect.PrecisePoseGoto(PtzPose(10f, 20f, 5f)).toDto()
        assertIs<DeviceEffectDto.PrecisePoseGoto>(dto)
        assertEquals(PtzPoseDto(10f, 20f, 5f), dto.targetPose)
    }

    @Test
    fun configChanged_preserves_fields_list() {
        val dto = DeviceEffect.ConfigChanged(listOf("device.name", "osd.position")).toDto()
        assertIs<DeviceEffectDto.ConfigChanged>(dto)
        assertEquals(listOf("device.name", "osd.position"), dto.changedFields)
    }

    @Test
    fun deviceUpgradeRequested_preserves_firmware() {
        val dto = DeviceEffect.DeviceUpgradeRequested("v1.0.0").toDto()
        assertIs<DeviceEffectDto.DeviceUpgradeRequested>(dto)
        assertEquals("v1.0.0", dto.firmware)
    }

    @Test
    fun formatSDCardRequested_preserves_index() {
        val dto = DeviceEffect.FormatSDCardRequested(0).toDto()
        assertIs<DeviceEffectDto.FormatSDCardRequested>(dto)
        assertEquals(0, dto.cardIndex)
    }
}
