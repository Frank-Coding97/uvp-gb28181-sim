package com.uvp.sim.camera

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class PreviewTextureTransformTest {

    @Test
    fun fullBufferWithoutRotationKeepsAspectAndCoordinates() {
        val result = PreviewTextureTransform.calculate(
            bufferWidth = 1280,
            bufferHeight = 720,
            cropLeft = 0,
            cropTop = 0,
            cropRight = 1280,
            cropBottom = 720,
            rotationDegrees = 0,
            mirrored = false,
        )

        assertEquals(1280, result.frameWidth)
        assertEquals(720, result.frameHeight)
        assertContentEquals(
            floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f),
            result.textureCoordinates,
        )
    }

    @Test
    fun portraitBufferRotatedClockwiseProducesLandscapeFrame() {
        val result = PreviewTextureTransform.calculate(
            bufferWidth = 720,
            bufferHeight = 1280,
            cropLeft = 0,
            cropTop = 0,
            cropRight = 720,
            cropBottom = 1280,
            rotationDegrees = 90,
            mirrored = false,
        )

        assertEquals(1280, result.frameWidth)
        assertEquals(720, result.frameHeight)
        assertContentEquals(
            floatArrayOf(1f, 1f, 1f, 0f, 0f, 1f, 0f, 0f),
            result.textureCoordinates,
        )
    }

    @Test
    fun cameraTransformedSurfaceKeepsBaseCoordinatesButUsesRotatedAspect() {
        val result = PreviewTextureTransform.calculate(
            bufferWidth = 1600,
            bufferHeight = 1200,
            cropLeft = 0,
            cropTop = 0,
            cropRight = 1600,
            cropBottom = 1200,
            rotationDegrees = 90,
            mirrored = false,
            hasCameraTransform = true,
        )

        assertEquals(1200, result.frameWidth)
        assertEquals(1600, result.frameHeight)
        assertContentEquals(
            floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f),
            result.textureCoordinates,
        )
    }

    @Test
    fun cropRectIsMappedIntoNormalizedBufferCoordinates() {
        val result = PreviewTextureTransform.calculate(
            bufferWidth = 1280,
            bufferHeight = 720,
            cropLeft = 160,
            cropTop = 90,
            cropRight = 1120,
            cropBottom = 630,
            rotationDegrees = 0,
            mirrored = false,
        )

        assertEquals(960, result.frameWidth)
        assertEquals(540, result.frameHeight)
        assertContentEquals(
            floatArrayOf(0.125f, 0.875f, 0.875f, 0.875f, 0.125f, 0.125f, 0.875f, 0.125f),
            result.textureCoordinates,
        )
    }

    @Test
    fun mirroredOutputSamplesTheOppositeHorizontalEdge() {
        val result = PreviewTextureTransform.calculate(
            bufferWidth = 1280,
            bufferHeight = 720,
            cropLeft = 0,
            cropTop = 0,
            cropRight = 1280,
            cropBottom = 720,
            rotationDegrees = 0,
            mirrored = true,
        )

        assertContentEquals(
            floatArrayOf(1f, 1f, 0f, 1f, 1f, 0f, 0f, 0f),
            result.textureCoordinates,
        )
    }
}
