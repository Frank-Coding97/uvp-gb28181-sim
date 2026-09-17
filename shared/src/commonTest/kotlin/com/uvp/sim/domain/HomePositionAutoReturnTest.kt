package com.uvp.sim.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 看守位自动归位判定 —— GB/T 28181 里 `ResetTime` 的语义("无云台操作等待该秒数后归位到
 * `PresetIndex`")。
 *
 * 判定是纯函数(不读时钟),所以这里直接喂 `idleSeconds`,不用真等 N 秒。
 * 节拍与活动信号在 [SimulatorEngine] 里,不在这层的测试范围。
 */
class HomePositionAutoReturnTest {

    private val target = PtzPose(pan = 45f, tilt = -10f, zoom = 2f)

    private fun configured(
        enabled: Boolean = true,
        presetIndex: Int? = 3,
        resetTime: Int? = 30,
        presets: Map<Int, PtzPose> = mapOf(3 to target),
    ) = DeviceControlModel(
        homePositionEnabled = enabled,
        homePositionPresetIndex = presetIndex,
        homePositionResetTime = resetTime,
        presets = presets,
    )

    @Test
    fun `空闲未到 ResetTime 不归位,到点才归位`() {
        val model = configured(resetTime = 30)
        assertNull(decideHomePositionReturn(model, idleSeconds = 0, alreadyReturned = false))
        assertNull(decideHomePositionReturn(model, idleSeconds = 29, alreadyReturned = false))

        val plan = decideHomePositionReturn(model, idleSeconds = 30, alreadyReturned = false)
        assertEquals(3, plan?.presetIndex)
        assertEquals(target, plan?.target)
    }

    @Test
    fun `已归过一次就不重复归位`() {
        // 闩的作用:归位动作会写回 lastCommand,没有这个闩的话倒计时会重新开始,
        // 于是每 ResetTime 秒再归一次位,日志无限刷。
        val model = configured(resetTime = 30)
        assertNull(decideHomePositionReturn(model, idleSeconds = 999, alreadyReturned = true))
    }

    @Test
    fun `未启用看守位时不归位`() {
        val model = configured(enabled = false, resetTime = 30)
        assertNull(decideHomePositionReturn(model, idleSeconds = 999, alreadyReturned = false))
    }

    @Test
    fun `ResetTime 未下发或为 0 时不归位`() {
        // 0 在这套语义里没有可用含义(平台只在 >= 10 时才允许启用),当成"不自动归位",
        // 免得演成"配置刚下发就瞬间弹回去"。
        assertNull(
            decideHomePositionReturn(
                configured(resetTime = null), idleSeconds = 999, alreadyReturned = false,
            )
        )
        assertNull(
            decideHomePositionReturn(
                configured(resetTime = 0), idleSeconds = 999, alreadyReturned = false,
            )
        )
    }

    @Test
    fun `平台从未下发过预置位编号时不归位`() {
        assertNull(
            decideHomePositionReturn(
                configured(presetIndex = null), idleSeconds = 999, alreadyReturned = false,
            )
        )
    }

    @Test
    fun `指向的预置位在本机不存在时不归位也不造假坐标`() {
        // 平台可能下发一个本机还没有的预置位号。真实设备不会为此凭空造坐标 ——
        // 这条口径与 PresetHandler「只落配置、不凭空造预置位」一致。
        val model = configured(presetIndex = 5, presets = mapOf(3 to target))
        assertNull(decideHomePositionReturn(model, idleSeconds = 999, alreadyReturned = false))
    }

    @Test
    fun `六轴任一速率非 0 都算云台正在动`() {
        // 活动信号的第二位:本机手操不写 lastCommand,只看时间戳的话
        // 人正按着方向键/光圈键时倒计时照走,松手前就被硬拉回看守位。
        assertFalse(DeviceControlModel().isCameraMoving)
        assertTrue(DeviceControlModel(panSpeed = 1f).isCameraMoving)
        assertTrue(DeviceControlModel(tiltSpeed = -1f).isCameraMoving)
        assertTrue(DeviceControlModel(zoomSpeed = 0.5f).isCameraMoving)
        assertTrue(DeviceControlModel(focusSpeed = 0.3f).isCameraMoving)
        assertTrue(DeviceControlModel(irisSpeed = -0.3f).isCameraMoving)
    }
}
