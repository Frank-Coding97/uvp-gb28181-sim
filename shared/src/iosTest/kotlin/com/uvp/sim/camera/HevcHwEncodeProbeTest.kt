package com.uvp.sim.camera

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T-B1-6:HEVC 硬编能力探测的缓存 + 异常兜底行为。
 *
 * 由于 IosCameraController 是单例,test 用 `overrideHevcHwEncodeSupportedForTest` 显式
 * set / clear 缓存值。真实探测走 HevcHwProbe.probe(),iosSimulator arm64 上 HEVC 支持
 * 因模拟器版本而异 —— 不 assert 具体值,只验证缓存行为。
 */
class HevcHwEncodeProbeTest {

    @AfterTest
    fun cleanup() {
        IosCameraController.overrideHevcHwEncodeSupportedForTest(null)
    }

    @Test
    fun probe_cached_value_true_returns_true() {
        IosCameraController.overrideHevcHwEncodeSupportedForTest(true)
        assertTrue(IosCameraController.hevcHwEncodeSupported)
        // 二次读走缓存,值不变
        assertTrue(IosCameraController.hevcHwEncodeSupported)
    }

    @Test
    fun probe_cached_value_false_returns_false() {
        IosCameraController.overrideHevcHwEncodeSupportedForTest(false)
        assertEquals(false, IosCameraController.hevcHwEncodeSupported)
        assertEquals(false, IosCameraController.hevcHwEncodeSupported)
    }

    @Test
    fun probe_real_call_does_not_crash_and_returns_boolean() {
        // 清缓存 → 真跑一次 probe,不 assert 具体值(依赖 simulator 环境)
        IosCameraController.overrideHevcHwEncodeSupportedForTest(null)
        val result = IosCameraController.hevcHwEncodeSupported
        // 单纯 assert 类型 Boolean(编译期已保证);跑到这里说明未 crash
        assertTrue(result == true || result == false)
    }
}
