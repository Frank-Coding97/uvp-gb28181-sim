package com.uvp.sim.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Android 扫码 actual — CameraX `Preview` + `ImageAnalysis` + ZXing 解码。
 *
 * 关键设计:
 * - `STRATEGY_KEEP_ONLY_LATEST`:解码比出帧慢时丢旧帧,不排队堆积
 * - [AtomicBoolean] 闸门 + `clearAnalyzer()`:解码成功**立即**停止分析,
 *   保证 `onResult` 只回调一次(用例 8.5)。analyzer 在 CameraX 线程池上跑,
 *   闸门必须是原子的,不能用普通 Boolean
 * - `DisposableEffect` 退出即 `unbind` 本页 use case + 关自建 executor(用例 8.6)。
 *   只 unbind 自己绑的两个 use case,**不调 `unbindAll()`** —— 那会把
 *   AndroidCameraStreamer 的推流 use case 一起干掉
 *
 * 权限:进入前由调用方(QrScanScreen)负责;这里只做兜底检查,没权限直接
 * 走 [onError] 文案而不是崩在 CameraX 内部。
 */
@Composable
actual fun ScanQrCode(
    onResult: (String) -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val granted = remember(context) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
    }

    if (!granted) {
        DisposableEffect(Unit) {
            onError(CAMERA_PERMISSION_DENIED)
            onDispose { }
        }
        return
    }

    val delivered = remember { AtomicBoolean(false) }
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    val executor: ExecutorService = remember { Executors.newSingleThreadExecutor() }

    AndroidView(modifier = modifier, factory = { previewView })

    DisposableEffect(previewView, lifecycleOwner) {
        var provider: ProcessCameraProvider? = null
        var preview: Preview? = null
        var analysis: ImageAnalysis? = null

        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val cameraProvider = runCatching { future.get() }.getOrNull()
            if (cameraProvider == null) {
                onError(CAMERA_OPEN_FAILED)
                return@addListener
            }
            provider = cameraProvider
            val previewUseCase = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val analysisUseCase = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysisUseCase.setAnalyzer(executor) { image ->
                analyzeFrame(
                    image = image,
                    delivered = delivered,
                    onDecoded = { text ->
                        // 先摘 analyzer 断掉后续帧,再回调 —— 顺序反过来的话
                        // onResult 期间还可能有一帧正在解码。
                        analysisUseCase.clearAnalyzer()
                        onResult(text)
                    },
                )
            }
            preview = previewUseCase
            analysis = analysisUseCase
            runCatching {
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    previewUseCase,
                    analysisUseCase,
                )
            }.onFailure { onError(CAMERA_OPEN_FAILED) }
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            analysis?.clearAnalyzer()
            // 只 unbind 自己的两个 use case:unbindAll() 会连带解绑推流 /
            // 录像的 use case(AndroidCameraStreamer 同一 provider 单例)。
            val own = listOfNotNull(preview, analysis).toTypedArray()
            if (own.isNotEmpty()) runCatching { provider?.unbind(*own) }
            executor.shutdown()
        }
    }
}

/**
 * 单帧解码。YUV_420_888 的 Y 平面就是灰度图,ZXing 的
 * [PlanarYUVLuminanceSource] 直接吃它,无需 RGB 转换。
 *
 * [delivered] 闸门保证即使有并发帧在飞,`onDecoded` 也只会走一次。
 */
private fun analyzeFrame(
    image: ImageProxy,
    delivered: AtomicBoolean,
    onDecoded: (String) -> Unit,
) {
    try {
        if (delivered.get()) return
        val text = decodeQr(image) ?: return
        if (delivered.compareAndSet(false, true)) onDecoded(text)
    } finally {
        image.close()
    }
}

private val qrHints = mapOf<DecodeHintType, Any>(DecodeHintType.TRY_HARDER to true)

private fun decodeQr(image: ImageProxy): String? = runCatching {
    val plane = image.planes[0]
    val buffer = plane.buffer
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)
    val source = PlanarYUVLuminanceSource(
        bytes,
        plane.rowStride,
        image.height,
        0,
        0,
        image.width.coerceAtMost(plane.rowStride),
        image.height,
        false,
    )
    // QRCodeReader 不是线程安全的,但 analyzer 串行跑在单线程 executor 上,
    // 每帧新建成本也可以忽略 —— 用局部实例最省心。
    // 未识别到码时 ZXing 抛 NotFoundException,由 runCatching 吞掉返回 null。
    val result = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source)), qrHints)
    result.text?.takeIf { it.isNotBlank() }
}.getOrNull()

private const val CAMERA_PERMISSION_DENIED = "需要相机权限才能扫码"
private const val CAMERA_OPEN_FAILED = "相机打不开,请退出重试"
