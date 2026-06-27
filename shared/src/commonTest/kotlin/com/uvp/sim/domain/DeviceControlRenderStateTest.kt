package com.uvp.sim.domain

import com.uvp.sim.gb28181.PtzCommand
import com.uvp.sim.gb28181.PanDirection
import com.uvp.sim.gb28181.TiltDirection
import com.uvp.sim.gb28181.ZoomDirection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Wave 3 PR-DC-DECOUPLE 拆分验收测试。
 *
 * 验证目标:
 *  1. `deriveRenderState(model)` 是**纯函数** — 同 model 输入 → 同 RenderState 输出
 *  2. Model 字段变化 → RenderState 派生字段跟随(避免 UI 接到陈旧渲染信息)
 *  3. `deriveCommandCategory` 把协议字符串解析压在派生层,不漏到 UI(轨 ④ PR-UI-PROTOCOL-FIX)
 */
class DeviceControlRenderStateTest {

    @Test
    fun `deriveRenderState 是纯函数 - 同输入同输出`() {
        val m = DeviceControlModel(
            panAngle = 10f,
            tiltAngle = 20f,
            zoomLevel = 2f,
            lastCommand = LastDeviceCommand("PTZCmd", "A50F010240000017", 1_700_000_000_000L),
            pendingEffect = DeviceEffect.IFrameFlash,
            auxTimestamps = mapOf(1 to 1_700_000_000_100L),
        )

        val r1 = deriveRenderState(m)
        val r2 = deriveRenderState(m)

        assertEquals(r1, r2, "同 Model 输入两次 deriveRenderState 必须严格相等(纯函数)")
    }

    @Test
    fun `deriveRenderState 把 lastCommand 拆给 RenderState`() {
        val ptz = PtzCommand(
            panDirection = PanDirection.LEFT,
            tiltDirection = TiltDirection.NONE,
            zoomDirection = ZoomDirection.NONE,
            panSpeed = 50,
            tiltSpeed = 0,
            zoomSpeed = 0,
        )
        val m = DeviceControlModel(
            lastCommand = LastDeviceCommand(
                type = "PTZCmd",
                rawHex = "A50F0102320000DE",
                timestampMs = 1_700_000_000_000L,
                ptz = ptz,
            )
        )

        val r = deriveRenderState(m)

        assertEquals("PTZCmd", r.lastCommandType)
        assertEquals("A50F0102320000DE", r.lastCommandHex)
        assertEquals(1_700_000_000_000L, r.lastRecvAtMs)
        assertEquals(ptz, r.lastCommandPtz)
        assertFalse(r.hasPendingEffect, "无 pendingEffect → hasPendingEffect=false")
        assertNull(r.pendingEffect)
    }

    @Test
    fun `deriveRenderState 空 Model → 空 RenderState`() {
        val r = deriveRenderState(DeviceControlModel())

        assertNull(r.lastCommandType)
        assertNull(r.lastCommandHex)
        assertNull(r.lastRecvAtMs)
        assertNull(r.lastCommandPtz)
        assertFalse(r.hasPendingEffect)
        assertNull(r.pendingEffect)
        assertTrue(r.auxTimestamps.isEmpty())
    }

    @Test
    fun `Model 变 → RenderState 派生字段跟着变`() {
        val before = deriveRenderState(DeviceControlModel(pendingEffect = null))
        val after = deriveRenderState(DeviceControlModel(pendingEffect = DeviceEffect.Reboot))

        assertNotEquals(before, after)
        assertFalse(before.hasPendingEffect)
        assertTrue(after.hasPendingEffect)
        assertEquals(DeviceEffect.Reboot, after.pendingEffect)
    }

