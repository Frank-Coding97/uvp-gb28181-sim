package com.uvp.sim.camera

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.CoreMedia.kCMVideoCodecType_HEVC
import platform.VideoToolbox.VTCompressionSessionCreate
import platform.VideoToolbox.VTCompressionSessionInvalidate
import platform.VideoToolbox.VTCompressionSessionRefVar

/**
 * T-B1-6:HEVC 硬编能力探测。
 *
 * 独立 object 便于 test 覆盖(通过 `IosCameraController.overrideHevcHwEncodeSupportedForTest`)。
 * 探测策略:创建一个 320x240 的 VT HEVC session,create 返回值 == 0 视为支持,立即
 * Invalidate 释放。所有异常 → false(caller 的 runCatching 兜底)。
 *
 * 探测开销 <100ms,App 冷启一次,不阻塞主流程(caller 从 background thread 触发)。
 */
@OptIn(ExperimentalForeignApi::class)
internal object HevcHwProbe {
    fun probe(): Boolean = memScoped {
        val out = alloc<VTCompressionSessionRefVar>()
        val status = VTCompressionSessionCreate(
            allocator = null,
            width = 320,
            height = 240,
            codecType = kCMVideoCodecType_HEVC,
            encoderSpecification = null,
            sourceImageBufferAttributes = null,
            compressedDataAllocator = null,
            outputCallback = null,
            outputCallbackRefCon = null,
            compressionSessionOut = out.ptr,
        )
        val session = out.value
        if (session != null) {
            VTCompressionSessionInvalidate(session)
        }
        status == 0
    }
}
