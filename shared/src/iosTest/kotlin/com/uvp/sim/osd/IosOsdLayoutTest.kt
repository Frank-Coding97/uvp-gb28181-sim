package com.uvp.sim.osd

import com.uvp.sim.config.OsdPosition
import com.uvp.sim.config.OsdSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IosOsdLayoutTest {

    @Test
    fun font_sizes_match_android_height_ratios_and_flooring() {
        assertEquals(16, IosOsdLayout.pixelSize(OsdSize.SMALL, 480))
        assertEquals(24, IosOsdLayout.pixelSize(OsdSize.MEDIUM, 480))
        assertEquals(33, IosOsdLayout.pixelSize(OsdSize.LARGE, 480))
        assertEquals(25, IosOsdLayout.pixelSize(OsdSize.SMALL, 720))
        assertEquals(36, IosOsdLayout.pixelSize(OsdSize.MEDIUM, 720))
        assertEquals(50, IosOsdLayout.pixelSize(OsdSize.LARGE, 720))
        assertEquals(37, IosOsdLayout.pixelSize(OsdSize.SMALL, 1080))
        assertEquals(54, IosOsdLayout.pixelSize(OsdSize.MEDIUM, 1080))
        assertEquals(75, IosOsdLayout.pixelSize(OsdSize.LARGE, 1080))
        assertEquals(16, IosOsdLayout.pixelSize(OsdSize.SMALL, 100))
    }

    @Test
    fun timestamp_is_always_anchored_top_left() {
        OsdPosition.entries.forEach { persistedPosition ->
            assertEquals(
                IosOsdPoint(6.0, 6.0),
                IosOsdLayout.timestampOrigin("2026-07-11 12:00:00", persistedPosition),
            )
        }
    }

    @Test
    fun channel_name_is_always_anchored_top_right() {
        OsdPosition.entries.forEach { persistedPosition ->
            assertEquals(
                IosOsdPoint(1074.0, 6.0),
                IosOsdLayout.channelNameOrigin(
                    text = "Camera 01",
                    viewportWidth = 1280.0,
                    textWidth = 200.0,
                    persistedPosition = persistedPosition,
                ),
            )
        }
    }

    @Test
    fun valid_colors_are_parsed_case_insensitively() {
        assertEquals(IosOsdColor(255, 255, 255), IosOsdLayout.parseFillColor("#FFFFFF"))
        assertEquals(IosOsdColor(0, 170, 127), IosOsdLayout.parseFillColor("#00aA7F"))
        assertEquals(IosOsdColor(0, 0, 0), IosOsdLayout.parseOutlineColor("#000000"))
    }

    @Test
    fun invalid_colors_use_layer_specific_fallbacks() {
        val invalid = listOf("", "FFFFFF", "#FFF", "#FFFFFFFF", "#GGHHII")
        invalid.forEach { value ->
            assertEquals(IosOsdColor.WHITE, IosOsdLayout.parseFillColor(value))
            assertEquals(IosOsdColor.BLACK, IosOsdLayout.parseOutlineColor(value))
        }
    }

    @Test
    fun watermark_spacing_matches_android() {
        val layout = IosOsdLayout.watermarkLayout(
            text = "watermark",
            viewportWidth = 1280.0,
            viewportHeight = 720.0,
            textWidth = 100.0,
            textHeight = 20.0,
        )

        assertEquals(250.0, layout.stepX)
        assertEquals(80.0, layout.stepY)
        assertEquals(-30.0, layout.angleDegrees)
        assertEquals(0.28, layout.alpha)
    }

    @Test
    fun odd_watermark_rows_are_offset_by_half_a_horizontal_step() {
        val layout = IosOsdLayout.watermarkLayout("w", 1280.0, 720.0, 100.0, 20.0)
        val even = layout.tiles.single { it.row == 0 && it.column == 0 }
        val odd = layout.tiles.single { it.row == 1 && it.column == 0 }

        assertEquals(layout.stepX / 2.0, odd.center.x - even.center.x, absoluteTolerance = 0.0001)
    }

    @Test
    fun watermark_grid_extends_beyond_every_rotated_viewport_corner() {
        val width = 1280.0
        val height = 720.0
        val layout = IosOsdLayout.watermarkLayout("w", width, height, 100.0, 20.0)
        assertTrue(layout.tiles.minOf { it.center.x } <= 0.0)
        assertTrue(layout.tiles.maxOf { it.center.x } >= width)
        assertTrue(layout.tiles.minOf { it.center.y } <= 0.0)
        assertTrue(layout.tiles.maxOf { it.center.y } >= height)
    }

    @Test
    fun empty_text_produces_no_origin_or_watermark_tiles() {
        assertEquals(null, IosOsdLayout.timestampOrigin("", OsdPosition.CENTER))
        assertEquals(null, IosOsdLayout.channelNameOrigin("", 1280.0, 100.0, OsdPosition.CENTER))
        assertTrue(IosOsdLayout.watermarkLayout("", 1280.0, 720.0, 100.0, 20.0).tiles.isEmpty())
    }
}
