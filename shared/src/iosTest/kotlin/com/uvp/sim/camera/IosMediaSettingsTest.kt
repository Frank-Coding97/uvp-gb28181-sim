package com.uvp.sim.camera

import com.uvp.sim.app.PlatformRuntimeIos
import com.uvp.sim.media.AudioCodec
import com.uvp.sim.media.VideoCodec
import kotlin.test.Test
import kotlin.test.assertEquals

class IosMediaSettingsTest {

    @Test
    fun encoding_tuning_uses_bitrate_frame_rate_and_gop_from_config() {
        val tuning = encodingTuning(
            CaptureConfig(
                frameRate = 20,
                bitrateBps = 1_200_000,
                keyframeIntervalSeconds = 2,
            )
        )

        assertEquals(1_200_000, tuning.averageBitRateBps)
        assertEquals(20, tuning.expectedFrameRate)
        assertEquals(40, tuning.maxKeyFrameInterval)
    }

    @Test
    fun aac_target_sample_rate_uses_saved_setting() {
        assertEquals(
            8_000.0,
            targetAudioSampleRate(AudioCaptureConfig(codec = AudioCodec.AAC, sampleRateHz = 8_000)),
        )
        assertEquals(
            16_000.0,
            targetAudioSampleRate(AudioCaptureConfig(codec = AudioCodec.AAC, sampleRateHz = 16_000)),
        )
        assertEquals(
            8_000.0,
            targetAudioSampleRate(AudioCaptureConfig(codec = AudioCodec.G711A, sampleRateHz = 16_000)),
        )
    }

    @Test
    fun platform_runtime_applies_new_camera_and_audio_settings_to_existing_facades() {
        val runtime = PlatformRuntimeIos()
        val camera = runtime.buildCameraCapture(CaptureConfig())
        val audio = runtime.buildAudioCapture(AudioCaptureConfig())
        val updatedCamera = CaptureConfig(
            widthPx = 1920,
            heightPx = 1080,
            frameRate = 30,
            bitrateBps = 4_000_000,
            keyframeIntervalSeconds = 2,
            videoCodec = VideoCodec.H265,
        )
        val updatedAudio = AudioCaptureConfig(
            codec = AudioCodec.AAC,
            sampleRateHz = 16_000,
        )

        runtime.applyVideoConfig(updatedCamera, updatedAudio)

        assertEquals(updatedCamera, camera.configuredConfigForTest())
        assertEquals(updatedAudio, audio.configuredConfigForTest())
    }
}
