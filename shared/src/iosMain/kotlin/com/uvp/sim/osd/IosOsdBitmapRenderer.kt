package com.uvp.sim.osd

import com.uvp.sim.config.OsdConfig
import com.uvp.sim.config.OsdLayer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.autoreleasepool
import kotlinx.cinterop.get
import kotlinx.cinterop.useContents
import platform.CoreFoundation.CFDataGetBytePtr
import platform.CoreFoundation.CFDataGetLength
import platform.CoreFoundation.CFRelease
import platform.CoreGraphics.CGAffineTransformMakeRotation
import platform.CoreGraphics.CGAffineTransformMakeTranslation
import platform.CoreGraphics.CGDataProviderCopyData
import platform.CoreGraphics.CGImageGetBytesPerRow
import platform.CoreGraphics.CGImageGetDataProvider
import platform.CoreGraphics.CGImageGetHeight
import platform.CoreGraphics.CGImageGetWidth
import platform.CoreGraphics.CGContextConcatCTM
import platform.CoreGraphics.CGContextRestoreGState
import platform.CoreGraphics.CGContextSaveGState
import platform.CoreGraphics.CGContextTranslateCTM
import platform.CoreGraphics.CGRectMake
import platform.CoreImage.CIImage
import platform.Foundation.NSString
import platform.Foundation.create
import platform.UIKit.NSFontAttributeName
import platform.UIKit.NSForegroundColorAttributeName
import platform.UIKit.NSStrokeColorAttributeName
import platform.UIKit.NSStrokeWidthAttributeName
import platform.UIKit.UIColor
import platform.UIKit.UIFont
import platform.UIKit.UIFontWeightRegular
import platform.UIKit.UIGraphicsBeginImageContextWithOptions
import platform.UIKit.UIGraphicsEndImageContext
import platform.UIKit.UIGraphicsGetCurrentContext
import platform.UIKit.UIGraphicsGetImageFromCurrentImageContext
import platform.UIKit.drawAtPoint
import platform.UIKit.sizeWithAttributes
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.max

internal data class IosOsdVisibleBounds(
    val minX: Double,
    val minY: Double,
    val maxX: Double,
    val maxY: Double,
)

internal data class IosOsdRenderResult(
    val image: CIImage,
    val visibleBounds: IosOsdVisibleBounds,
    val watermarkTileCount: Int,
    val maximumWatermarkAlpha: Double,
    val channelLayerIdentity: Any?,
    val watermarkLayerIdentity: Any?,
    val timestampBackingPixelArea: Int,
    val channelBackingPixelArea: Int,
)

internal data class IosOsdTextBitmapProbe(
    val width: Double,
    val height: Double,
    val hasVisiblePixels: Boolean,
    val hasTransparentPixels: Boolean,
    val hasFillPixels: Boolean,
    val hasOutlinePixels: Boolean,
)

private enum class StaticLayerType { CHANNEL, WATERMARK }

private data class StaticLayerKey(
    val type: StaticLayerType,
    val text: String,
    val width: Int,
    val height: Int,
    val pixelSize: Int,
    val fillColor: String,
    val outlineColor: String,
)

private data class RenderedLayer(
    val image: CIImage,
    val bounds: IosOsdVisibleBounds,
    val watermarkTileCount: Int = 0,
    val maximumWatermarkAlpha: Double = 0.0,
    val backingPixelArea: Int = 0,
)

