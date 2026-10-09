package com.uvp.sim.ui.simulate

import com.uvp.sim.domain.LastDeviceCommand
import com.uvp.sim.domain.deriveCommandCategory
import com.uvp.sim.ui.model.mapper.toDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HudTabTest {
    @Test
    fun commandsReachThePageContainingTheirFeedback() {
        val cases = listOf(
            Triple("PTZCmd", "A50F0102320000DE", HudTab.Ptz),
            Triple("PTZPreciseCtrl", "45,30,1", HudTab.Ptz),
            Triple("PTZCmd", "SetPreset#9", HudTab.Position),
            Triple("PTZCmd", "巡航 #2 启动", HudTab.Position),
            Triple("PTZCmd", "扫描 #0 启动", HudTab.Position),
            Triple("HomePosition", "Recall#1", HudTab.Position),
            Triple("PTZCmd", "雨刷 ON", HudTab.Device),
            Triple("RecordCmd", "Record", HudTab.Device),
            Triple("DeviceUpgrade", "1.0.4", HudTab.Device),
            Triple("FormatSDCard", "card 1", HudTab.Device),
            Triple("IFameCmd", "Send", HudTab.Image),
            Triple("IFrameCmd", "Send", HudTab.Image),
            Triple("SnapShotCmd", "1", HudTab.Image),
            Triple("DeviceConfig", "FrameMirror", HudTab.Image),
        )
        cases.forEach { (type, raw, expected) ->
            val category = deriveCommandCategory(LastDeviceCommand(type, raw, 1L))?.toDto()
            assertEquals(expected, HudTab.fromCategory(category), "$type / $raw")
        }
        assertNull(HudTab.fromCategory(null))
    }
}
