package com.uvp.sim.domain.devicecontrol

import com.uvp.sim.domain.DeviceControlModel
import com.uvp.sim.domain.DeviceEffect
import com.uvp.sim.domain.LastDeviceCommand
import com.uvp.sim.domain.PtzPose
import com.uvp.sim.gb28181.ManscdpParser
import com.uvp.sim.gb28181.PresetOp
import com.uvp.sim.gb28181.PtzInstruction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * 预置位 CRUD + HomePosition 看守位。
 *
 * - 预置位上限 [maxPresetIndex] = 8(spec Q3)
 * - SET / CALL / DEL 三操作通过 PTZCmd 子族 0x81/0x82/0x83 进入 [handlePtzPreset]
 * - HomePosition 是平台 XML 显式标签,独立 [handleHomePosition] 入口
 */
interface PresetHandler {
    val maxPresetIndex: Int
    fun handlePtzPreset(p: PtzInstruction.Preset, hex: String)
    fun handleHomePosition(xml: String)
}

internal class DefaultPresetHandler(
    private val state: MutableStateFlow<DeviceControlModel>,
    override val maxPresetIndex: Int = 8,
) : PresetHandler {

    /**
     * 预置位 CRUD (GB-2022 §F.3 byte3 = 0x81/0x82/0x83 + byte4 = 编号).
     *
     * - 上限 8 个,index 范围 1-8(spec Q3 决议),越界仅记 lastCommand 不动 presets
     * - SET: 当前姿态入库(可覆盖)+ 设 currentPresetIndex
     * - CALL: 已存在则 emit [DeviceEffect.PresetRecall];不存在仅记 lastCommand
     * - DEL: 移除;若删除的正是 currentPresetIndex 则清零
     */
    override fun handlePtzPreset(p: PtzInstruction.Preset, hex: String) {
        val idx = p.index
        if (idx !in 1..maxPresetIndex) {
            state.update {
                it.copy(
                    lastCommand = LastDeviceCommand(
                        "PTZCmd", "${p.op}#$idx (out-of-range)", nowMs()
                    )
                )
            }
            return
        }
        state.update { s ->
            when (p.op) {
                PresetOp.SET -> {
                    val pose = PtzPose(s.panAngle, s.tiltAngle, s.zoomLevel)
                    s.copy(
                        presets = s.presets + (idx to pose),
                        currentPresetIndex = idx,
                        lastCommand = LastDeviceCommand("PTZCmd", "SetPreset#$idx", nowMs())
                    )
                }
                PresetOp.CALL -> {
                    val target = s.presets[idx]
                    if (target == null) {
                        s.copy(
                            lastCommand = LastDeviceCommand(
                                "PTZCmd", "CallPreset#$idx (empty)", nowMs()
                            )
                        )
                    } else {
                        s.copy(
                            currentPresetIndex = idx,
                            pendingEffect = DeviceEffect.PresetRecall(idx, target),
                            lastCommand = LastDeviceCommand("PTZCmd", "CallPreset#$idx", nowMs())
                        )
                    }
                }
                PresetOp.DEL -> s.copy(
                    presets = s.presets - idx,
                    currentPresetIndex = if (s.currentPresetIndex == idx) null else s.currentPresetIndex,
                    lastCommand = LastDeviceCommand("PTZCmd", "DelPreset#$idx", nowMs())
                )
            }
        }
    }

    override fun handleHomePosition(xml: String) {
        val enabledRaw = ManscdpParser.tagValue(xml, "Enabled")
        val idx = ManscdpParser.tagValue(xml, "PresetIndex")?.toIntOrNull() ?: return
        // Enabled=0 → 关闭看守位
        if (enabledRaw == "0") {
            state.update {
                it.copy(
                    homePositionEnabled = false,
                    lastCommand = LastDeviceCommand("HomePosition", "Disabled", nowMs())
                )
            }
            return
        }
        val existing = state.value.presets[idx]
        if (existing == null) {
            val now = state.value
            val pose = PtzPose(now.panAngle, now.tiltAngle, now.zoomLevel)
            state.update {
                it.copy(
                    presets = it.presets + (idx to pose),
                    currentPresetIndex = idx,
                    homePosition = pose,             // 同步写看守位字段供 Query 应答
                    homePositionEnabled = true,
                    lastCommand = LastDeviceCommand("HomePosition", "Set#$idx", nowMs())
                )
            }
        } else {
            state.update {
                it.copy(
                    currentPresetIndex = idx,
                    homePosition = existing,
                    homePositionEnabled = true,
                    pendingEffect = DeviceEffect.HomePositionReturn(existing),
                    lastCommand = LastDeviceCommand("HomePosition", "Recall#$idx", nowMs())
                )
            }
        }
    }
}
