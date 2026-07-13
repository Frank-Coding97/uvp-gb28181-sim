package com.uvp.sim.camera

import com.uvp.sim.app.PlatformRuntimeIos
import com.uvp.sim.camera.AudioCaptureConfig
import com.uvp.sim.media.AudioCodec
import com.uvp.sim.media.VideoCodec
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * v1.3-A dual-camera-channel iOS 侧对齐:
 *   - [IosCameraController.switchFacing] / [IosCameraController.applyRuntimeConfig]
 *     preview 未启时行为(fire-and-forget,仅更新 state)
 *   - [CameraCapture.setFacing] 真的转发到 controller,不再是纯 warning
 *   - [CameraCapture.applyConfig] 同步刷新 facade + 通过 controller.applyRuntimeConfig 传导
 *   - [PlatformRuntimeIos.applyVideoConfig] 同时 propagate 到 camera + audio 两条 facade
 *   - [AudioCapture.applyConfig] 用新 config 替换 streamer,同时 stopSync 旧 streamer 避免泄漏
 *
 * 真机相关(session 上真的换 AVCaptureDeviceInput / VT session 重建)留人工验证,Simulator 无摄像头。
 */
class IosCameraFacingAndApplyConfigTest {

    @BeforeTest
    fun setup() = runTest {
        // controller 是全局单例,先 stopPreview + reset,让每个 test 从干净 state 起手
        IosCameraController.stopPreview()
        IosCameraController.resetPendingStateForTest()
    }

    @AfterTest
    fun teardown() = runTest {
        IosCameraController.stopPreview()
        IosCameraController.resetPendingStateForTest()
    }

    // ---- switchFacing (controller-level) ----

    @Test
    fun switchFacing_updates_current_facing_synchronously() {
        assertEquals(CameraFacing.BACK, IosCameraController.currentFacingForTest())
        IosCameraController.switchFacing(CameraFacing.FRONT)
        assertEquals(CameraFacing.FRONT, IosCameraController.currentFacingForTest())
    }

    @Test
    fun switchFacing_same_value_is_noop() {
        IosCameraController.switchFacing(CameraFacing.BACK)
        assertEquals(CameraFacing.BACK, IosCameraController.currentFacingForTest())
        // 二次同值不改 state 也不 crash
        IosCameraController.switchFacing(CameraFacing.BACK)
        assertEquals(CameraFacing.BACK, IosCameraController.currentFacingForTest())
    }

    @Test
    fun switchFacing_preview_not_running_only_stashes_target() {
        // preview 未启:switchFacing 只更新 currentFacing,不 dispatch 到 sessionQueue
        assertNull(IosCameraController.session.value)
        IosCameraController.switchFacing(CameraFacing.FRONT)
        assertEquals(CameraFacing.FRONT, IosCameraController.currentFacingForTest())
        // captureSession 保持 null,session queue 已 short-circuit 不 crash
        assertNull(IosCameraController.session.value)
    }

    // ---- applyRuntimeConfig (controller-level) ----

    @Test
    fun applyRuntimeConfig_updates_current_config_synchronously() {
        val target = CaptureConfig(
            widthPx = 1920,
            heightPx = 1080,
            frameRate = 30,
            bitrateBps = 4_000_000,
            keyframeIntervalSeconds = 2,
            cameraFacing = CameraFacing.FRONT,
            videoCodec = VideoCodec.H265,
        )
        IosCameraController.applyRuntimeConfig(target)
        assertEquals(target, IosCameraController.currentConfigForTest())
        assertEquals(CameraFacing.FRONT, IosCameraController.currentFacingForTest())
    }

    @Test
    fun applyRuntimeConfig_same_value_is_noop() {
        val cfg = CaptureConfig()
        IosCameraController.applyRuntimeConfig(cfg)
        val snapshot = IosCameraController.currentConfigForTest()
        // 同值再来一次不改引用 / 不 crash
        IosCameraController.applyRuntimeConfig(cfg)
        assertEquals(snapshot, IosCameraController.currentConfigForTest())
    }

    // ---- CameraCapture facade wiring ----

