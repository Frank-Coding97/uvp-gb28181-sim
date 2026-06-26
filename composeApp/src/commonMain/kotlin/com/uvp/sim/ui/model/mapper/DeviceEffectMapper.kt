package com.uvp.sim.ui.model.mapper

import com.uvp.sim.domain.DeviceEffect
import com.uvp.sim.ui.model.DeviceEffectDto

/** PR-A T3.2 实现. sealed when-exhaustive 9 variant. */
fun DeviceEffect.toDto(): DeviceEffectDto = when (this) {
    DeviceEffect.IFrameFlash -> DeviceEffectDto.IFrameFlash
    DeviceEffect.Reboot -> DeviceEffectDto.Reboot
    DeviceEffect.SnapshotFlash -> DeviceEffectDto.SnapshotFlash
    is DeviceEffect.HomePositionReturn -> DeviceEffectDto.HomePositionReturn(targetPose.toDto())
    is DeviceEffect.PresetRecall -> DeviceEffectDto.PresetRecall(index, targetPose.toDto())
    is DeviceEffect.PrecisePoseGoto -> DeviceEffectDto.PrecisePoseGoto(targetPose.toDto())
    is DeviceEffect.ConfigChanged -> DeviceEffectDto.ConfigChanged(changedFields)
    is DeviceEffect.DeviceUpgradeRequested -> DeviceEffectDto.DeviceUpgradeRequested(firmware)
    is DeviceEffect.FormatSDCardRequested -> DeviceEffectDto.FormatSDCardRequested(cardIndex)
}
