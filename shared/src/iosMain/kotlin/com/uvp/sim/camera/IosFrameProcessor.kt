package com.uvp.sim.camera

import com.uvp.sim.api.LogTag
import com.uvp.sim.config.OsdConfig
import com.uvp.sim.observability.LogLevel
import com.uvp.sim.observability.SystemLogger
import com.uvp.sim.osd.FrameMirrorTransform
import com.uvp.sim.osd.IosFrameMirrorHolder
import com.uvp.sim.osd.IosOsdBitmapRenderer
import com.uvp.sim.osd.IosOsdRenderResult
import com.uvp.sim.osd.IosVideoMaskHolder
import com.uvp.sim.osd.OsdSnapshot
import com.uvp.sim.osd.OsdTickerSource
import com.uvp.sim.osd.VideoMaskOverlay
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
import platform.CoreImage.CIColor
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
    /**
     * 当前生效的**画面遮挡**（GB-2022 A.2.1.17）。默认读进程级 [IosVideoMaskHolder] ——
     * 与 Android 侧 `OsdRendererHolder.installMaskSupplier` 同一形状：
     * 装一次、所有画面消费者共享，不必逐层穿过 Controller / Coordinator / Session 传参。
     *
     * 测试可直接注一个固定快照进来。
     */
    private val maskSupplier: () -> VideoMaskOverlay = { IosVideoMaskHolder.current() },
    /**
     * 当前生效的**画面翻转**（GB-2022 A.2.1.23）。默认读进程级 [IosFrameMirrorHolder] ——
     * 形状同 [maskSupplier]：装一次、所有画面消费者共享，不逐层传参。
     *
     * 测试可直接注一个固定变换进来。
     */
    private val mirrorSupplier: () -> FrameMirrorTransform = { IosFrameMirrorHolder.current() },
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
        // 画面翻转（GB-2022 A.2.1.23）—— 只翻**画面本身**，且在叠加层之前：
        // OSD 文字与遮挡块是"盖在画面上的内容"，位置由平台按画面坐标给定，
        // 跟着一起翻会让设备显示的位置与平台配置的坐标系统性错位（而画面看起来仍正常）。
        // 与 Android 侧 `CameraTexturePass.setFrameMirror` 的位置对称。
        val mirrored = applyFrameMirror(positioned)
        val osdStartedAt = CACurrentMediaTime()
        val composed = composeOverlays(mirrored, targetWidth, targetHeight)

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

    /**
     * 把画面翻转（GB-2022 A.2.1.23）施加到**已经铺满画框**的帧上。
     *
     * ⛔ 必须绕**画框中心**翻，不是绕图像自身 extent 的中心：`positioned` 是 aspectFill
     * 之后的结果（可能比画框略大、且带小数偏移），而输出按 `(0,0,targetW,targetH)` 裁切 ——
     * 绕画框中心翻才能保证裁到的仍是同一块画面，只是方向反了。绕图像自身中心翻会在
     * 偏移非零时把画面整体挪走，表现为"翻了之后边缘多出一条黑边"。
     *
     * [FrameMirrorTransform.NONE]（平台没配过 / 配了 0 / 越界值）时**原样返回**，
     * 一帧都不多进 CoreImage 变换分支 —— 与加本功能之前逐帧一致。
     */
    private fun applyFrameMirror(image: CIImage): CIImage {
        val mirror = mirrorSupplier()
        if (mirror.isIdentity) return image
        val sx = if (mirror.mirrorX) -1.0 else 1.0
        val sy = if (mirror.mirrorY) -1.0 else 1.0
        val cx = targetWidth / 2.0
        val cy = targetHeight / 2.0
        return image
            .imageByApplyingTransform(CGAffineTransformMakeTranslation(-cx, -cy))
            .imageByApplyingTransform(CGAffineTransformMakeScale(sx, sy))
            .imageByApplyingTransform(CGAffineTransformMakeTranslation(cx, cy))
    }

    /**
     * OSD 文字 → 画面遮挡，依次叠到画面上。
     *
     * ⛔ 顺序不能反：遮挡是"盖在画面上的内容"，必须压在 OSD 文字之上
     * （平台配的遮挡挡住的也包括 OSD 所占的那块区域）。与 Android 侧
     * `OsdRenderer.onFrameAvailable`（`drawOsdLayers` → `drawMaskLayers`）保持一致。
     */
    private fun composeOverlays(positioned: CIImage, width: Int, height: Int): ComposedFrame {
        val osd = composeOsd(positioned)
        val rects = maskSupplier().rects
        if (rects.isEmpty()) return osd
        return try {
            ComposedFrame(applyMask(osd.image, rects, width, height), true)
        } catch (t: Throwable) {
            logRenderFallback(t)
            osd
        }
    }

    /**
     * 把归一化 0~1 的遮挡矩形填成**纯黑实心块**。
     *
     * ⛔ 坐标系：CoreImage 的原点在**左下角**，而 [VideoMaskRect] 的原点在画面**左上角**，
     * 所以纵向要 `(1 - bottom) * height` 换算一次。漏了这一步遮挡块会**上下镜像** ——
     * 位置看着"差不多对"、其实错开一整块，是最难当场发现的一类错。
     *
     * 用 `imageWithColor` + `imageByCroppingToRect` 造实心块，而不是开 CGContext 画：
     * 整条 iOS 管线本来就是 CoreImage 组合（见 [composeOsd]），多开一条位图路径反而
     * 要多管一份色彩空间与翻转。
     */
    private fun applyMask(
        base: CIImage,
        rects: List<com.uvp.sim.osd.VideoMaskRect>,
        width: Int,
        height: Int,
    ): CIImage {
        val w = width.toDouble()
        val h = height.toDouble()
        val black = CIImage.imageWithColor(CIColor.colorWithRed(0.0, green = 0.0, blue = 0.0, alpha = 1.0))
        var composed = base
        for (rect in rects) {
            val block = black.imageByCroppingToRect(
                CGRectMake(
                    rect.left * w,
                    (1.0 - rect.bottom) * h,
                    (rect.right - rect.left) * w,
                    (rect.bottom - rect.top) * h,
                )
            )
            composed = block.imageByCompositingOverImage(composed)
        }
        return composed
    }

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