/** UIKit-backed OSD bitmap renderer. Its output is transparent and ready for Core Image compositing. */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal class IosOsdBitmapRenderer {
    private var channelCache: Pair<StaticLayerKey, RenderedLayer>? = null
    private var watermarkCache: Pair<StaticLayerKey, RenderedLayer>? = null

    internal var staticBuildCount: Int = 0
        private set

    fun render(
        snapshot: OsdSnapshot,
        config: OsdConfig,
        width: Int,
        height: Int,
    ): IosOsdRenderResult? {
        if (width <= 0 || height <= 0) return null
        if (snapshot.timestamp == null && snapshot.channelName == null && snapshot.watermark == null) return null

        val timestamp = snapshot.timestamp?.let {
            renderTimestamp(it, config.timestamp, height)
        }
        val channel = snapshot.channelName?.let {
            staticLayer(StaticLayerType.CHANNEL, it, config.channelName, width, height)
        }
        val watermark = snapshot.watermark?.let {
            staticLayer(StaticLayerType.WATERMARK, it, config.watermark, width, height)
        }
        val layers = listOfNotNull(timestamp, channel, watermark)
        if (layers.isEmpty()) return null

        var composite = layers.first().image
        for (index in 1 until layers.size) {
            composite = layers[index].image.imageByCompositingOverImage(composite)
        }
        return IosOsdRenderResult(
            image = composite,
            visibleBounds = unionBounds(layers.map { it.bounds }),
            watermarkTileCount = watermark?.watermarkTileCount ?: 0,
            maximumWatermarkAlpha = watermark?.maximumWatermarkAlpha ?: 0.0,
            channelLayerIdentity = channel,
            watermarkLayerIdentity = watermark,
            timestampBackingPixelArea = timestamp?.backingPixelArea ?: 0,
            channelBackingPixelArea = channel?.backingPixelArea ?: 0,
        )
    }

    internal fun renderTextForTest(text: String, layer: OsdLayer, viewportHeight: Int): IosOsdTextBitmapProbe? {
        if (text.isEmpty()) return null
        return autoreleasepool {
            val measured = measureText(text, layer, viewportHeight)
            val padding = 4.0
            val width = max(1.0, measured.first + padding * 2.0)
            val height = max(1.0, measured.second + padding * 2.0)
            UIGraphicsBeginImageContextWithOptions(platform.CoreGraphics.CGSizeMake(width, height), false, 1.0)
            try {
                NSString.create(string = text).drawAtPoint(
                    platform.CoreGraphics.CGPointMake(padding, padding),
                    withAttributes = textAttributes(layer, viewportHeight, 1.0),
                )
                val image = UIGraphicsGetImageFromCurrentImageContext() ?: return@autoreleasepool null
                val cgImage = image.CGImage ?: return@autoreleasepool null
                val pixels = inspectPixels(cgImage)
                IosOsdTextBitmapProbe(
                    width = image.size.useContents { this.width },
                    height = image.size.useContents { this.height },
                    hasVisiblePixels = pixels.hasVisible,
                    hasTransparentPixels = pixels.hasTransparent,
                    hasFillPixels = pixels.hasWhiteFill,
                    hasOutlinePixels = pixels.hasBlackOutline,
                )
            } finally {
                UIGraphicsEndImageContext()
            }
        }
    }

    private fun staticLayer(
        type: StaticLayerType,
        text: String,
        style: OsdLayer,
        width: Int,
        height: Int,
    ): RenderedLayer {
        val key = StaticLayerKey(
            type = type,
            text = text,
            width = width,
            height = height,
            pixelSize = IosOsdLayout.pixelSize(style.size, height),
            fillColor = style.fillColor,
            outlineColor = style.outlineColor,
        )
        val cached = if (type == StaticLayerType.CHANNEL) channelCache else watermarkCache
        if (cached?.first == key) return cached.second

        val built = when (type) {
            StaticLayerType.CHANNEL -> renderChannel(text, style, width, height)
            StaticLayerType.WATERMARK -> renderWatermark(text, style, width, height)
        }
        staticBuildCount++
        val entry = key to built
        if (type == StaticLayerType.CHANNEL) channelCache = entry else watermarkCache = entry
        return built
    }

    private fun renderTimestamp(text: String, style: OsdLayer, height: Int): RenderedLayer {
        val measured = measureText(text, style, height)
        val origin = requireNotNull(IosOsdLayout.timestampOrigin(text, style.position))
        return renderTextLayer(text, style, height, measured, origin)
    }

    private fun renderChannel(text: String, style: OsdLayer, width: Int, height: Int): RenderedLayer {
        val measured = measureText(text, style, height)
        val origin = requireNotNull(
            IosOsdLayout.channelNameOrigin(text, width.toDouble(), measured.first, style.position)
        )
        return renderTextLayer(text, style, height, measured, origin)
    }

    private fun renderTextLayer(
        text: String,
        style: OsdLayer,
        viewportHeight: Int,
        measured: Pair<Double, Double>,
        origin: IosOsdPoint,
    ): RenderedLayer = autoreleasepool {
        val padding = 3.0
        val bitmapWidth = ceil(measured.first + padding * 2.0).toInt().coerceAtLeast(1)
        val bitmapHeight = ceil(measured.second + padding * 2.0).toInt().coerceAtLeast(1)
        UIGraphicsBeginImageContextWithOptions(
            platform.CoreGraphics.CGSizeMake(bitmapWidth.toDouble(), bitmapHeight.toDouble()), false, 1.0
        )
        try {
            NSString.create(string = text).drawAtPoint(
                platform.CoreGraphics.CGPointMake(padding, padding),
                withAttributes = textAttributes(style, viewportHeight, 1.0),
            )
            val cgImage = requireNotNull(UIGraphicsGetImageFromCurrentImageContext()?.CGImage)
            val localImage = CIImage.imageWithCGImage(cgImage)
            // UIKit uses top-left coordinates; Core Image places the local bitmap from its bottom-left.
            val translated = localImage.imageByApplyingTransform(
                CGAffineTransformMakeTranslation(
                    origin.x - padding,
                    viewportHeight - origin.y + padding - bitmapHeight,
                )
            )
            RenderedLayer(
                image = translated,
                bounds = IosOsdVisibleBounds(
                    origin.x,
                    origin.y,
                    origin.x + measured.first,
                    origin.y + measured.second,
                ),
                backingPixelArea = bitmapWidth * bitmapHeight,
            )
        } finally {
            UIGraphicsEndImageContext()
        }
    }

    private fun renderWatermark(text: String, style: OsdLayer, width: Int, height: Int): RenderedLayer {
        var tileCount = 0
        return renderFullFrame(width, height) {
            val measured = measureText(text, style, height)
            val layout = IosOsdLayout.watermarkLayout(
                text = text,
                viewportWidth = width.toDouble(),
                viewportHeight = height.toDouble(),
                textWidth = measured.first,
                textHeight = measured.second,
            )
            val context = requireNotNull(UIGraphicsGetCurrentContext())
            val nsText = NSString.create(string = text)
            val attributes = textAttributes(style, height, layout.alpha)
            val radians = layout.angleDegrees * PI / 180.0
            for (tile in layout.tiles) {
                CGContextSaveGState(context)
                CGContextTranslateCTM(context, tile.center.x, tile.center.y)
                CGContextConcatCTM(context, CGAffineTransformMakeRotation(radians))
                nsText.drawAtPoint(
                    platform.CoreGraphics.CGPointMake(-measured.first / 2.0, -measured.second / 2.0),
                    withAttributes = attributes,
                )
                CGContextRestoreGState(context)
                tileCount++
            }
            IosOsdVisibleBounds(0.0, 0.0, width.toDouble(), height.toDouble())
        }.copy(watermarkTileCount = tileCount, maximumWatermarkAlpha = IosOsdLayout.WATERMARK_ALPHA)
    }

    private inline fun renderFullFrame(
        width: Int,
        height: Int,
        crossinline draw: () -> IosOsdVisibleBounds,
    ): RenderedLayer = autoreleasepool {
        UIGraphicsBeginImageContextWithOptions(
            platform.CoreGraphics.CGSizeMake(width.toDouble(), height.toDouble()), false, 1.0
        )
        try {
            val bounds = draw()
            val cgImage = requireNotNull(UIGraphicsGetImageFromCurrentImageContext()?.CGImage)
            RenderedLayer(
                image = CIImage.imageWithCGImage(cgImage),
                bounds = bounds,
                backingPixelArea = width * height,
            )
        } finally {
            UIGraphicsEndImageContext()
        }
    }

    private fun measureText(text: String, style: OsdLayer, viewportHeight: Int): Pair<Double, Double> {
        val size = NSString.create(string = text).sizeWithAttributes(
            textAttributes(style, viewportHeight, 1.0)
        )
        return size.useContents { width to height }
    }

    private fun textAttributes(
        style: OsdLayer,
        viewportHeight: Int,
        alpha: Double,
    ): Map<Any?, *> {
        val fill = IosOsdLayout.parseFillColor(style.fillColor)
        val outline = IosOsdLayout.parseOutlineColor(style.outlineColor)
        val attributes = mapOf<Any?, Any?>(
            NSFontAttributeName to UIFont.monospacedSystemFontOfSize(
                IosOsdLayout.pixelSize(style.size, viewportHeight).toDouble(),
                UIFontWeightRegular,
            ),
            NSForegroundColorAttributeName to color(fill, alpha),
            NSStrokeColorAttributeName to color(outline, alpha),
            NSStrokeWidthAttributeName to -3.0,
        )
        return attributes
    }

    private fun color(value: IosOsdColor, alpha: Double): UIColor = UIColor.colorWithRed(
        red = value.red / 255.0,
        green = value.green / 255.0,
        blue = value.blue / 255.0,
        alpha = alpha,
    )

    private data class PixelInspection(
        val hasVisible: Boolean,
        val hasTransparent: Boolean,
        val hasWhiteFill: Boolean,
        val hasBlackOutline: Boolean,
    )

    private fun inspectPixels(image: platform.CoreGraphics.CGImageRef): PixelInspection {
        val provider = CGImageGetDataProvider(image) ?: return PixelInspection(false, false, false, false)
        val data = CGDataProviderCopyData(provider) ?: return PixelInspection(false, false, false, false)
        return try {
            val bytes = CFDataGetBytePtr(data) ?: return PixelInspection(false, false, false, false)
            val length = CFDataGetLength(data).toInt()
            val pixelCount = (CGImageGetWidth(image) * CGImageGetHeight(image)).toInt()
            val bytesPerRow = CGImageGetBytesPerRow(image).toInt()
            val width = CGImageGetWidth(image).toInt()
            var visible = false
            var transparent = false
            var white = false
            var black = false
            for (pixel in 0 until pixelCount) {
                val row = pixel / width
                val column = pixel % width
                val offset = row * bytesPerRow + column * 4
                if (offset + 3 >= length) break
                // UIGraphics produces premultiplied BGRA on iOS little-endian targets.
                val blue = bytes[offset].toInt()
                val green = bytes[offset + 1].toInt()
                val red = bytes[offset + 2].toInt()
                val alpha = bytes[offset + 3].toInt()
                visible = visible || alpha > 0
                transparent = transparent || alpha == 0
                white = white || (alpha > 200 && red > 230 && green > 230 && blue > 230)
                black = black || (alpha > 80 && red < 25 && green < 25 && blue < 25)
            }
            PixelInspection(visible, transparent, white, black)
        } finally {
            CFRelease(data)
        }
    }

    private fun unionBounds(bounds: List<IosOsdVisibleBounds>): IosOsdVisibleBounds = IosOsdVisibleBounds(
        minX = bounds.minOf { it.minX },
        minY = bounds.minOf { it.minY },
        maxX = bounds.maxOf { it.maxX },
        maxY = bounds.maxOf { it.maxY },
    )
}
