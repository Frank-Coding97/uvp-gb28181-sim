package com.uvp.sim.ui.simulate

import com.uvp.sim.ui.model.DeviceControlDto
import kotlin.test.Test
import kotlin.test.assertNotNull

/**
 * v1.3-C · CameraGlbView (iOS actual) smoke test.
 *
 * 覆盖 T-C1-4: 编译级 smoke (UIKitView 真跑要真机 XCUI, 主验收在 T-C1-6).
 *
 * 这里只验证:
 * - DTO 默认构造不崩
 * - Filament-backed DTO boundary remains constructible on the iOS target
 */
class CameraGlbViewIosSmokeTest {

    @Test
    fun default_dto_and_wiring_composes_types_correctly() {
        val dto = DeviceControlDto()
        // Native UIView creation requires an app host; this test covers the
        // Kotlin boundary while Xcode build covers the ObjC++ bridge itself.
        assertNotNull(dto)
    }
}
