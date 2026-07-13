package com.uvp.sim.camera

import com.uvp.sim.config.OsdConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.CoreFoundation.CFRelease
import platform.CoreVideo.CVPixelBufferCreate
import platform.CoreVideo.CVPixelBufferRef
import platform.CoreVideo.CVPixelBufferRefVar
import platform.CoreVideo.kCVPixelFormatType_32BGRA
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@OptIn(ExperimentalForeignApi::class)
class IosFrameProcessorOsdTest {

    @Test
    fun all_disabled_uses_zero_overlay_fast_path() {
        val base = OsdConfig()
        val flow = MutableStateFlow(
            base.copy(
                timestamp = base.timestamp.copy(enabled = false),
                channelName = base.channelName.copy(enabled = false),
                watermark = base.watermark.copy(enabled = false),
            )
        )
        val processor = IosFrameProcessor(1280, 720, flow)

        assertNull(processor.renderOsdForTest())
    }

    @Test
    fun enabled_channel_renders_at_final_output_dimensions() {
        val base = OsdConfig()
        val flow = MutableStateFlow(
            base.copy(
                timestamp = base.timestamp.copy(enabled = false),
                channelName = base.channelName.copy(enabled = true, text = "Camera A"),
            )
        )
        val processor = IosFrameProcessor(1280, 720, flow)

        val result = assertNotNull(processor.renderOsdForTest())
        assertEquals(6.0, 1280.0 - result.visibleBounds.maxX, absoluteTolerance = 1.0)
    }

    @Test
    fun hot_flow_update_is_visible_without_recreating_processor() {
        val base = OsdConfig()
        val flow = MutableStateFlow(
            base.copy(
                timestamp = base.timestamp.copy(enabled = false),
                channelName = base.channelName.copy(enabled = true, text = "Camera A"),
            )
        )
        val processor = IosFrameProcessor(1280, 720, flow)
        assertEquals("Camera A", processor.currentOsdSnapshotForTest().channelName)

        flow.value = flow.value.copy(channelName = flow.value.channelName.copy(text = "Camera B"))

        assertEquals("Camera B", processor.currentOsdSnapshotForTest().channelName)
        assertNotNull(processor.renderOsdForTest())
    }

    @Test
    fun final_overlay_render_failure_retries_positioned_frame_in_same_target_buffer() {
        val base = OsdConfig()
        val flow = MutableStateFlow(
            base.copy(
                timestamp = base.timestamp.copy(enabled = false),
                channelName = base.channelName.copy(enabled = true, text = "Camera A"),
            )
        )
        var renderCalls = 0
        val processor = IosFrameProcessor(
            targetWidth = 64,
            targetHeight = 64,
            osdConfigFlow = flow,
            renderOverride = { _, _, _ ->
                renderCalls++
                if (renderCalls == 1) error("lazy CI overlay failure")
            },
        )
        val input = createPixelBuffer(64, 64)
        try {
            val output = assertNotNull(processor.process(input))
            try {
                assertEquals(2, renderCalls)
            } finally {
                CFRelease(output)
            }
        } finally {
            CFRelease(input)
        }
    }

    private fun createPixelBuffer(width: Int, height: Int): CVPixelBufferRef = memScoped {
        val out = alloc<CVPixelBufferRefVar>()
        val status = CVPixelBufferCreate(
            allocator = null,
            width = width.toULong(),
            height = height.toULong(),
            pixelFormatType = kCVPixelFormatType_32BGRA,
            pixelBufferAttributes = null,
            pixelBufferOut = out.ptr,
        )
        check(status == 0)
        requireNotNull(out.value)
    }
}
