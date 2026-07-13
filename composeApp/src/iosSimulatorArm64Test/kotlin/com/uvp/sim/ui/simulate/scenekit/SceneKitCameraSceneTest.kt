package com.uvp.sim.ui.simulate.scenekit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * v1.3-C · SceneKitCameraScene 单测.
 *
 * 覆盖 tasks:
 * - T-C1-2 loadFromBundle + fallback
 * - T-C1-3 bindPivots + setupLightsAndCamera
 * - T-C1-5 detach 释放路径
 * - T-C4-2 applyPerformanceLevel L0/L1
 * - T-C4-3 pause/resume
 *
 * 注:主 bundle 内此测试进程中不存在 `security_camera.scn`
 * (只有 iOS app bundle 才有),loadFromBundle 全程走 fallback 分支.
 */
class SceneKitCameraSceneTest {

    // --- T-C1-2 ---

    @Test
    fun loadFromBundle_returns_true_and_populates_root() {
        val scene = SceneKitCameraScene()
        val loaded = scene.loadFromBundle()
        assertTrue(loaded, "loadFromBundle should return true even in fallback mode")
        assertNotNull(scene.rootNode, "rootNode not null after load")
    }

    @Test
    fun loadFromBundle_uses_fallback_when_scn_missing() {
        val scene = SceneKitCameraScene()
        scene.loadFromBundle()
        // 测试 bundle 内没 .scn, 必走 fallback
        assertTrue(scene.isFallback, "should fallback to procedural scene when .scn missing")
    }

    // --- T-C1-3 ---

    @Test
    fun bindPivots_finds_three_pivots_in_fallback_scene() {
        val scene = SceneKitCameraScene()
        scene.loadFromBundle()
        val bound = scene.bindPivots()
        assertTrue(bound, "bindPivots should locate all three pivots in fallback scene")
        assertNotNull(scene.panPivot, "panPivot not null")
        assertNotNull(scene.tiltPivot, "tiltPivot not null")
        assertNotNull(scene.zoomPivot, "zoomPivot not null")
    }

    @Test
    fun bindPivots_returns_false_before_load() {
        val scene = SceneKitCameraScene()
        val bound = scene.bindPivots()
        assertFalse(bound, "bindPivots should return false before loadFromBundle")
    }

    @Test
    fun setupLightsAndCamera_attaches_lights_and_camera() {
        val scene = SceneKitCameraScene()
        scene.loadFromBundle()
        scene.setupLightsAndCamera()
        assertNotNull(scene.keyLight, "key light attached")
        assertNotNull(scene.ambientLight, "ambient light attached")
        assertNotNull(scene.mainCamera, "camera attached")
    }

    // --- T-C1-5 ---

    @Test
    fun detach_clears_all_references() {
        val scene = SceneKitCameraScene()
        scene.loadFromBundle()
        scene.bindPivots()
        scene.setupLightsAndCamera()
        scene.detach()
        assertNull(scene.rootNode, "rootNode cleared after detach")
        assertNull(scene.panPivot, "panPivot cleared")
        assertNull(scene.tiltPivot, "tiltPivot cleared")
        assertNull(scene.zoomPivot, "zoomPivot cleared")
        assertNull(scene.keyLight, "keyLight cleared")
        assertNull(scene.mainCamera, "mainCamera cleared")
        assertNull(scene.scnView, "scnView cleared")
    }

    @Test
    fun attach_detach_loop_survives_three_cycles() {
        // 不真用 SCNView(要 UIKit runtime + xctest host), 只跑 load/detach 循环
        repeat(3) {
            val scene = SceneKitCameraScene()
            assertTrue(scene.loadFromBundle())
            assertTrue(scene.bindPivots())
            scene.setupLightsAndCamera()
            scene.detach()
        }
    }

    // --- T-C4-2 ---

    @Test
    fun applyPerformanceLevel_clamps_to_L0_L1() {
        val scene = SceneKitCameraScene()
        scene.applyPerformanceLevel(0)
        assertEquals(0, scene.performanceLevel)
        scene.applyPerformanceLevel(1)
        assertEquals(1, scene.performanceLevel)
        scene.applyPerformanceLevel(5) // out of range → clamp to 1
        assertEquals(1, scene.performanceLevel, "level > 1 clamps to 1")
        scene.applyPerformanceLevel(-1) // 负数 → clamp to 0
        assertEquals(0, scene.performanceLevel, "negative clamps to 0")
    }

    // --- T-C4-3 ---

    @Test
    fun pause_resume_noop_when_scnview_not_attached() {
        val scene = SceneKitCameraScene()
        scene.loadFromBundle()
        // 无 view attach 时 pause/resume 不应崩
        scene.pause()
        scene.resume()
    }
}
