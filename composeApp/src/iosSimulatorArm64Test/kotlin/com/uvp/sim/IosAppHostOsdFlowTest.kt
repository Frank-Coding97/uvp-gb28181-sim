package com.uvp.sim

import com.uvp.sim.config.OsdPosition
import com.uvp.sim.config.OsdSize
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class IosAppHostOsdFlowTest {

    @Test
    fun derived_osd_replaces_only_runtime_channel_name() {
        val source = IosAppHost.defaultConfig().osd.copy(
            channelName = IosAppHost.defaultConfig().osd.channelName.copy(
                enabled = false,
                text = "persisted name",
                position = OsdPosition.CENTER,
                size = OsdSize.LARGE,
                fillColor = "#123456",
                outlineColor = "#654321",
            ),
            watermark = IosAppHost.defaultConfig().osd.watermark.copy(
                enabled = true,
                text = "UVP TEST",
            ),
        )
        val config = IosAppHost.defaultConfig().copy(osd = source)

        val result = IosAppHost.deriveOsdConfig(config, "Camera A")

        assertEquals(source.copy(channelName = source.channelName.copy(text = "Camera A")), result)
    }

    @Test
    fun same_state_flow_emits_channel_and_config_hot_updates() = runTest {
        val initial = IosAppHost.defaultConfig()
        val config = MutableStateFlow(initial)
        val channelName = MutableStateFlow("Camera A")
        val flow = IosAppHost.osdConfigStateFlow(config, channelName, backgroundScope)
        runCurrent()

        channelName.value = "Camera B"
        runCurrent()
        assertEquals("Camera B", flow.value.channelName.text)

        val updatedOsd = initial.osd.copy(
            watermark = initial.osd.watermark.copy(enabled = true, text = "UVP TEST"),
        )
        config.value = initial.copy(osd = updatedOsd)
        runCurrent()
        assertEquals(updatedOsd.watermark, flow.value.watermark)
        assertEquals("Camera B", flow.value.channelName.text)
    }
}