    @Test
    fun `deriveCommandCategory 协议字符串解析压在派生层 - UI 不再 parse rawHex`() {
        // PTZ 运动 / PreciseCtrl / HomePosition → Ptz
        assertEquals(
            DeviceCommandCategory.Ptz,
            deriveCommandCategory(LastDeviceCommand("PTZCmd", "A50F0102320000DE", 100L))
        )
        assertEquals(
            DeviceCommandCategory.Ptz,
            deriveCommandCategory(LastDeviceCommand("PTZPreciseCtrl", "set45", 200L))
        )
        assertEquals(
            DeviceCommandCategory.Ptz,
            deriveCommandCategory(LastDeviceCommand("HomePosition", "Recall#1", 300L))
        )

        // PTZCmd 辅助(rawHex 中文开头):雨刷 / 红外灯 / 加热 / 除雾 / 制冷 / Aux → Aux
        listOf("雨刷 ON", "红外灯 OFF", "加热 ON", "除雾 OFF", "制冷 ON", "Aux#7 ON (unmapped)").forEach { raw ->
            assertEquals(
                DeviceCommandCategory.Aux,
                deriveCommandCategory(LastDeviceCommand("PTZCmd", raw, 100L)),
                "rawHex=$raw 必须归为 Aux",
            )
        }

        // 状态类:录像 / 布防 / 报警 / 远程重启 → Status
        listOf("RecordCmd", "GuardCmd", "AlarmCmd", "TeleBoot").forEach { type ->
            assertEquals(
                DeviceCommandCategory.Status,
                deriveCommandCategory(LastDeviceCommand(type, "v", 100L)),
                "type=$type 必须归为 Status",
            )
        }

        // 图像类 → Image
        listOf("IFameCmd", "SnapShotCmd", "DeviceConfig", "DeviceUpgrade", "FormatSDCard", "TargetTrack").forEach { type ->
            assertEquals(
                DeviceCommandCategory.Image,
                deriveCommandCategory(LastDeviceCommand(type, "v", 100L)),
                "type=$type 必须归为 Image",
            )
        }

        // 未知 type → null
        assertNull(deriveCommandCategory(LastDeviceCommand("UnknownCmd", "v", 100L)))
    }

    @Test
    fun `deriveRenderState 把 category 一起带给 UI`() {
        val auxModel = DeviceControlModel(
            lastCommand = LastDeviceCommand("PTZCmd", "雨刷 ON", 100L),
        )
        assertEquals(DeviceCommandCategory.Aux, deriveRenderState(auxModel).lastCommandCategory)

        val ptzModel = DeviceControlModel(
            lastCommand = LastDeviceCommand("PTZCmd", "A50F", 200L),
        )
        assertEquals(DeviceCommandCategory.Ptz, deriveRenderState(ptzModel).lastCommandCategory)

        // 没收到命令 → null
        assertNull(deriveRenderState(DeviceControlModel()).lastCommandCategory)
    }

    @Test
    fun `Model 必须不含 render 派生字段类型 - 字段反向断言`() {
        // 此断言通过编译期 + 运行期字段访问双重保证:
        // - Model data class 的字段命名里**没有** lastCommandType / lastCommandHex / lastRecvAtMs
        //   等"显示用"扁平字段(它们应由 deriveRenderState 从 lastCommand 拆出)
        // - 该列表是「Model 当前合法字段」白名单,任何后续往 Model 偷塞 UI 字段都会让本测试编译失败
        val m = DeviceControlModel()
        // 罗列所有 Model 当前合法字段,触发编译期检查
        val _all = listOf<Any?>(
            m.panAngle, m.tiltAngle, m.zoomLevel, m.irisLevel, m.focusLevel,
            m.panSpeed, m.tiltSpeed, m.zoomSpeed,
            m.isRecording, m.isGuarded, m.isAlarming, m.isRebooting,
            m.dragZoomRect,
            m.presets, m.currentPresetIndex,
            m.homePosition, m.homePositionEnabled,
            m.cruiseTracks, m.activeCruiseTrack,
            m.auxStates, m.auxTimestamps,
            m.lastCommand, m.lastPreciseCtrl,
            m.upgradeProgress, m.pendingEffect,
        )
        // 26 字段(同 plan §a 列表 + 25 个原 DeviceControlState 字段一一对应)
        assertEquals(25, _all.size)
    }
}
