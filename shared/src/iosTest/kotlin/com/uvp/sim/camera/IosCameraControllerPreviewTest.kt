package com.uvp.sim.camera

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * IosCameraController P1-1 preview-only 骨架的可测语义:
 *   - object 单例存在
 *   - session StateFlow 初始 null
 *   - encodingActive 初始 false
 *   - startPreview 在无相机 device 环境(Simulator)返回 no-op 但不 crash
 *   - stopPreview 幂等(未运行时 no-op 不 crash)
 *   - latestFramePixelBuffer 未有帧时返回 null
 *   - requestEncoding 在 P1-1 阶段抛 NotImplementedError(P2-1 会补真实现)
 *
 * 真机相关的 delegate publish latestFrame + startRunning 上画等,留 T-V 真机手工验(AC-1)。
 */
class IosCameraControllerPreviewTest {

    private val defaultConfig = CaptureConfig(
        widthPx = 1280,
        heightPx = 720,
        frameRate = 25,
        bitrateBps = 2_000_000,
        keyframeIntervalSeconds = 1,
        cameraFacing = CameraFacing.BACK,
    )

    @Test
    fun controller_is_object_singleton() {
        val a = IosCameraController
        val b = IosCameraController
        assertTrue(a === b, "IosCameraController must be a singleton object")
    }

    @Test
    fun session_state_flow_starts_null() {
        assertNull(IosCameraController.session.value)
    }

    @Test
    fun encoding_active_starts_false() {
        assertFalse(IosCameraController.encodingActive.value)
    }

    @Test
    fun latest_frame_is_null_before_any_delegate_sample() {
        assertNull(IosCameraController.latestFramePixelBuffer())
    }

    @Test
    fun stopPreview_when_not_running_is_noop() = runTest {
        // 不 crash 即通过 —— Simulator 上 startPreview 因无 camera 会 wireCaptureSession 返回 false,
        // captureSession 保持 null。此时 stopPreview 应幂等 no-op。
        IosCameraController.stopPreview()
        assertNull(IosCameraController.session.value)
    }

    @Test
    fun startPreview_on_simulator_no_camera_fails_gracefully_without_crash() = runTest {
        // Simulator 无 back camera,startPreview 内 wireCaptureSession 会 return false。
        // 不抛异常,session 保持 null。真机行为(session 非空)留 T-V AC-1 验。
        IosCameraController.startPreview(defaultConfig)
        // 不做 assertion on session.value,不同环境行为不同;确保没 crash 即可。
    }

    @Test
    fun requestEncoding_in_p1_1_throws_notImplementedError() {
        val ex = kotlin.runCatching { IosCameraController.requestEncoding() }.exceptionOrNull()
        assertNotNull(ex, "requestEncoding 应在 P1-1 骨架阶段抛 NotImplementedError")
        assertTrue(
            ex is NotImplementedError,
            "expected NotImplementedError, got ${ex::class.simpleName}"
        )
    }

    @Test
    fun requestKeyFrame_before_encoding_is_noop() {
        // 不抛异常即通过 —— controller 内部 guard encodingActive.value == false 时 return
        IosCameraController.requestKeyFrame()
    }
}
