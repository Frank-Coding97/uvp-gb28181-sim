package com.uvp.sim.ui.simulate.ptz

import com.uvp.sim.ui.model.LastDeviceCommandDto
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 「状态」页那盏「请求关键帧」一次性指示灯的判据。
 *
 * 2026-09-19 从「图像」页搬来本页（用户：「放到『状态』下面，做成四四方方的卡片」）——
 * 它语义上是"设备此刻在做什么"的一次性指示灯，与那四盏状态灯同类。
 * 判据函数 [isKeyFrameRequestActive] 留在**纯函数**里，就是为了这组用例不必等真实 2 秒。
 */
class StatusTabContentStateTest {

    /**
     * ⛔ 两种拼写都认是**刻意**的：本仓历史里 `IFameCmd`（少一个 r）与 `IFrameCmd` 都出现过，
     * 只认一种会让平台下发的另一半**静默不亮** —— 那种缺陷在设备屏幕上与"平台没发"无法区分。
     */
    @Test
    fun keyFrameRequest_lightsForBothProtocolSpellings() {
        listOf("IFameCmd", "IFrameCmd").forEach { type ->
            val command = LastDeviceCommandDto(type, "Send", timestampMs = 1_000L)
            assertTrue(isKeyFrameRequestActive(command, nowMs = 1_500L), type)
        }
    }

    @Test
    fun keyFrameRequest_turnsOffAfterFeedbackWindow() {
        val command = LastDeviceCommandDto("IFrameCmd", "Send", timestampMs = 1_000L)

        assertTrue(isKeyFrameRequestActive(command, nowMs = 1_000L + KEY_FRAME_FEEDBACK_MS))
        assertFalse(
            isKeyFrameRequestActive(command, nowMs = 1_000L + KEY_FRAME_FEEDBACK_MS + 1L),
            "过了保持窗口必须灭掉，否则指示灯变成常亮",
        )
        assertFalse(
            isKeyFrameRequestActive(command.copy(type = "SnapShotCmd"), nowMs = 1_500L),
            "别的命令不能点亮关键帧指示灯",
        )
    }

    @Test
    fun keyFrameRequest_isDarkWhenNothingWasEverReceived() {
        assertFalse(isKeyFrameRequestActive(null, nowMs = 1_000L))
    }

    /**
     * ⚠️ 时钟回拨（`now < 时间戳`）不许把灯点亮也不许抛异常 —— 这里断言的是
     * `in 0..KEY_FRAME_FEEDBACK_MS` 那个**下界**：负数差值会落到区间外。
     */
    @Test
    fun keyFrameRequest_staysDarkWhenClockWentBackwards() {
        val command = LastDeviceCommandDto("IFrameCmd", "Send", timestampMs = 10_000L)

        assertFalse(isKeyFrameRequestActive(command, nowMs = 9_000L))
    }
}
