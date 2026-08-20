package com.uvp.sim.camera

internal data class PreviewTextureGeometry(
    val frameWidth: Int,
    val frameHeight: Int,
    val textureCoordinates: FloatArray,
)

/** Converts CameraX's crop/rotation metadata into OES texture sampling coordinates. */
internal object PreviewTextureTransform {
    private val outputCorners = arrayOf(
        0f to 1f,
        1f to 1f,
        0f to 0f,
        1f to 0f,
    )

    fun calculate(
        bufferWidth: Int,
        bufferHeight: Int,
        cropLeft: Int,
        cropTop: Int,
        cropRight: Int,
        cropBottom: Int,
        rotationDegrees: Int,
        mirrored: Boolean,
    ): PreviewTextureGeometry {
        require(bufferWidth > 0 && bufferHeight > 0)
        require(cropLeft >= 0 && cropTop >= 0)
        require(cropRight in (cropLeft + 1)..bufferWidth)
        require(cropBottom in (cropTop + 1)..bufferHeight)

        val rotation = ((rotationDegrees % 360) + 360) % 360
        require(rotation % 90 == 0)

        val cropWidth = cropRight - cropLeft
        val cropHeight = cropBottom - cropTop
        val coordinates = FloatArray(outputCorners.size * 2)
        outputCorners.forEachIndexed { index, (outputX, outputY) ->
            val mirroredX = if (mirrored) 1f - outputX else outputX
            val (cropX, cropY) = when (rotation) {
                0 -> mirroredX to outputY
                90 -> outputY to 1f - mirroredX
                180 -> 1f - mirroredX to 1f - outputY
                else -> 1f - outputY to mirroredX
            }
            coordinates[index * 2] = (cropLeft + cropX * cropWidth) / bufferWidth
            coordinates[index * 2 + 1] = (cropTop + cropY * cropHeight) / bufferHeight
        }

        val swapsDimensions = rotation == 90 || rotation == 270
        return PreviewTextureGeometry(
            frameWidth = if (swapsDimensions) cropHeight else cropWidth,
            frameHeight = if (swapsDimensions) cropWidth else cropHeight,
            textureCoordinates = coordinates,
        )
    }
}
