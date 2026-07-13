package com.uvp.sim.osd

import com.uvp.sim.config.OsdConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class IosOsdBitmapRendererTest {
    private val config = OsdConfig().copy(
        channelName = OsdConfig().channelName.copy(text = "Camera A"),
        watermark = OsdConfig().watermark.copy(enabled = true, text = "UVP TEST"),
    )

    @Test
    fun rendersAsciiAndCjkTextIntoNonEmptyBitmap() {
        val renderer = IosOsdBitmapRenderer()

        val ascii = assertNotNull(renderer.renderTextForTest("2026-07-11 18:30:00.123", config.timestamp, 720))
        val cjk = assertNotNull(renderer.renderTextForTest("前置摄像头-测试，01", config.channelName, 720))

        assertTrue(ascii.width > 0.0 && ascii.height > 0.0)
        assertTrue(cjk.width > 0.0 && cjk.height > 0.0)
        assertTrue(ascii.hasVisiblePixels)
        assertTrue(cjk.hasVisiblePixels)
    }

    @Test
    fun preservesTransparentBackgroundAndConfiguredFillAndOutline() {
        val renderer = IosOsdBitmapRenderer()
        val bitmap = assertNotNull(renderer.renderTextForTest("OSD", config.timestamp, 720))

        assertTrue(bitmap.hasTransparentPixels)
        assertTrue(bitmap.hasFillPixels)
        assertTrue(bitmap.hasOutlinePixels)
    }

    @Test
    fun timestampUsesTightBackingBitmapInsteadOfFullViewport() {
        val renderer = IosOsdBitmapRenderer()
        val result = assertNotNull(
            renderer.render(
                OsdSnapshot("2026-07-11 18:30:00.123", null, null),
                config,
                1280,
                720,
            )
        )

        assertTrue(result.timestampBackingPixelArea > 0)
        assertTrue(result.timestampBackingPixelArea < 1280 * 720 / 8)
        assertEquals(6.0, result.visibleBounds.minX, 1.0)
        assertEquals(6.0, result.visibleBounds.minY, 1.0)
    }

    @Test
    fun longChannelRemainsInsideRightMargin() {
        val renderer = IosOsdBitmapRenderer()
        val result = assertNotNull(
            renderer.render(
                snapshot = OsdSnapshot(null, "A very long channel name 前置摄像头", null),
                config = config,
                width = 320,
                height = 180,
            )
        )

        assertTrue(result.visibleBounds.maxX <= 314.0)
        assertEquals(6.0, 320.0 - result.visibleBounds.maxX, 1.0)
    }

    @Test
    fun watermarkUsesTiledLayoutAndConfiguredAlpha() {
        val renderer = IosOsdBitmapRenderer()
        val result = assertNotNull(
            renderer.render(OsdSnapshot(null, null, "UVP TEST"), config, 1280, 720)
        )

        assertTrue(result.watermarkTileCount > 1)
        assertTrue(result.maximumWatermarkAlpha <= IosOsdLayout.WATERMARK_ALPHA)
    }

    @Test
    fun staticLayersReuseCacheEntries() {
        val renderer = IosOsdBitmapRenderer()
        val snapshot = OsdSnapshot(null, "Camera A", "UVP TEST")

        val first = assertNotNull(renderer.render(snapshot, config, 1280, 720))
        val count = renderer.staticBuildCount
        val second = assertNotNull(renderer.render(snapshot, config, 1280, 720))

        assertEquals(2, count)
        assertEquals(count, renderer.staticBuildCount)
        assertTrue(first.channelBackingPixelArea < 1280 * 720 / 8)
        assertSame(first.channelLayerIdentity, second.channelLayerIdentity)
        assertSame(first.watermarkLayerIdentity, second.watermarkLayerIdentity)
    }

    @Test
    fun staticCacheInvalidatesForEveryVisualInput() {
        val renderer = IosOsdBitmapRenderer()
        val snapshot = OsdSnapshot(null, "Camera A", null)
        renderer.render(snapshot, config, 1280, 720)

        val variants = listOf(
            snapshot.copy(channelName = "Camera B") to config,
            snapshot to config.copy(channelName = config.channelName.copy(size = com.uvp.sim.config.OsdSize.LARGE)),
            snapshot to config.copy(channelName = config.channelName.copy(fillColor = "#00AA7F")),
            snapshot to config.copy(channelName = config.channelName.copy(outlineColor = "#112233")),
        )
        var expected = 1
        for ((variantSnapshot, variantConfig) in variants) {
            renderer.render(variantSnapshot, variantConfig, 1280, 720)
            expected++
            assertEquals(expected, renderer.staticBuildCount)
        }
        renderer.render(snapshot, config, 640, 360)
        assertEquals(expected + 1, renderer.staticBuildCount)
    }

    @Test
    fun emptySnapshotDoesNotBuildBitmap() {
        val renderer = IosOsdBitmapRenderer()

        assertNull(renderer.render(OsdSnapshot(null, null, null), config, 1280, 720))
        assertEquals(0, renderer.staticBuildCount)
    }
}
