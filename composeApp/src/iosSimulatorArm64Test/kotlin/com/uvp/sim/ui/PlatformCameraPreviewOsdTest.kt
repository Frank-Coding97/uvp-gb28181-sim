package com.uvp.sim.ui

import com.uvp.sim.config.OsdConfig
import com.uvp.sim.config.OsdLayer
import com.uvp.sim.config.OsdPosition
import com.uvp.sim.config.OsdSize
import com.uvp.sim.osd.IosOsdLayout
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.flow.MutableStateFlow
import platform.CoreGraphics.CGRectMake
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalForeignApi::class)
class PlatformCameraPreviewOsdTest {
    @Test
    fun portraitAspectFillUsesEncodingScaleButVisibleAnchors() {
        val mapping = PreviewAspectFillMapping.calculate(
            sourceWidth = 1280.0,
            sourceHeight = 720.0,
            viewWidth = 390.0,
            viewHeight = 844.0,
        )

        assertEquals(844.0 / 720.0, mapping.scale, 0.0001)
        assertEquals((390.0 - 1280.0 * mapping.scale) / 2.0, mapping.offsetX, 0.0001)
        assertEquals(0.0, mapping.offsetY, 0.0001)
        assertEquals(390.0 / mapping.scale, mapping.visibleSourceWidth, 0.0001)
        assertEquals(720.0, mapping.visibleSourceHeight, 0.0001)
        val margin = IosOsdLayout.MARGIN_PX
        val timestampX = margin * mapping.scale
        val channelX = (mapping.visibleSourceWidth - margin - 100.0) * mapping.scale
        assertTrue(timestampX >= 0.0)
        assertTrue(channelX >= 0.0 && channelX + 100.0 * mapping.scale <= 390.0)
    }

    @Test
    fun landscapeAspectFillMapsTheEncodingCanvasWithVerticalCrop() {
        val mapping = PreviewAspectFillMapping.calculate(
            sourceWidth = 1280.0,
            sourceHeight = 720.0,
            viewWidth = 844.0,
            viewHeight = 390.0,
        )

        assertEquals(844.0 / 1280.0, mapping.scale, 0.0001)
        assertEquals(0.0, mapping.offsetX, 0.0001)
        assertEquals((390.0 - 720.0 * mapping.scale) / 2.0, mapping.offsetY, 0.0001)
        assertEquals(1280.0, mapping.visibleSourceWidth, 0.0001)
        assertEquals(390.0 / mapping.scale, mapping.visibleSourceHeight, 0.0001)
    }

    @Test
    fun osdLayoutStaysInEncodingCoordinatesBeforePreviewMapping() {
        val mapping = PreviewAspectFillMapping.calculate(1280.0, 720.0, 390.0, 844.0)
        val fontPixels = IosOsdLayout.pixelSize(OsdSize.MEDIUM, 720)
        val channelOrigin = IosOsdLayout.channelNameOrigin(
            text = "Camera",
            viewportWidth = mapping.visibleSourceWidth,
            textWidth = 100.0,
            persistedPosition = OsdPosition.TOP_RIGHT,
        )!!
        val watermark = IosOsdLayout.watermarkLayout("W", 1280.0, 720.0, 50.0, 36.0)

        assertEquals(36, fontPixels)
        assertEquals((mapping.visibleSourceWidth - 106.0) * mapping.scale, channelOrigin.x * mapping.scale, 0.0001)
        assertEquals(125.0, watermark.stepX, 0.0001)
        assertEquals(144.0, watermark.stepY, 0.0001)
        assertEquals(125.0 * mapping.scale, 125.0 * mapping.scale, 0.0001)
        assertEquals(36.0 * mapping.scale, fontPixels * mapping.scale, 0.0001)
        assertTrue(channelOrigin.x * mapping.scale < 390.0)
    }

    @Test
    fun dynamicOutputSizeChangesPreviewScaleAndFontRatio() {
        val hd = PreviewAspectFillMapping.calculate(1280.0, 720.0, 390.0, 844.0)
        val fullHd = PreviewAspectFillMapping.calculate(1920.0, 1080.0, 390.0, 844.0)

        assertTrue(hd.scale > fullHd.scale)
        assertEquals(
            IosOsdLayout.pixelSize(OsdSize.MEDIUM, 720) * hd.scale,
            IosOsdLayout.pixelSize(OsdSize.MEDIUM, 1080) * fullHd.scale,
            1.0,
        )
    }

