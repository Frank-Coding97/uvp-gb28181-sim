package com.uvp.sim.camera

import kotlin.test.Test
import kotlin.test.assertEquals

class IosCaptureOrientationTest {

    @Test
    fun portrait_source_is_aspect_filled_into_landscape_encoder_canvas() {
        val transform = aspectFillTransform(
            sourceWidth = 720.0,
            sourceHeight = 1280.0,
            targetWidth = 1280.0,
            targetHeight = 720.0,
        )

        assertEquals(1280.0 / 720.0, transform.scale, absoluteTolerance = 0.0001)
        assertEquals(0.0, transform.translateX, absoluteTolerance = 0.0001)
        assertEquals((720.0 - 1280.0 * transform.scale) / 2.0, transform.translateY, absoluteTolerance = 0.0001)
    }

    @Test
    fun matching_aspect_ratio_needs_no_crop() {
        val transform = aspectFillTransform(1280.0, 720.0, 1280.0, 720.0)

        assertEquals(1.0, transform.scale)
        assertEquals(0.0, transform.translateX)
        assertEquals(0.0, transform.translateY)
    }
}
