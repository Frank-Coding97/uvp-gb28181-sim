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
     * 预置位 CRUD (GB/T 28181 表 A.7:字节4 = 0x81/0x82/0x83,**字节5 固定 0x00、字节6 = 编号**).
     *
     * ⚠️ 编号在**字节6**,不在字节5 —— 见 [com.uvp.sim.gb28181.PtzCmdDecoder.decodePreset]
     * 上的说明。2026-09-16 之前读的是字节5,平台下发的帧一律解成编号 0 被丢弃。
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

    /**
     * 看守位控制(GB/T 28181-2016 §9.3.1 + 附录 A,走 `DeviceControl` 下的 `HomePosition` 元素)。
     *
     * 标准里 `HomePosition` 只有三个字段,且语义是**配置**而不是「现在就去做」:
     *  - `Enabled`(必选):1 开启 / 0 关闭
     *  - `ResetTime`(可选):无云台操作达到该秒数后自动归位
     *  - `PresetIndex`(可选):要回到的预置位号,0~255
     *
     * 2026-09-16 按标准修正了三处原来的实现偏差:
     *  1. `ResetTime` 之前**完全没读**(应答里写死 30)→ 平台侧对账比对 `ResetTime` 必然 mismatch;
     *  2. `PresetIndex` 之前当**必选**处理(缺失就整条 return),标准里它是可选的;
     *  3. 之前收到命令就**立刻**归位(把配置命令当成了调用命令),且预置位不存在时会
     *     **凭空造一个预置位**。真实设备不会因为平台下发了一个不存在的预置位号就新建预置位。
     *     现在只落配置、不动作;「指向的预置位存不存在」由平台侧对账负责
     *     (标准也没定义"已归位"的上报,设备无法替平台判断)。
     *  4. `PresetIndex` / `ResetTime` 现在在入口就钳到标准可表示区间(0~255 / >= 0)。
     *     它们会被 `HomePositionQuery` 的应答原样回传,越界值会让设备此后只能回非法应答。
     *
     * 注意:本方法**不**负责"空闲超时后自动归位"的计时行为,那是独立的设备行为逻辑。
     */
    override fun handleHomePosition(xml: String) {
        val enabled = ManscdpParser.tagValue(xml, "Enabled") != "0"   // 缺省视为开启
        // 入口就钳到标准可表示的区间:`PresetIndex` 0~255,`ResetTime` >= 0。平台侧解析器
        // 对越界值直接判协议非法,而这两个字段会被 `HomePositionQuery` 的应答原样回传 ——
        // 不钳的话,一个手造的越界报文会让这台设备**永久**只能回非法应答,且现象指向查询而不是
        // 当初那条控制命令,极难排查。这里跟 wire 契约绑在一起,所以钳在入口而不是出处。
        val presetIndex = ManscdpParser.tagValue(xml, "PresetIndex")?.toIntOrNull()?.coerceIn(0, 255)
        val resetTime = ManscdpParser.tagValue(xml, "ResetTime")?.toIntOrNull()?.coerceAtLeast(0)
        state.update { s ->
            val effectiveIndex = presetIndex ?: s.homePositionPresetIndex
            s.copy(
                homePositionEnabled = enabled,
                homePositionPresetIndex = effectiveIndex,
                // ResetTime 未下发时保留上一次的值(平台可能只改开关)
                homePositionResetTime = resetTime ?: s.homePositionResetTime,
                // 坐标快照只做回显;查不到就是 null,不造假
                homePosition = effectiveIndex?.let { s.presets[it] },
                lastCommand = LastDeviceCommand(
                    "HomePosition",
                    if (enabled) "Enable#${effectiveIndex ?: "-"}" else "Disable",
                    nowMs()
                )
            )
        }
    }
}
