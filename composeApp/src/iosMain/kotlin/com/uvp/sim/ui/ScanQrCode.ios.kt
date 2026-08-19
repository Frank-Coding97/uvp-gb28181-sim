package com.uvp.sim.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.interop.UIKitView
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFoundation.AVAuthorizationStatusAuthorized
import platform.AVFoundation.AVCaptureConnection
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVCaptureDeviceInput
import platform.AVFoundation.AVCaptureMetadataOutput
import platform.AVFoundation.AVCaptureMetadataOutputObjectsDelegateProtocol
import platform.AVFoundation.AVCaptureOutput
import platform.AVFoundation.AVCaptureSession
import platform.AVFoundation.AVCaptureSessionPresetHigh
import platform.AVFoundation.AVCaptureVideoPreviewLayer
import platform.AVFoundation.AVLayerVideoGravityResizeAspectFill
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.AVMetadataMachineReadableCodeObject
import platform.AVFoundation.AVMetadataObjectTypeQRCode
import platform.AVFoundation.authorizationStatusForMediaType
import platform.CoreGraphics.CGRectMake
import platform.UIKit.UIView
import platform.darwin.NSObject
import platform.darwin.dispatch_get_main_queue

/**
 * iOS 扫码 actual — 独立 [AVCaptureSession] + [AVCaptureMetadataOutput]。
 *
 * 跟 [PlatformCameraPreview] 的进程级单例模型**故意不同**:扫码是短时页面,
 * session 随页面建、随页面释放(plan §5.6 / 用例 8.6)。复用单例会跟推流
 * session 争抢设备,而且退出后还得手动收尾。
 *
 * 解码用系统的 metadata output(不引 ZXing):AVFoundation 自带 QR 识别,
 * 精度和功耗都比自己在 Kotlin 里跑解码好。
 *
 * `hasDelivered` 闸门保证只回调一次 —— metadata delegate 对着同一张码会
 * 持续出对象(用例 8.5)。
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
@Composable
actual fun ScanQrCode(
    onResult: (String) -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier,
) {
    val authorized = remember {
        AVCaptureDevice.authorizationStatusForMediaType(AVMediaTypeVideo) ==
            AVAuthorizationStatusAuthorized
    }
    if (!authorized) {
        DisposableEffect(Unit) {
            onError(CAMERA_PERMISSION_DENIED)
            onDispose { }
        }
        return
    }

    val controller = remember { IosQrScanController() }

    UIKitView(
        factory = { controller.view },
        modifier = modifier,
        interactive = false,
    )

    DisposableEffect(controller) {
        val failure = controller.start(onResult = onResult)
        if (failure != null) onError(failure)
        onDispose { controller.stop() }
    }
}

/** iOS 暂不改变既有扫码反馈行为。 */
actual fun playQrScanSuccessSound() = Unit

/**
 * 扫码 session 的持有者。UIKitView.factory 只交出 [view],start/stop 由
 * DisposableEffect 驱动 —— 保证"退出页面即释放相机"。
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private class IosQrScanController {

    val view: QrScanPreviewView = QrScanPreviewView()

    private var session: AVCaptureSession? = null
    private var delegate: QrMetadataDelegate? = null
    private var delivered = false

    /** 返回 null 表示启动成功;否则返回给用户看的错误文案。 */
    fun start(onResult: (String) -> Unit): String? {
        if (session != null) return null
        val capture = AVCaptureSession()
        capture.sessionPreset = AVCaptureSessionPresetHigh

        val device = AVCaptureDevice.defaultDeviceWithMediaType(AVMediaTypeVideo)
            ?: return CAMERA_OPEN_FAILED
        val input = AVCaptureDeviceInput.deviceInputWithDevice(device, null)
            as? AVCaptureDeviceInput
            ?: return CAMERA_OPEN_FAILED
        if (!capture.canAddInput(input)) return CAMERA_OPEN_FAILED
        capture.addInput(input)

        val output = AVCaptureMetadataOutput()
        if (!capture.canAddOutput(output)) return CAMERA_OPEN_FAILED
        capture.addOutput(output)
        val handler = QrMetadataDelegate { text ->
            // 闸门在主队列上读写(delegate 队列 = main),不需要额外同步。
            if (!delivered) {
                delivered = true
                onResult(text)
            }
        }
        output.setMetadataObjectsDelegate(handler, dispatch_get_main_queue())
        // availableMetadataObjectTypes 只有在 addOutput 之后才填好,顺序不能反。
        output.metadataObjectTypes = listOf(AVMetadataObjectTypeQRCode)
        delegate = handler

        view.previewLayer.session = capture
        view.previewLayer.videoGravity = AVLayerVideoGravityResizeAspectFill

        session = capture
        capture.startRunning()
        return null
    }

    fun stop() {
        session?.let { capture ->
            if (capture.isRunning()) capture.stopRunning()
            capture.inputs.filterIsInstance<platform.AVFoundation.AVCaptureInput>()
                .forEach { capture.removeInput(it) }
            capture.outputs.filterIsInstance<AVCaptureOutput>()
                .forEach { capture.removeOutput(it) }
        }
        view.previewLayer.session = null
        session = null
        delegate = null
    }
}

/** 只承载 preview layer 的 UIView,layout 时同步 layer frame。 */
@OptIn(ExperimentalForeignApi::class)
private class QrScanPreviewView : UIView(frame = CGRectMake(0.0, 0.0, 0.0, 0.0)) {
    val previewLayer = AVCaptureVideoPreviewLayer()

    init {
        userInteractionEnabled = false
        layer.addSublayer(previewLayer)
    }

    override fun layoutSubviews() {
        super.layoutSubviews()
        previewLayer.setFrame(bounds)
    }
}

/**
 * `AVCaptureMetadataOutputObjectsDelegate` 的 NSObject 子类。
 * K/N 要求 ObjC delegate 必须是 NSObject 子类并 `@ExportObjCClass` 注册,
 * 形状照 `CameraSampleDelegate.kt`。
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
@kotlinx.cinterop.ExportObjCClass
private class QrMetadataDelegate(
    private val onText: (String) -> Unit,
) : NSObject(), AVCaptureMetadataOutputObjectsDelegateProtocol {

    override fun captureOutput(
        output: AVCaptureOutput,
        didOutputMetadataObjects: List<*>,
        fromConnection: AVCaptureConnection,
    ) {
        didOutputMetadataObjects
            .filterIsInstance<AVMetadataMachineReadableCodeObject>()
            .firstNotNullOfOrNull { it.stringValue?.takeIf { text -> text.isNotBlank() } }
            ?.let(onText)
    }
}

private const val CAMERA_PERMISSION_DENIED = "需要相机权限才能扫码"
private const val CAMERA_OPEN_FAILED = "相机打不开,请退出重试"
