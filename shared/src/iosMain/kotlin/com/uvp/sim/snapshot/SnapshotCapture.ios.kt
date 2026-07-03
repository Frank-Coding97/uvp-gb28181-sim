package com.uvp.sim.snapshot

import com.uvp.sim.api.LogTag
import com.uvp.sim.camera.IosCameraController
import com.uvp.sim.observability.LogLevel
import com.uvp.sim.observability.SystemLogger
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import platform.CoreFoundation.CFRelease
import platform.CoreImage.CIContext
import platform.CoreImage.CIImage
import platform.CoreImage.createCGImage
import platform.CoreVideo.CVImageBufferRef
import platform.CoreVideo.CVPixelBufferGetHeight
import platform.CoreVideo.CVPixelBufferGetWidth
import platform.Foundation.NSData
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation

/**
 * iOS 抓拍实现 — 从 [com.uvp.sim.camera.IosCameraController] 拿最新一帧 CVPixelBuffer,
 * 走 CIImage → CGImage → UIImage → JPEG 编码。
 *
 * 为什么不用 AVCapturePhotoOutput:那需要额外挂 output 到 AVCaptureSession,
 * 会跟现有 AVCaptureVideoDataOutput 抢配置,且引入闪光灯/快门声等副作用。
 * 直接复用编码线的最新一帧,零成本、无副作用,画质对抓拍够用(JPEG 0.8)。
 *
 * 失败路径(全部返 null):
 *   - streamer 未 publish(未开流 / 已 release)
 *   - latestFramePixelBuffer 为 null(首帧未到)
 *   - CIContext.createCGImage 失败(极罕见)
 *   - UIImageJPEGRepresentation 失败(数据异常)
 *
 * 所有失败都走 SystemLogger warn,不抛异常给上层。
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
actual class SnapshotCapture actual constructor() {

    private val ciContext: CIContext? by lazy { CIContext.contextWithOptions(null) }

    actual suspend fun takeJpeg(): ByteArray? {
        // v1.3-A T-P6-1:直接从 IosCameraController 取最新一帧(替换 IosSnapshotSourceHolder path)
        val pixelBuffer: CVImageBufferRef = IosCameraController.latestFramePixelBuffer() ?: run {
            SystemLogger.emit(
                level = LogLevel.Warning,
                tag = LogTag.Media,
                message = "SnapshotCapture.ios: latestFramePixelBuffer 为 null(preview 未启 / 首帧未到)"
            )
            return null
        }

        // 从这里开始:pixelBuffer 由本方法负责 CFRelease
        return try {
            encodeToJpeg(pixelBuffer)
        } finally {
            CFRelease(pixelBuffer)
        }
    }

    /**
     * CVPixelBuffer → JPEG bytes,不释放传入的 pixelBuffer(caller 责任)。
     */
    private fun encodeToJpeg(pixelBuffer: CVImageBufferRef): ByteArray? {
        val width = CVPixelBufferGetWidth(pixelBuffer).toDouble()
        val height = CVPixelBufferGetHeight(pixelBuffer).toDouble()
        if (width <= 0 || height <= 0) {
            SystemLogger.emit(
                level = LogLevel.Warning,
                tag = LogTag.Media,
                message = "SnapshotCapture.ios: pixel buffer 尺寸异常 ${width}x${height}"
            )
            return null
        }

        val ctx = ciContext ?: run {
            SystemLogger.emit(
                level = LogLevel.Warning,
                tag = LogTag.Media,
                message = "SnapshotCapture.ios: CIContext.contextWithOptions 返 null(初始化异常)"
            )
            return null
        }

        val ciImage: CIImage = CIImage.imageWithCVPixelBuffer(pixelBuffer)

        // K/N binding: `createCGImage:fromRect:` 映射为顶层扩展函数 platform.CoreImage.createCGImage
        // 直接用 ciImage.extent 作 fromRect,避免手工拼 CGRect 尺寸偏差。
        val cgImage = ctx.createCGImage(ciImage, ciImage.extent)
        if (cgImage == null) {
            SystemLogger.emit(
                level = LogLevel.Warning,
                tag = LogTag.Media,
                message = "SnapshotCapture.ios: CIContext.createCGImage 返 null"
            )
            return null
        }

        val uiImage = UIImage.imageWithCGImage(cgImage)
        val nsData: NSData = UIImageJPEGRepresentation(uiImage, compressionQuality = 0.8) ?: run {
            SystemLogger.emit(
                level = LogLevel.Warning,
                tag = LogTag.Media,
                message = "SnapshotCapture.ios: UIImageJPEGRepresentation 返 null"
            )
            return null
        }

        return nsData.toByteArray()
    }

    private fun NSData.toByteArray(): ByteArray {
        val len = this.length.toInt()
        if (len == 0) return ByteArray(0)
        val bytesPtr = this.bytes ?: return ByteArray(0)
        return bytesPtr.reinterpret<ByteVar>().readBytes(len)
    }
}
