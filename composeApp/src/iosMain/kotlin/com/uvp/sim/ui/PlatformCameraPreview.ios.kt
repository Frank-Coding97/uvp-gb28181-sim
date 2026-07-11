package com.uvp.sim.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.interop.UIKitView
import com.uvp.sim.api.LogLevel
import com.uvp.sim.api.LogTag
import com.uvp.sim.camera.IosCameraController
import com.uvp.sim.config.OsdConfig
import com.uvp.sim.config.OsdLayer
import com.uvp.sim.osd.IosOsdColor
import com.uvp.sim.osd.IosOsdLayout
import com.uvp.sim.osd.OsdSnapshot
import com.uvp.sim.osd.OsdTickerSource
import com.uvp.sim.observability.SystemLogger
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import platform.AVFoundation.AVCaptureVideoOrientationPortrait
import platform.AVFoundation.AVCaptureVideoPreviewLayer
import platform.AVFoundation.AVLayerVideoGravityResizeAspectFill
import platform.CoreGraphics.CGAffineTransformMakeRotation
import platform.CoreGraphics.CGContextConcatCTM
import platform.CoreGraphics.CGContextRestoreGState
import platform.CoreGraphics.CGContextSaveGState
import platform.CoreGraphics.CGContextScaleCTM
import platform.CoreGraphics.CGContextTranslateCTM
import platform.CoreGraphics.CGPointMake
import platform.CoreGraphics.CGRect
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSString
import platform.Foundation.create
import platform.UIKit.NSFontAttributeName
import platform.UIKit.NSForegroundColorAttributeName
import platform.UIKit.NSStrokeColorAttributeName
import platform.UIKit.NSStrokeWidthAttributeName
import platform.UIKit.UIColor
import platform.UIKit.UIFont
import platform.UIKit.UIFontWeightRegular
import platform.UIKit.UIGraphicsGetCurrentContext
import platform.UIKit.UIView
import platform.UIKit.drawAtPoint
import platform.UIKit.sizeWithAttributes
import kotlin.math.PI
import kotlin.math.max
import kotlin.time.Clock

/** Mirrors AVCaptureVideoPreviewLayer.resizeAspectFill for encoding-space OSD coordinates. */
internal data class PreviewAspectFillMapping(
    val scale: Double,
    val offsetX: Double,
    val offsetY: Double,
    val visibleSourceWidth: Double,
    val visibleSourceHeight: Double,
) {
    fun mapX(sourceX: Double): Double = offsetX + sourceX * scale

    fun mapY(sourceY: Double): Double = offsetY + sourceY * scale

    companion object {
        fun calculate(
            sourceWidth: Double,
            sourceHeight: Double,
            viewWidth: Double,
            viewHeight: Double,
        ): PreviewAspectFillMapping {
            if (sourceWidth <= 0.0 || sourceHeight <= 0.0 || viewWidth <= 0.0 || viewHeight <= 0.0) {
                return PreviewAspectFillMapping(1.0, 0.0, 0.0, 0.0, 0.0)
            }
            val scale = max(viewWidth / sourceWidth, viewHeight / sourceHeight)
            return PreviewAspectFillMapping(
                scale = scale,
                offsetX = (viewWidth - sourceWidth * scale) / 2.0,
                offsetY = (viewHeight - sourceHeight * scale) / 2.0,
                visibleSourceWidth = viewWidth / scale,
                visibleSourceHeight = viewHeight / scale,
            )
        }
    }
}

/**
 * iOS 相机预览 — 单例 UIView 宿主模型 (2026-07-07 重构)。
 *
 * 架构:preview UIView + AVCaptureVideoPreviewLayer 是 pipeline 的一部分,不是 UI 层的。
 * 生命周期跟 [IosCameraController.session] 对齐,不跟 Compose composable 走。
 *
 * 收益 (对比旧模型 "每次 factory 新建 container + update 挂 session"):
 * - 切 tab 单次成本:~300ms → ~0ms (UIKit 单亲约束下的 addSubview 转移是 O(1))
 * - session 首次挂 preview layer 的 ~300ms 只付一次,不再每次切 tab 重付
 * - session 上永远只挂一个 preview layer → 从根源消灭"连续切 3 次 app 卡死"
 *
 * 关键行为:
 * - [IosCameraPreviewHost.containerView] 是模块级单例,进程内唯一实例
 * - Session 挂载在 host init 里订阅 controller.session 一次到底,不依赖 UIKitView 生命周期
 * - [PlatformCameraPreview] 的 UIKitView.factory 每次返回同一 containerView;UIKit 单亲约束
 *   会自动把它从旧 hosting view detach、attach 到新的,preview layer 和 session 不动
 */
@OptIn(ExperimentalForeignApi::class)
internal object IosCameraPreviewHost {
    /**
     * Preview UIView 单例。首次访问触发 [IosCameraPreviewHost] object 装载,
     * init 里启动 session collect 协程。
     */
    val containerView: CameraPreviewContainerView = CameraPreviewContainerView().apply {
        previewLayer.videoGravity = AVLayerVideoGravityResizeAspectFill
    }

