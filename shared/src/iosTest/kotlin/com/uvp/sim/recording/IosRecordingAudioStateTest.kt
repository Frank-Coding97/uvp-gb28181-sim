package com.uvp.sim.recording

import com.uvp.sim.app.RecordingEncoderConfig
import com.uvp.sim.media.AudioCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * T-B3-1: audio track 状态字段 + 诊断计数(初值 + snapshot API 语义)。
 *
 * 只测状态字段初始值 + snapshot 读取语义,不涉及 AVAssetWriter 实际起录
 * (那属于 T-B3-4 的 startWriter 场景)。
 */
class IosRecordingAudioStateTest {

    @Test
    fun snapshot_default_state_shows_audio_disabled() {
        val svc = buildService(recAudioCodec = null)
        val snap = svc.snapshotAudioDiagnostics()
        assertNull(snap.activeAudioCodec, "默认无 audio codec")
        assertEquals(-1L, snap.audioBaselinePtsUs, "baseline 初始未设定")
        assertEquals(-1L, snap.lastAppendedAudioRelPtsUs, "lastAppended 初始未设定")
        assertEquals(0, snap.audioFramesSeen)
        assertEquals(0, snap.audioFramesAppended)
        assertEquals(0, snap.dropAudioPtsRegression)
        assertEquals(0, snap.audioAppendFailures)
        assertFalse(snap.audioInputPresent, "未起录时 audioInput 应为 null")
    }

    @Test
    fun snapshot_reflects_service_construction_defaults() {
        // AAC codec 场景:字段仍是零(codec snapshot 发生在 start() 里)。
        val svc = buildService(recAudioCodec = AudioCodec.AAC)
        val snap = svc.snapshotAudioDiagnostics()
        // start 之前 activeAudioCodec 应保持 null,不能提前触发。
        assertNull(snap.activeAudioCodec, "构造期不该 snapshot codec — 应发生在 start()")
        assertFalse(snap.audioInputPresent)
    }

    private fun buildService(recAudioCodec: AudioCodec?): IosRecordingService {
        val enc = RecordingEncoderConfig(
            widthPx = 1280,
            heightPx = 720,
            frameRate = 25,
            bitrateBps = 2_000_000,
            keyframeIntervalSeconds = 1,
            audioCodec = recAudioCodec,
        )
        return IosRecordingService(
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default),
            deviceIdSupplier = { "test-device" },
            encoderConfigSupplier = { enc },
            osdConfigSupplier = { kotlinx.coroutines.flow.MutableStateFlow(com.uvp.sim.config.OsdConfig()) },
            profileSupplier = { com.uvp.sim.config.RecordingProfile() },
        )
    }
}
