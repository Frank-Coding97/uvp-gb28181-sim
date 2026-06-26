package com.uvp.sim.domain.devicecontrol

import com.uvp.sim.domain.DeviceControlModel
import com.uvp.sim.domain.LastDeviceCommand
import com.uvp.sim.gb28181.AuxFunction
import com.uvp.sim.gb28181.PtzInstruction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * 辅助控制开关 (GB-2022 §F.3 byte3=0x89/0x8A) — 雨刷 / 红外灯 / 加热 / 除雾 / 制冷。
 * Iris 在 PTZCmd 主报文里已通过 [PtzHandler.handlePtz] 累计,不在此处处理。
 */
interface AuxHandler {
    fun handlePtzAux(p: PtzInstruction.Aux, hex: String)
}

internal class DefaultAuxHandler(
    private val state: MutableStateFlow<DeviceControlModel>,
) : AuxHandler {

    /**
     * 辅助控制 (GB-2022 §F.3 byte3=0x89/0x8A).
     *
     * 编号映射(海康/大华事实标准):1=雨刷 / 2=红外灯 / 3=加热 / 4=除雾 / 5=制冷.
     * 未知 index 仍记 lastCommand 但不动 auxStates.
     */
    override fun handlePtzAux(p: PtzInstruction.Aux, hex: String) {
        val func = AuxFunction.fromIndex(p.index)
        val name = func?.displayName ?: "Aux${p.index}"
        val opLabel = if (p.on) "ON" else "OFF"
        val now = nowMs()
        if (func != null) {
            state.update {
                it.copy(
                    auxStates = it.auxStates + (p.index to p.on),
                    auxTimestamps = it.auxTimestamps + (p.index to now),
                    lastCommand = LastDeviceCommand("PTZCmd", "$name $opLabel", now)
                )
            }
        } else {
            state.update {
                it.copy(
                    lastCommand = LastDeviceCommand("PTZCmd", "Aux#${p.index} $opLabel (unmapped)", now)
                )
            }
        }
    }
}