    // AVCaptureVideoPreviewLayer.session setter 要求主线程;collect 直接跑在 Main。
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    init {
        scope.launch {
            IosCameraController.session.collect { session ->
                containerView.installOsdConfigFlow(IosCameraController.previewOsdConfigFlow)
                if (containerView.previewLayer.session !== session) {
                    containerView.previewLayer.session = session
                    // 2026-07-09:sensor 原生 LandscapeRight,用户手机竖着看需要 preview
                    // layer 单独 rotate 到 Portrait。这个 connection 跟 sample delegate 的
                    // output connection 是独立的两条路,不影响推流方向(1280x720 landscape)。
                    containerView.previewLayer.connection?.let { conn ->
                        if (conn.isVideoOrientationSupported()) {
                            conn.setVideoOrientation(AVCaptureVideoOrientationPortrait)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun PlatformCameraPreview(modifier: Modifier) {
    val container = IosCameraPreviewHost.containerView
    UIKitView(
        factory = {
            // UIKit 单亲约束:一个 UIView 只能有一个 superview。旧 hosting view 若还在,
            // 显式让出;新 hosting view 会通过 addSubview 自动接管。
            container.removeFromSuperview()
            container.userInteractionEnabled = false
            container
        },
        update = {
            it.installOsdConfigFlow(IosCameraController.previewOsdConfigFlow)
        },
        modifier = modifier,
        interactive = false,
        // 无 onRelease:containerView 是单例,不能被 dispose;session 挂载不受 UIKitView 生命周期影响
    )
}

/**
 * 自定义 UIView:持有 [AVCaptureVideoPreviewLayer] 作为唯一 sublayer,
 * 在 [layoutSubviews] 里同步 layer frame = bounds,确保旋转/resize 正确。
 */
@OptIn(ExperimentalForeignApi::class)
internal class CameraPreviewContainerView(
    osdConfigFlow: StateFlow<OsdConfig> = IosCameraController.previewOsdConfigFlow,
) : UIView(frame = CGRectMake(0.0, 0.0, 0.0, 0.0)) {
    val previewLayer = AVCaptureVideoPreviewLayer()
    val osdOverlay = CameraPreviewOsdOverlayView(osdConfigFlow)

    init {
        userInteractionEnabled = false
        layer.addSublayer(previewLayer)
        addSubview(osdOverlay)
    }

    fun installOsdConfigFlow(flow: StateFlow<OsdConfig>) {
        osdOverlay.installConfigFlow(flow)
    }

    override fun layoutSubviews() {
        super.layoutSubviews()
        previewLayer.setFrame(bounds)
        osdOverlay.setFrame(bounds)
        osdOverlay.setNeedsDisplay()
    }
}

/** Transparent, non-interactive OSD surface above the process-wide preview layer. */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal class CameraPreviewOsdOverlayView(
    configFlow: StateFlow<OsdConfig>,
) : UIView(frame = CGRectMake(0.0, 0.0, 0.0, 0.0)) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var refreshJob: Job? = null
    private var configFlow: StateFlow<OsdConfig> = configFlow
    private var tickerSource = OsdTickerSource(configFlow)
    private var latestConfig = configFlow.value
    private var lastFallbackLogAtMs = -1L

    internal var latestSnapshotForTest: OsdSnapshot = OsdSnapshot(null, null, null)
        private set

    internal val activeRefreshJobsForTest: Int
        get() = if (refreshJob?.isActive == true) 1 else 0

    init {
        userInteractionEnabled = false
        opaque = false
        backgroundColor = UIColor.clearColor
        hidden = true
    }

    fun installConfigFlow(flow: StateFlow<OsdConfig>) {
        if (configFlow === flow) return
        configFlow = flow
        tickerSource = OsdTickerSource(flow)
        refresh()
    }

    override fun didMoveToWindow() {
        super.didMoveToWindow()
        if (window == null) stopRefreshing() else startRefreshing()
    }

    override fun drawRect(rect: CValue<CGRect>) {
        super.drawRect(rect)
        if (hidden) return
        runCatching { drawSnapshot(latestSnapshotForTest, latestConfig) }
            .onFailure { logFallback("draw") }
    }

    internal fun refreshForTest() = refresh()

    internal fun startRefreshingForTest() = startRefreshing()

    internal fun stopRefreshingForTest() = stopRefreshing()

    private fun startRefreshing() {
        if (refreshJob?.isActive == true) return
        refresh()
        refreshJob = scope.launch {
            while (isActive) {
                delay(250)
                refresh()
            }
        }
    }

    private fun stopRefreshing() {
        refreshJob?.cancel()
        refreshJob = null
    }

    private fun refresh() {
        runCatching {
            latestConfig = configFlow.value
            latestSnapshotForTest = tickerSource.snapshot()
            hidden = latestSnapshotForTest.run {
                timestamp == null && channelName == null && watermark == null
            }
            setNeedsDisplay()
        }.onFailure {
            hidden = true
            setNeedsDisplay()
            logFallback("refresh")
        }
    }

    private fun logFallback(stage: String) {
        val now = Clock.System.now().toEpochMilliseconds()
        if (lastFallbackLogAtMs >= 0L && now - lastFallbackLogAtMs < 10_000L) return
        lastFallbackLogAtMs = now
        SystemLogger.emit(
            LogLevel.Warning,
            LogTag.Media,
            "IOS_OSD_PREVIEW_FALLBACK stage=$stage",
        )
    }

    private fun drawSnapshot(snapshot: OsdSnapshot, config: OsdConfig) {
        val viewport = bounds.useContents { size.width to size.height }
        if (viewport.first <= 0.0 || viewport.second <= 0.0) return
        val outputSize = IosCameraController.previewOutputSize()
        val mapping = PreviewAspectFillMapping.calculate(
            sourceWidth = outputSize.first.toDouble(),
            sourceHeight = outputSize.second.toDouble(),
            viewWidth = viewport.first,
            viewHeight = viewport.second,
        )
        val context = UIGraphicsGetCurrentContext() ?: return
        // PreviewLayer is portrait-oriented independently from the landscape encoded canvas.
        // Keep anchors on the visible view edges, but preserve encoded pixel size/density via scale.
        CGContextSaveGState(context)
        CGContextScaleCTM(context, mapping.scale, mapping.scale)
        try {
            drawVisibleCanvas(
                snapshot = snapshot,
                config = config,
                encodedHeight = outputSize.second.toDouble(),
                visibleWidth = mapping.visibleSourceWidth,
                visibleHeight = mapping.visibleSourceHeight,
            )
        } finally {
            CGContextRestoreGState(context)
        }
    }

    private fun drawVisibleCanvas(
        snapshot: OsdSnapshot,
        config: OsdConfig,
        encodedHeight: Double,
        visibleWidth: Double,
        visibleHeight: Double,
    ) {
        snapshot.timestamp?.let { text ->
            val origin = IosOsdLayout.timestampOrigin(text, config.timestamp.position) ?: return@let
            drawText(text, config.timestamp, encodedHeight, origin.x, origin.y)
        }
        snapshot.channelName?.let { text ->
            val measured = measureText(text, config.channelName, encodedHeight)
            val origin = IosOsdLayout.channelNameOrigin(
                text,
                visibleWidth,
                measured.first,
                config.channelName.position,
            ) ?: return@let
            drawText(text, config.channelName, encodedHeight, origin.x, origin.y)
        }
        snapshot.watermark?.let { text ->
            drawWatermark(
                text = text,
                style = config.watermark,
                width = visibleWidth,
                height = visibleHeight,
                layoutHeight = encodedHeight,
            )
        }
    }

    private fun drawWatermark(
        text: String,
        style: OsdLayer,
        width: Double,
        height: Double,
        layoutHeight: Double,
    ) {
        val measured = measureText(text, style, layoutHeight)
        val layout = IosOsdLayout.watermarkLayout(text, width, height, measured.first, measured.second)
        val context = UIGraphicsGetCurrentContext() ?: return
        val nsText = NSString.create(string = text)
        val attributes = textAttributes(style, layoutHeight, layout.alpha)
        val radians = layout.angleDegrees * PI / 180.0
        for (tile in layout.tiles) {
            CGContextSaveGState(context)
            CGContextTranslateCTM(context, tile.center.x, tile.center.y)
            CGContextConcatCTM(context, CGAffineTransformMakeRotation(radians))
            nsText.drawAtPoint(
                CGPointMake(-measured.first / 2.0, -measured.second / 2.0),
                withAttributes = attributes,
            )
            CGContextRestoreGState(context)
        }
    }

    private fun drawText(
        text: String,
        style: OsdLayer,
        viewportHeight: Double,
        x: Double,
        y: Double,
    ) {
        NSString.create(string = text).drawAtPoint(
            CGPointMake(x, y),
            withAttributes = textAttributes(style, viewportHeight, 1.0),
        )
    }

    private fun measureText(text: String, style: OsdLayer, viewportHeight: Double): Pair<Double, Double> {
        val size = NSString.create(string = text).sizeWithAttributes(
            textAttributes(style, viewportHeight, 1.0),
        )
        return size.useContents { width to height }
    }

    private fun textAttributes(
        style: OsdLayer,
        viewportHeight: Double,
        alpha: Double,
    ): Map<Any?, *> = mapOf<Any?, Any?>(
        NSFontAttributeName to UIFont.monospacedSystemFontOfSize(
            IosOsdLayout.pixelSize(style.size, viewportHeight.toInt()).toDouble(),
            UIFontWeightRegular,
        ),
        NSForegroundColorAttributeName to color(IosOsdLayout.parseFillColor(style.fillColor), alpha),
        NSStrokeColorAttributeName to color(IosOsdLayout.parseOutlineColor(style.outlineColor), alpha),
        NSStrokeWidthAttributeName to -3.0,
    )

    private fun color(value: IosOsdColor, alpha: Double): UIColor = UIColor.colorWithRed(
        red = value.red / 255.0,
        green = value.green / 255.0,
        blue = value.blue / 255.0,
        alpha = alpha,
    )
}
