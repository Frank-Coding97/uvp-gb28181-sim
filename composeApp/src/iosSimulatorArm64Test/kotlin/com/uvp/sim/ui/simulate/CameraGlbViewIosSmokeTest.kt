package com.uvp.sim.ui.simulate

import com.uvp.sim.ui.model.DeviceControlDto
import com.uvp.sim.ui.simulate.scenekit.SceneKitCameraScene
import com.uvp.sim.ui.simulate.scenekit.SceneKitEffectDispatcher
import kotlin.test.Test
import kotlin.test.assertNotNull

/**
 * v1.3-C · CameraGlbView (iOS actual) smoke test.
 *
 * 覆盖 T-C1-4: 编译级 smoke (UIKitView 真跑要真机 XCUI, 主验收在 T-C1-6).
 *
 * 这里只验证:
 * - DTO 默认构造不崩
 * - Scene + Dispatcher 组合能挂起来(underlying wiring 没有类型错)
 */
class CameraGlbViewIosSmokeTest {

    @Test
    fun default_dto_and_wiring_composes_types_correctly() {
        val dto = DeviceControlDto()
        val scene = SceneKitCameraScene()
        val dispatcher = SceneKitEffectDispatcher(scene)
        // 类型编译过关即 pass, 不真挂 UIKitView (要 test host xctest runner).
        assertNotNull(dto)
        assertNotNull(scene)
        assertNotNull(dispatcher)
    }
}
