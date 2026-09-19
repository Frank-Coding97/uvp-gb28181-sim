package com.uvp.sim.ui.simulate.ptz

import com.uvp.sim.ui.model.DeviceConfigRowDto
import com.uvp.sim.ui.model.LastDeviceCommandDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ImageTabContentStateTest {

    @Test
    fun keyFrameRequest_lights_for_both_protocol_spellings() {
        listOf("IFameCmd", "IFrameCmd").forEach { type ->
            val command = LastDeviceCommandDto(type, "Send", timestampMs = 1_000L)
            assertTrue(isKeyFrameRequestActive(command, nowMs = 1_500L), type)
        }
    }

    @Test
    fun keyFrameRequest_turns_off_after_feedback_window() {
        val command = LastDeviceCommandDto("IFrameCmd", "Send", timestampMs = 1_000L)

        assertFalse(isKeyFrameRequestActive(command, nowMs = 1_000L + KEY_FRAME_FEEDBACK_MS + 1L))
        assertFalse(isKeyFrameRequestActive(command.copy(type = "SnapShotCmd"), nowMs = 1_500L))
    }

    // ===== 设备配置族区块：常驻行 + 限额（硬高度预算的守卫） =====

    private fun pinnedRow(label: String = "视频参数属性") =
        DeviceConfigRowDto(label = label, value = "主 …", fromPlatform = false, alwaysVisible = true)

    private fun plainRow(label: String, fromPlatform: Boolean) =
        DeviceConfigRowDto(label = label, value = "…", fromPlatform = fromPlatform)

    private fun visibleRowCount(rows: List<DeviceConfigRowDto>) =
        selectConfigRows(rows).let { it.pinned.size + it.visible.size }

    @Test
    fun selectConfigRows_emptyInputYieldsEmptySelection() {
        val selection = selectConfigRows(emptyList())
        assertEquals(emptyList(), selection.pinned)
        assertEquals(emptyList(), selection.visible)
        assertEquals(0, selection.hiddenCount)
        assertEquals(0, selection.configuredCount)
    }

    /**
     * ⛔⛔ 平台**一项都没配**时，常驻行仍要显示 —— 它是"设备现在主/子码流各是什么"
     * 在设备屏幕上唯一的可见处，而这一项**永远有生效值**（平台写入 ?: 出厂派生）。
     * 跟着 `fromPlatform` 过滤掉，这一行就永远不出现。
     */
    @Test
    fun selectConfigRows_keepsPinnedRowWhenNothingWasConfigured() {
        val rows = listOf(pinnedRow(), plainRow("画面遮挡", false), plainRow("前端 OSD", false))
        val selection = selectConfigRows(rows)
        assertEquals(listOf("视频参数属性"), selection.pinned.map { it.label })
        assertEquals(emptyList(), selection.visible)
        assertEquals(0, selection.configuredCount, "常驻行不算进'平台已配 N 项'")
    }

    /** 名额内有几项就显示几项，**不能**报"另有 -N 项未展示"。 */
    @Test
    fun selectConfigRows_neverReportsNegativeRemainder() {
        val rows = listOf(pinnedRow(), plainRow("画面遮挡", true))
        val selection = selectConfigRows(rows)
        assertEquals(1, selection.visible.size)
        assertEquals(0, selection.hiddenCount, "配置数 <= 名额时余数必须是 0")
    }

    @Test
    fun selectConfigRows_capsVisibleRowsAndReportsRemainder() {
        val rows = listOf(
            pinnedRow(),
            plainRow("画面遮挡", true),
            plainRow("画面翻转", true),
            plainRow("前端 OSD", true),
        )
        val selection = selectConfigRows(rows)
        assertEquals(MAX_VISIBLE_CONFIG_ROWS, selection.visible.size)
        assertEquals(3, selection.configuredCount, "统计数照实报，只是不全都画出来")
        assertEquals(3 - MAX_VISIBLE_CONFIG_ROWS, selection.hiddenCount)
    }

    /**
     * ⛔⛔ 这条测的就是那个**硬高度预算**：`PtzHudPanel` 内容区定高 284dp 且不滚动，
     * 超出的行会被直接裁掉 —— 而裁掉的往往是最后一行（刚下发的那一项）。
     *
     * 常驻行**不受**名额限制，所以"常驻行 <= 1"是必须一起守的前提：
     * 一旦有人加的常驻行多于一个，等式右边就会破。
     */
    @Test
    fun selectConfigRows_totalRenderedRowsStayWithinHeightBudget() {
        val pinned = listOf(pinnedRow("视频参数属性"))
        // 最坏情况：平台把本族 8 个普通项**全配齐**。
        val many = List(8) { plainRow("项$it", fromPlatform = true) }
        assertEquals(
            2,
            visibleRowCount(pinned + many),
            "常驻行 + 普通行 必须 <= 2（284dp 内容区 / 约 46dp 一行）",
        )
    }
}