    @Test
    fun cameraCapture_setFacing_forwards_to_controller() {
        val cam = CameraCapture(CaptureConfig(cameraFacing = CameraFacing.BACK))
        cam.setFacing(CameraFacing.FRONT)
        assertEquals(CameraFacing.FRONT, cam.pendingFacingForTest())
        assertEquals(CameraFacing.FRONT, IosCameraController.currentFacingForTest())
        // facade 的 config snapshot 也同步换朝向,后续 start() 起 VT session 时用新值
        assertEquals(CameraFacing.FRONT, cam.configuredConfigForTest().cameraFacing)
    }

    @Test
    fun cameraCapture_applyConfig_propagates_to_controller() {
        val cam = CameraCapture(CaptureConfig())
        val updated = CaptureConfig(
            widthPx = 640,
            heightPx = 480,
            frameRate = 15,
            bitrateBps = 800_000,
            cameraFacing = CameraFacing.FRONT,
            videoCodec = VideoCodec.H265,
        )
        cam.applyConfig(updated)

        assertEquals(updated, cam.configuredConfigForTest())
        assertEquals(updated, IosCameraController.currentConfigForTest())
        assertEquals(CameraFacing.FRONT, IosCameraController.currentFacingForTest())
    }

    // ---- AudioCapture facade wiring ----

    @Test
    fun audioCapture_applyConfig_swaps_streamer_and_stops_previous() {
        val audio = AudioCapture(AudioCaptureConfig(codec = AudioCodec.G711A))
        val streamerBefore = audio.streamerForTest()
        val updated = AudioCaptureConfig(codec = AudioCodec.AAC, sampleRateHz = 16_000)

        audio.applyConfig(updated)

        assertEquals(updated, audio.configuredConfigForTest())
        // applyConfig 必须真的替换实例,而不是原地 mutate 单例
        assertNotSame(
            streamerBefore, audio.streamerForTest(),
            "applyConfig must swap the underlying streamer so the new codec/sampleRate is honored"
        )
        assertEquals(AudioCodec.AAC, audio.streamerForTest().configuredCodec())
    }

    // ---- PlatformRuntimeIos.applyVideoConfig integration ----

    @Test
    fun platform_runtime_apply_video_config_propagates_to_camera_and_controller() {
        val runtime = PlatformRuntimeIos()
        val camera = runtime.buildCameraCapture(CaptureConfig())
        val audio = runtime.buildAudioCapture(AudioCaptureConfig())

        val updatedCamera = CaptureConfig(
            widthPx = 1920,
            heightPx = 1080,
            frameRate = 30,
            bitrateBps = 4_000_000,
            keyframeIntervalSeconds = 2,
            cameraFacing = CameraFacing.FRONT,
            videoCodec = VideoCodec.H265,
        )
        val updatedAudio = AudioCaptureConfig(codec = AudioCodec.AAC, sampleRateHz = 16_000)

        runtime.applyVideoConfig(updatedCamera, updatedAudio)

        assertEquals(updatedCamera, camera.configuredConfigForTest())
        assertEquals(updatedAudio, audio.configuredConfigForTest())
        assertSame(
            camera, runtime.buildCameraCapture(CaptureConfig()),
            "buildCameraCapture must keep returning the same facade after applyVideoConfig"
        )
        // controller 侧真的看到了新 facing / 新 config
        assertEquals(CameraFacing.FRONT, IosCameraController.currentFacingForTest())
        assertEquals(updatedCamera, IosCameraController.currentConfigForTest())
    }

    @Test
    fun platform_runtime_release_clears_facade_refs() = runTest {
        val runtime = PlatformRuntimeIos()
        runtime.buildCameraCapture(CaptureConfig())
        runtime.buildAudioCapture(AudioCaptureConfig())

        runtime.release()

        // release 后 apply 不再 propagate(引用被清)—— controller state 保持上次 apply 的值
        val before = IosCameraController.currentConfigForTest()
        runtime.applyVideoConfig(
            CaptureConfig(widthPx = 640, heightPx = 480),
            AudioCaptureConfig(codec = AudioCodec.G711U),
        )
        assertEquals(before, IosCameraController.currentConfigForTest())

        // 下一次 buildCameraCapture 应能拿到全新的 facade
        val fresh = runtime.buildCameraCapture(CaptureConfig())
        assertTrue(
            fresh.configuredConfigForTest() == CaptureConfig(),
            "release 后 build 出的 CameraCapture 必须是干净的默认 config"
        )
    }
}
