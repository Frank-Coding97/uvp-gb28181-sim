package com.uvp.sim.osd

import com.uvp.sim.config.OsdConfig
import com.uvp.sim.config.OsdLayer
import com.uvp.sim.config.OsdPosition
import com.uvp.sim.config.OsdSize
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OsdTickerSourceTest {

    @Test
    fun explicit_config_snapshot_does_not_tear_with_flow_value() {
        val flowConfig = OsdConfig().copy(
            channelName = OsdConfig().channelName.copy(text = "new"),
        )
        val captured = flowConfig.copy(
            channelName = flowConfig.channelName.copy(text = "captured"),
        )
        val source = OsdTickerSource(MutableStateFlow(flowConfig))

        assertEquals("captured", source.snapshot(captured).channelName)
        assertEquals("new", source.snapshot().channelName)
    }

    private fun layer(
        enabled: Boolean = false,
        text: String = ""
    ) = OsdLayer(
        enabled = enabled,
        text = text,
        position = OsdPosition.TOP_RIGHT,
        size = OsdSize.MEDIUM,
        fillColor = "#FFFFFF",
        outlineColor = "#000000"
    )

    @Test
    fun timestampEnabledReturnsNonNull() {
        val flow = MutableStateFlow(OsdConfig(timestamp = layer(enabled = true)))
        val snap = OsdTickerSource(flow).snapshot()
        assertNotNull(snap.timestamp)
    }

    @Test
    fun timestampDisabledReturnsNull() {
        val flow = MutableStateFlow(OsdConfig(timestamp = layer(enabled = false)))
        val snap = OsdTickerSource(flow).snapshot()
        assertNull(snap.timestamp)
    }

    @Test
    fun channelNameEnabledEmptyTextReturnsNull() {
        val flow = MutableStateFlow(OsdConfig(channelName = layer(enabled = true, text = "")))
        val snap = OsdTickerSource(flow).snapshot()
        assertNull(snap.channelName)
    }

    @Test
    fun channelNameEnabledWithTextReturnsText() {
        val flow = MutableStateFlow(OsdConfig(channelName = layer(enabled = true, text = "正门")))
        val snap = OsdTickerSource(flow).snapshot()
        assertEquals("正门", snap.channelName)
    }

    @Test
    fun timestampFormatMatchesPattern() {
        val flow = MutableStateFlow(OsdConfig(timestamp = layer(enabled = true)))
        val snap = OsdTickerSource(flow).snapshot()
        val pattern = Regex("^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}$")
        assertTrue(pattern.matches(snap.timestamp!!), "got: ${snap.timestamp}")
    }

    @Test
    fun fixedClockProducesExpectedTimestamp() {
        val fixed = object : Clock {
            override fun now(): Instant = Instant.fromEpochMilliseconds(1_700_000_000_123L)
        }
        val flow = MutableStateFlow(OsdConfig(timestamp = layer(enabled = true)))
        val snap = OsdTickerSource(flow, clock = fixed, timeZone = TimeZone.UTC).snapshot()
        assertEquals("2023-11-14 22:13:20.123", snap.timestamp)
    }

    @Test
    fun watermarkEnabledWithTextReturnsText() {
        val flow = MutableStateFlow(OsdConfig(watermark = layer(enabled = true, text = "@DEMO")))
        val snap = OsdTickerSource(flow).snapshot()
        assertEquals("@DEMO", snap.watermark)
    }
}
