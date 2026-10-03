package com.uvp.sim.domain.devicecontrol

import com.uvp.sim.domain.DeviceControlModel
import com.uvp.sim.domain.LastDeviceCommand
import com.uvp.sim.gb28181.AuxFunction
import com.uvp.sim.gb28181.PtzInstruction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * 辅助开关控制(GB/T 28181-2022 **A.3.7 表 A.11**)—— 只处理标准命名的 **雨刷(编号 1)**。
 * Iris 在 PTZCmd 主报文里已通过 [PtzHandler.handlePtz] 累计,不在此处处理。
 *
 * ⛔⛔ 指令码是 **`0x8C` 开 / `0x8D` 关**(字节5 = 辅助开关编号)。**不是 `0x89`/`0x8A`**
 * —— 那是**扫描**(表 A.10),两族被并成一行写过一次,后果是平台点「开始扫描」设备去开了雨刷
 * (见 `PtzCmdDecoder` 类头)。
 */
interface AuxHandler {
    fun handlePtzAux(p: PtzInstruction.Aux, hex: String)
}

internal class DefaultAuxHandler(
    private val state: MutableStateFlow<DeviceControlModel>,
) : AuxHandler {

    /**
     * 辅助开关 (GB-2022 §A.3.7 表 A.11,byte4=0x8C/0x8D,byte5=编号)。
     *
     * 只有编号 **1 = 雨刷** 有标准语义。其余编号(2~5 等)在标准里没有定义 ——
     * 仍记 lastCommand 便于排障时看到"设备确实收到了一条辅助开关帧",但**不动 auxStates**:
     * 界面上不该为一条语义未知的报文点亮状态灯。
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
