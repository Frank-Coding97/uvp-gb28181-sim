package com.uvp.sim.camera

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFoundation.AVCaptureConnection
import platform.AVFoundation.AVCaptureOutput
import platform.AVFoundation.AVCaptureVideoDataOutputSampleBufferDelegateProtocol
import platform.CoreMedia.CMSampleBufferRef
import platform.darwin.NSObject

/**
 * `AVCaptureVideoDataOutputSampleBufferDelegate` NSObject 子类。
 *
 * K/N 要求 ObjC delegate 是 `NSObject` 子类并标注 `@ExportObjCClass` 让 runtime 注册。
 * 每帧 [captureOutput] 触发 → forward 到 [onSample] lambda。
 *
 * v1.3-A T-P6-1:从 v1.2 IosCameraStreamer.kt 抽出的独立文件(IosCameraStreamer 已删)。
 * IosCameraController 内部用同一个 delegate 类构造 delegate instance。
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
@kotlinx.cinterop.ExportObjCClass
internal class CameraSampleDelegate(
    private val onSample: (CMSampleBufferRef) -> Unit,
) : NSObject(), AVCaptureVideoDataOutputSampleBufferDelegateProtocol {
    override fun captureOutput(
        output: AVCaptureOutput,
        didOutputSampleBuffer: CMSampleBufferRef?,
        fromConnection: AVCaptureConnection,
    ) {
        didOutputSampleBuffer?.let(onSample)
    }
}
