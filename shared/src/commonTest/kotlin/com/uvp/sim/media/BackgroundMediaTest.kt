package com.uvp.sim.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BackgroundMediaControllerTest {

    @Test
    fun registeredWithoutActiveInviteNeverEntersPlaceholderMode() {
        val controller = BackgroundMediaController()

        controller.enterBackground(hasActiveInvite = false)
        assertEquals(BackgroundMediaMode.Inactive, controller.mode.value)

        controller.enterForeground(hasActiveInvite = false)
        assertEquals(BackgroundMediaMode.Inactive, controller.mode.value)
    }

    @Test
    fun activeInviteUsesPlaceholderInBackgroundAndWaitsForKeyFrameAfterReturn() {
        val controller = BackgroundMediaController()

        controller.onActiveInviteChanged(active = true)
        assertEquals(BackgroundMediaMode.Live, controller.mode.value)

        controller.enterBackground(hasActiveInvite = true)
        assertEquals(BackgroundMediaMode.Placeholder, controller.mode.value)

        // A stray encoder callback while still backgrounded must not re-enable the camera image.
        controller.onRealVideoKeyFrame()
        assertEquals(BackgroundMediaMode.Placeholder, controller.mode.value)

        controller.enterForeground(hasActiveInvite = true)
        assertEquals(BackgroundMediaMode.AwaitingKeyFrame, controller.mode.value)

        controller.onRealVideoKeyFrame()
        assertEquals(BackgroundMediaMode.Live, controller.mode.value)
    }

    @Test
    fun inviteThatStartsWhileBackgroundedStaysOnPlaceholderUntilForegroundKeyFrame() {
        val controller = BackgroundMediaController()

        controller.enterBackground(hasActiveInvite = false)
        controller.onActiveInviteChanged(active = true)
        assertEquals(BackgroundMediaMode.Placeholder, controller.mode.value)

        controller.enterForeground(hasActiveInvite = true)
        assertEquals(BackgroundMediaMode.AwaitingKeyFrame, controller.mode.value)
        controller.onRealVideoKeyFrame()
        assertEquals(BackgroundMediaMode.Live, controller.mode.value)
    }

    @Test
    fun endingTheInviteStopsPlaceholderOrRecoveryOutputImmediately() {
        val controller = BackgroundMediaController()

        controller.onActiveInviteChanged(active = true)
        controller.enterBackground(hasActiveInvite = true)
        assertEquals(BackgroundMediaMode.Placeholder, controller.mode.value)

        controller.onActiveInviteChanged(active = false)
        assertEquals(BackgroundMediaMode.Inactive, controller.mode.value)

        controller.enterForeground(hasActiveInvite = false)
        controller.onRealVideoKeyFrame()
        assertEquals(BackgroundMediaMode.Inactive, controller.mode.value)
    }
}

class BackgroundMediaFramesTest {

    @Test
    fun placeholderMessageUsesDeviceVideoWording() {
        assertEquals("视频画面暂不可用", BACKGROUND_VIDEO_MESSAGE)
        assertFalse(BACKGROUND_VIDEO_MESSAGE.contains("主播"))
    }

    @Test
    fun h264PlaceholderIsAnIndependentKeyFrameAtTheRequestedTimestamp() {
        val timestampUs = 12_345_678L
        val first = backgroundVideoFrame(VideoCodec.H264, timestampUs)

        assertEquals(VideoCodec.H264, first.codec)
        assertEquals(timestampUs, first.timestampUs)
        assertTrue(first.isKeyFrame)
        assertTrue(first.nalTypes().contains(NalType.SPS))
        assertTrue(first.nalTypes().contains(NalType.PPS))
        assertTrue(first.nalTypes().contains(NalType.IDR))
        assertTrue(hasAnyKeyNal(first.nalUnits, first.codec))
        assertFalse(first.nalUnits.any { it.size >= 4 && it[0] == 0.toByte() && it[1] == 0.toByte() })

        first.nalUnits.first()[0] = 0
        val next = backgroundVideoFrame(VideoCodec.H264, timestampUs + 40_000)
        assertEquals(NalType.SPS, next.codec.nalType(next.nalUnits.first()[0]))
    }

    @Test
    fun h265PlaceholderCarriesVpsSpsPpsAndIrap() {
        val frame = backgroundVideoFrame(VideoCodec.H265, timestampUs = 200_000L)
        val types = frame.nalTypes()

        assertEquals(VideoCodec.H265, frame.codec)
        assertTrue(frame.isKeyFrame)
        assertTrue(types.contains(H265NalType.VPS_NUT))
        assertTrue(types.contains(H265NalType.SPS_NUT))
        assertTrue(types.contains(H265NalType.PPS_NUT))
        assertTrue(types.any { frame.codec.isKeyNal(it) })
        assertTrue(hasAnyKeyNal(frame.nalUnits, frame.codec))
    }

    @Test
    fun silentAudioMatchesTheNegotiatedCodecAndPreservesTimestamp() {
        val timestampUs = 9_876_543L
        val alaw = backgroundAudioFrame(AudioCodec.G711A, timestampUs)
        val ulaw = backgroundAudioFrame(AudioCodec.G711U, timestampUs)
        val aac = backgroundAudioFrame(AudioCodec.AAC, timestampUs)

        assertEquals(timestampUs, alaw.timestampUs)
        assertEquals(AudioCodec.G711A, alaw.codec)
        assertEquals(160, alaw.payload.size)
        assertTrue(alaw.payload.all { it == 0xD5.toByte() })

        assertEquals(AudioCodec.G711U, ulaw.codec)
        assertEquals(160, ulaw.payload.size)
        assertTrue(ulaw.payload.all { it == 0xFF.toByte() })

        assertEquals(AudioCodec.AAC, aac.codec)
        assertTrue(aac.payload.contentEquals(byteArrayOf(0x01, 0x18, 0x20, 0x07)))
    }

    @Test
    fun audioCadenceFollowsCodecSampleDuration() {
        assertEquals(20_000L, backgroundAudioFrameDurationUs(AudioCodec.G711A))
        assertEquals(20_000L, backgroundAudioFrameDurationUs(AudioCodec.G711U))
        assertEquals(64_000L, backgroundAudioFrameDurationUs(AudioCodec.AAC, sampleRateHz = 16_000))
        assertEquals(128_000L, backgroundAudioFrameDurationUs(AudioCodec.AAC, sampleRateHz = 8_000))
    }
}
