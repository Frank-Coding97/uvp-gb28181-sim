package com.uvp.sim.camera

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.useContents
import kotlinx.cinterop.value
import platform.CoreFoundation.CFRelease
import platform.CoreGraphics.CGAffineTransformMakeScale
import platform.CoreGraphics.CGAffineTransformMakeTranslation
import platform.CoreGraphics.CGRectMake
import platform.CoreImage.CIContext
import platform.CoreImage.CIImage
import platform.CoreVideo.CVImageBufferRef
import platform.CoreVideo.CVPixelBufferCreate
import platform.CoreVideo.CVPixelBufferRef
import platform.CoreVideo.CVPixelBufferRefVar
import platform.CoreVideo.kCVPixelFormatType_32BGRA

/** CoreImage uses its Metal renderer to normalize portrait camera frames for VideoToolbox. */
@OptIn(ExperimentalForeignApi::class)
internal class IosFrameProcessor(
    private val targetWidth: Int,
    private val targetHeight: Int,
) {
    private val context: CIContext = CIContext.contextWithOptions(null)

    fun process(input: CVImageBufferRef): CVPixelBufferRef? {
        val source = CIImage.imageWithCVPixelBuffer(input)
        val sourceExtent = source.extent
        val extentValues = sourceExtent.useContents {
            doubleArrayOf(origin.x, origin.y, size.width, size.height)
        }
        val originX = extentValues[0]
        val originY = extentValues[1]
        val sourceWidth = extentValues[2]
        val sourceHeight = extentValues[3]
        if (sourceWidth <= 0.0 || sourceHeight <= 0.0) return null

        val geometry = aspectFillTransform(
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            targetWidth = targetWidth.toDouble(),
            targetHeight = targetHeight.toDouble(),
        )
        val normalized = source.imageByApplyingTransform(
            CGAffineTransformMakeTranslation(-originX, -originY)
        )
        val scaled = normalized.imageByApplyingTransform(
            CGAffineTransformMakeScale(geometry.scale, geometry.scale)
        )
        val positioned = scaled.imageByApplyingTransform(
            CGAffineTransformMakeTranslation(geometry.translateX, geometry.translateY)
        )

        val output = memScoped {
            val out = alloc<CVPixelBufferRefVar>()
            val status = CVPixelBufferCreate(
                allocator = null,
                width = targetWidth.toULong(),
                height = targetHeight.toULong(),
                pixelFormatType = kCVPixelFormatType_32BGRA,
                pixelBufferAttributes = null,
                pixelBufferOut = out.ptr,
            )
            if (status == 0) out.value else null
        } ?: return null

        return try {
            context.render(
                image = positioned,
                toCVPixelBuffer = output,
                bounds = CGRectMake(0.0, 0.0, targetWidth.toDouble(), targetHeight.toDouble()),
                colorSpace = null,
            )
            output
        } catch (t: Throwable) {
            CFRelease(output)
            null
        }
    }
}
