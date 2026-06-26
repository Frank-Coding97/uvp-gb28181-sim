package com.uvp.sim.ui.model

/**
 * UI 层 设备效果 DTO. 1:1 映射 com.uvp.sim.domain.DeviceEffect.
 * 9 variant 全部保留 sealed 形状, UI when-exhaustive 编译期保护.
 */
sealed class DeviceEffectDto {
    data object IFrameFlash : DeviceEffectDto()
    data object Reboot : DeviceEffectDto()
    data object SnapshotFlash : DeviceEffectDto()
    data class HomePositionReturn(val targetPose: PtzPoseDto) : DeviceEffectDto()
    data class PresetRecall(val index: Int, val targetPose: PtzPoseDto) : DeviceEffectDto()
    data class PrecisePoseGoto(val targetPose: PtzPoseDto) : DeviceEffectDto()
    data class ConfigChanged(val changedFields: List<String>) : DeviceEffectDto()
    data class DeviceUpgradeRequested(val firmware: String) : DeviceEffectDto()
    data class FormatSDCardRequested(val cardIndex: Int) : DeviceEffectDto()
}
