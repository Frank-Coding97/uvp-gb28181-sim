package com.uvp.sim.app

import com.uvp.sim.camera.AudioCaptureConfig
import com.uvp.sim.camera.CaptureConfig
import com.uvp.sim.config.DeviceConfig
import com.uvp.sim.config.GbVersion
import com.uvp.sim.config.ServerConfig
import com.uvp.sim.config.SimConfig
import com.uvp.sim.config.VideoProfile
import com.uvp.sim.config.VideoResolution
import com.uvp.sim.media.AudioCodec
import com.uvp.sim.media.VideoCodec
import com.uvp.sim.network.TransportType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Wave 4 PR-PLATFORM-RUNTIME — [PlatformRuntime] 装配契约测试。
 *
 * 验证目标(契约级,不验证 Android streamer 内部细节):
 *   1. AppEngine 在 ensureMediaBuilt 时调用 runtime.buildCameraCapture / buildAudioCapture /
 *      buildRecordingService 各一次,参数派生自当前 SimConfig.video
 *   2. updateConfig 中 video 子配置变化 → runtime.applyVideoConfig 触发一次,
 *      captureConfig / audioConfig 跟随新 SimConfig
 *   3. updateConfig 中 video 子配置不变 → applyVideoConfig 不触发(避免无谓重建)
 *   4. setConfig 同款语义(冷启动 loadOnce 后的 in-memory 注入路径)
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlatformRuntimeTest {

    private fun config(
        widthPx: Int = 1280,
        heightPx: Int = 720,
        frameRate: Int = 25,
        bitrateKbps: Int = 2000,
        videoCodec: VideoCodec = VideoCodec.H264,
        audioCodec: AudioCodec = AudioCodec.G711A,
    ) = SimConfig(
        gbVersion = GbVersion.V2022,
        server = ServerConfig(ip = "1.2.3.4", port = 5060, serverId = "s", domain = "d"),
        device = DeviceConfig(
            deviceId = "did", videoChannelId = "vc", alarmChannelId = "ac",
            username = "did", password = "p",
        ),
        video = VideoProfile(
            resolution = if (widthPx == 1280) VideoResolution.HD_720P else VideoResolution.FHD_1080P,
            frameRate = frameRate,
            bitrateKbps = bitrateKbps,
            videoCodec = videoCodec,
            audioCodec = audioCodec,
        ),
        transport = TransportType.UDP,
        keepaliveIntervalSeconds = 60,
    )

    @Test
    fun ensureMediaBuilt_calls_runtime_build_methods_once() = runTest {
        val runtime = FakePlatformRuntime()
        val app = AppEngine(
            resources = FakePlatformResources(),
            runtime = runtime,
            initialConfig = config(),
            parentScope = this,
        )
        runCurrent()

        app.ensureMediaBuilt()
        runCurrent()

        assertEquals(1, runtime.builtCameras.size, "buildCameraCapture should be invoked once")
        assertEquals(1, runtime.builtAudios.size, "buildAudioCapture should be invoked once")
        assertEquals(1, runtime.builtRecordings.size, "buildRecordingService should be invoked once")

        val cam = runtime.builtCameras[0]
        assertEquals(1280, cam.widthPx)
        assertEquals(720, cam.heightPx)
        assertEquals(25, cam.frameRate)
        assertEquals(2_000_000, cam.bitrateBps)
        assertEquals(VideoCodec.H264, cam.videoCodec)

        val au = runtime.builtAudios[0]
        assertEquals(AudioCodec.G711A, au.codec)
    }

    @Test
    fun ensureMediaBuilt_is_idempotent() = runTest {
        val runtime = FakePlatformRuntime()
        val app = AppEngine(
            resources = FakePlatformResources(),
            runtime = runtime,
            initialConfig = config(),
            parentScope = this,
        )
        runCurrent()

        app.ensureMediaBuilt()
        app.ensureMediaBuilt()
        app.ensureMediaBuilt()
        runCurrent()

        // 单例语义:后续调用复用 cached camera/audio/recording
        assertEquals(1, runtime.builtCameras.size)
        assertEquals(1, runtime.builtAudios.size)
        assertEquals(1, runtime.builtRecordings.size)
    }

    @Test
    fun setConfig_video_change_triggers_runtime_applyVideoConfig() = runTest {
        val runtime = FakePlatformRuntime()
        val app = AppEngine(
            resources = FakePlatformResources(),
            runtime = runtime,
            initialConfig = config(widthPx = 1280),
            parentScope = this,
        )
        runCurrent()

        val newCfg = config(widthPx = 1920) // 1280 → 1920(FHD_1080P)
        app.setConfig(newCfg)
        runCurrent()

        assertEquals(1, runtime.appliedVideoConfigs.size, "applyVideoConfig should fire on video change")
        val (cam, au) = runtime.appliedVideoConfigs[0]
        assertEquals(1920, cam.widthPx)
        assertEquals(1080, cam.heightPx)
    }

    @Test
    fun setConfig_video_unchanged_does_not_trigger_applyVideoConfig() = runTest {
        val runtime = FakePlatformRuntime()
        val app = AppEngine(
            resources = FakePlatformResources(),
            runtime = runtime,
            initialConfig = config(),
            parentScope = this,
        )
        runCurrent()

        // 改 server / device 字段,video 不变
        val newCfg = app.config.value.copy(
            device = app.config.value.device.copy(deviceId = "different")
        )
        app.setConfig(newCfg)
        runCurrent()

        assertTrue(
            runtime.appliedVideoConfigs.isEmpty(),
            "applyVideoConfig should not fire when video config is unchanged",
        )
    }

    @Test
    fun updateConfig_video_change_triggers_applyVideoConfig() = runTest {
        val runtime = FakePlatformRuntime()
        val app = AppEngine(
            resources = FakePlatformResources(),
            runtime = runtime,
            initialConfig = config(frameRate = 25),
            parentScope = this,
        )
        runCurrent()

        val newCfg = config(frameRate = 30)
        app.updateConfig(newCfg)
        runCurrent()

        assertEquals(1, runtime.appliedVideoConfigs.size)
        assertEquals(30, runtime.appliedVideoConfigs[0].first.frameRate)
    }

    @Test
    fun currentRecordingService_returns_built_instance_after_ensureMediaBuilt() = runTest {
        val runtime = FakePlatformRuntime()
        val app = AppEngine(
            resources = FakePlatformResources(),
            runtime = runtime,
            initialConfig = config(),
            parentScope = this,
        )
        runCurrent()

        assertEquals(null, app.currentRecordingService(), "no recording service before ensureMediaBuilt")
        app.ensureMediaBuilt()
        runCurrent()

        val svc = app.currentRecordingService()
        assertNotNull(svc, "recordingService should be available after ensureMediaBuilt")
    }
}
