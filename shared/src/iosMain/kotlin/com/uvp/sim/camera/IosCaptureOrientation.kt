package com.uvp.sim.camera

import kotlin.math.max

internal data class AspectFillTransform(
    val scale: Double,
    val translateX: Double,
    val translateY: Double,
)

internal fun aspectFillTransform(
    sourceWidth: Double,
    sourceHeight: Double,
    targetWidth: Double,
    targetHeight: Double,
): AspectFillTransform {
    val scale = max(targetWidth / sourceWidth, targetHeight / sourceHeight)
    return AspectFillTransform(
        scale = scale,
        translateX = (targetWidth - sourceWidth * scale) / 2.0,
        translateY = (targetHeight - sourceHeight * scale) / 2.0,
    )
}
