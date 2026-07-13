package com.uvp.sim.camera

import com.uvp.sim.api.LogTag
import com.uvp.sim.config.OsdConfig
import com.uvp.sim.observability.LogLevel
import com.uvp.sim.observability.SystemLogger
import com.uvp.sim.osd.IosOsdBitmapRenderer
import com.uvp.sim.osd.IosOsdRenderResult
import com.uvp.sim.osd.OsdSnapshot
import com.uvp.sim.osd.OsdTickerSource
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.CValue
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.useContents
import kotlinx.cinterop.value
import platform.CoreFoundation.CFRelease
import platform.CoreGraphics.CGAffineTransformMakeScale
import platform.CoreGraphics.CGAffineTransformMakeTranslation
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGRect
import platform.CoreImage.CIContext
import platform.CoreImage.CIImage
import platform.CoreVideo.CVImageBufferRef
import platform.CoreVideo.CVPixelBufferCreate
import platform.CoreVideo.CVPixelBufferRef
import platform.CoreVideo.CVPixelBufferRefVar
import platform.CoreVideo.kCVPixelFormatType_32BGRA
import platform.QuartzCore.CACurrentMediaTime
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Clock

/** CoreImage uses its Metal renderer to normalize portrait camera frames for VideoToolbox. */
@OptIn(ExperimentalForeignApi::class)
internal class IosFrameProcessor(
    private val targetWidth: Int,
    private val targetHeight: Int,
    private val osdConfigFlow: StateFlow<OsdConfig>,
    private val osdRenderer: IosOsdBitmapRenderer = IosOsdBitmapRenderer(),
    private val renderOverride: ((CIImage, CVPixelBufferRef, CValue<CGRect>) -> Unit)? = null,
) {
    private val context: CIContext = CIContext.contextWithOptions(null)
    private val tickerSource = OsdTickerSource(osdConfigFlow)
    private var lastFallbackLogAtMs = -1L
    private var perfSamples = 0
    private var perfTotalMs = 0.0
    private var perfMaxMs = 0.0

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
        val osdStartedAt = CACurrentMediaTime()
        val composed = composeOsd(positioned)

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

        val outputBounds = CGRectMake(0.0, 0.0, targetWidth.toDouble(), targetHeight.toDouble())
        return try {
            renderImage(composed.image, output, outputBounds)
            if (composed.hasOverlay) recordRenderPerformance(osdStartedAt)
            output
        } catch (t: Throwable) {
            if (composed.hasOverlay) {
                logRenderFallback(t)
                try {
                    renderImage(positioned, output, outputBounds)
                    recordRenderPerformance(osdStartedAt)
                    output
                } catch (_: Throwable) {
                    CFRelease(output)
                    null
                }
            } else {
                CFRelease(output)
                null
            }
        }
    }

    private data class ComposedFrame(val image: CIImage, val hasOverlay: Boolean)

    private fun composeOsd(positioned: CIImage): ComposedFrame = try {
        val overlay = renderOsdForTest()?.image ?: return ComposedFrame(positioned, false)
        ComposedFrame(overlay.imageByCompositingOverImage(positioned), true)
    } catch (t: Throwable) {
        logRenderFallback(t)
        ComposedFrame(positioned, false)
    }

    private fun renderImage(image: CIImage, output: CVPixelBufferRef, bounds: CValue<CGRect>) {
        renderOverride?.let {
            it(image, output, bounds)
            return
        }
        context.render(
            image = image,
            toCVPixelBuffer = output,
            bounds = bounds,
            colorSpace = null,
        )
    }

    private fun logRenderFallback(cause: Throwable) {
        val now = Clock.System.now().toEpochMilliseconds()
        if (lastFallbackLogAtMs >= 0L && now - lastFallbackLogAtMs < 10_000L) return
        lastFallbackLogAtMs = now
        SystemLogger.emit(
            LogLevel.Warning,
            LogTag.Media,
            "IOS_OSD_RENDER_FALLBACK type=${cause::class.simpleName}",
        )
    }

    private fun recordRenderPerformance(startedAt: Double) {
        val elapsedMs = (CACurrentMediaTime() - startedAt) * 1_000.0
        perfSamples++
        perfTotalMs += elapsedMs
        perfMaxMs = maxOf(perfMaxMs, elapsedMs)
        if (perfSamples < 250) return
        SystemLogger.emit(
            LogLevel.Info,
            LogTag.Media,
            "IOS_OSD_RENDER_PERF samples=$perfSamples avgMs=${perfTotalMs / perfSamples} maxMs=$perfMaxMs",
        )
        perfSamples = 0
        perfTotalMs = 0.0
        perfMaxMs = 0.0
    }

    internal fun currentOsdSnapshotForTest(): OsdSnapshot = tickerSource.snapshot(osdConfigFlow.value)

    internal fun renderOsdForTest(): IosOsdRenderResult? {
        val config = osdConfigFlow.value
        return osdRenderer.render(
            snapshot = tickerSource.snapshot(config),
            config = config,
            width = targetWidth,
            height = targetHeight,
        )
    }
}