    @Test
    fun containerKeepsOnePreviewLayerWithOverlayAboveIt() {
        val container = CameraPreviewContainerView()

        assertEquals(1, container.layer.sublayers?.count { it is platform.AVFoundation.AVCaptureVideoPreviewLayer })
        assertSame(container, container.osdOverlay.superview)
        assertTrue(container.subviews.indexOf(container.osdOverlay) >= 0)
    }

    @Test
    fun resizeUpdatesPreviewAndOverlayFramesTogether() {
        val container = CameraPreviewContainerView()
        container.setFrame(CGRectMake(0.0, 0.0, 390.0, 844.0))
        container.layoutSubviews()
        assertFrame(container, 390.0, 844.0)

        container.setFrame(CGRectMake(0.0, 0.0, 844.0, 390.0))
        container.layoutSubviews()
        assertFrame(container, 844.0, 390.0)
    }

    @Test
    fun refreshReadsHotFlowWithoutChangingPreviewLayer() {
        val flow = MutableStateFlow(osdConfig(channel = "A", watermark = "W"))
        val container = CameraPreviewContainerView(flow)
        val previewLayer = container.previewLayer

        container.osdOverlay.refreshForTest()
        assertEquals("A", container.osdOverlay.latestSnapshotForTest.channelName)
        assertEquals("W", container.osdOverlay.latestSnapshotForTest.watermark)

        flow.value = osdConfig(channel = "B", watermark = "NEW")
        container.osdOverlay.refreshForTest()
        assertEquals("B", container.osdOverlay.latestSnapshotForTest.channelName)
        assertEquals("NEW", container.osdOverlay.latestSnapshotForTest.watermark)
        assertSame(previewLayer, container.previewLayer)
    }

    @Test
    fun disablingAllLayersClearsOverlayWithoutChangingPreview() {
        val flow = MutableStateFlow(osdConfig(channel = "A", watermark = "W"))
        val container = CameraPreviewContainerView(flow)
        val previewLayer = container.previewLayer
        container.osdOverlay.refreshForTest()

        flow.value = OsdConfig(
            timestamp = layer(enabled = false),
            channelName = layer(enabled = false),
            watermark = layer(enabled = false),
        )
        container.osdOverlay.refreshForTest()

        assertTrue(container.osdOverlay.latestSnapshotForTest.run {
            timestamp == null && channelName == null && watermark == null
        })
        assertTrue(container.osdOverlay.hidden)
        assertSame(previewLayer, container.previewLayer)
    }

    @Test
    fun refreshLifecycleNeverAccumulatesMultipleJobs() {
        val overlay = CameraPreviewOsdOverlayView(MutableStateFlow(osdConfig()))

        overlay.startRefreshingForTest()
        overlay.startRefreshingForTest()
        assertEquals(1, overlay.activeRefreshJobsForTest)

        overlay.stopRefreshingForTest()
        overlay.stopRefreshingForTest()
        assertEquals(0, overlay.activeRefreshJobsForTest)

        overlay.startRefreshingForTest()
        assertEquals(1, overlay.activeRefreshJobsForTest)
        overlay.stopRefreshingForTest()
    }

    @Test
    fun hostRetainsTheSameContainerPreviewLayerAndOverlay() {
        val first = IosCameraPreviewHost.containerView
        val preview = first.previewLayer
        val overlay = first.osdOverlay

        val second = IosCameraPreviewHost.containerView

        assertSame(first, second)
        assertSame(preview, second.previewLayer)
        assertSame(overlay, second.osdOverlay)
        assertFalse(second.osdOverlay.userInteractionEnabled)
    }

    private fun assertFrame(container: CameraPreviewContainerView, width: Double, height: Double) {
        container.previewLayer.frame.useContents {
            assertEquals(width, size.width)
            assertEquals(height, size.height)
        }
        container.osdOverlay.frame.useContents {
            assertEquals(width, size.width)
            assertEquals(height, size.height)
        }
    }

    private fun osdConfig(channel: String = "Camera", watermark: String = ""): OsdConfig = OsdConfig(
        timestamp = layer(enabled = true),
        channelName = layer(enabled = true, text = channel),
        watermark = layer(enabled = watermark.isNotEmpty(), text = watermark),
    )

    private fun layer(enabled: Boolean, text: String = ""): OsdLayer = OsdLayer(
        enabled = enabled,
        text = text,
        position = OsdPosition.TOP_LEFT,
        size = OsdSize.MEDIUM,
        fillColor = "#FFFFFF",
        outlineColor = "#000000",
    )
}
