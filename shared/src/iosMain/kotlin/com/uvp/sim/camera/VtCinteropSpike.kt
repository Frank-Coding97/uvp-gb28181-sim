package com.uvp.sim.camera

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.alloc
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.cValue
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.value
import platform.CoreFoundation.CFRelease
import platform.CoreMedia.CMSampleBufferRef
import platform.CoreMedia.kCMVideoCodecType_H264
import platform.VideoToolbox.VTCompressionSessionCreate
import platform.VideoToolbox.VTCompressionSessionInvalidate
import platform.VideoToolbox.VTCompressionSessionRef
import platform.VideoToolbox.VTCompressionSessionRefVar

/**
 * T2 cinterop spike — verify Kotlin/Native can:
 *   1. `import platform.VideoToolbox.*` without a custom .def
 *   2. Create a real VTCompressionSession (Simulator uses software encoder)
 *   3. Pass a `staticCFunction` + `StableRef<Any>` through the outputCallback
 *      slot (identical bridging pattern needed by IosCameraStreamer)
 *   4. Release resources without leak / crash
 *
 * Compile-only spike: no assertions run at import time. Call sites are
 * validated by `:shared:compileKotlinIosSimulatorArm64` — if this file
 * compiles, plan-DEC risk R1 (missing symbols) and R2 (staticCFunction
 * signature mismatch) are both eliminated.
 *
 * Delete once IosCameraStreamer T5 lands the real implementation.
 */
@OptIn(ExperimentalForeignApi::class)
internal object VtCinteropSpike {

    /**
     * Verify the callback signature Kotlin/Native expects for
     * `VTCompressionOutputCallback`. Signature per VideoToolbox headers:
     *
     *   typedef void (*VTCompressionOutputCallback)(
     *     void * CM_NULLABLE outputCallbackRefCon,
     *     void * CM_NULLABLE sourceFrameRefCon,
     *     OSStatus status,
     *     VTEncodeInfoFlags infoFlags,
     *     CM_NULLABLE CMSampleBufferRef sampleBuffer);
     */
    val outputCallback = staticCFunction<
        COpaquePointer?,   // outputCallbackRefCon — carries StableRef<Streamer>
        COpaquePointer?,   // sourceFrameRefCon
        Int,               // OSStatus
        UInt,              // VTEncodeInfoFlags
        CMSampleBufferRef?,
        Unit
    > { refCon, _, status, _, sampleBuffer ->
        if (status != 0 || sampleBuffer == null || refCon == null) return@staticCFunction
        // Recover the Kotlin object from the void* userdata slot.
        val streamer = refCon.asStableRef<VtCinteropSpike.Receiver>().get()
        streamer.onSample(sampleBuffer)
    }

    /**
     * Receiver interface — real IosCameraStreamer will implement this same
     * shape so the callback binds to it identically.
     */
    interface Receiver {
        fun onSample(sample: CMSampleBufferRef)
    }

    /**
     * Attempt to create a minimal VTCompressionSession. On Simulator this uses
     * the software encoder path. Returns the raw handle (caller invalidates
     * and CFReleases).
     */
    @Suppress("UNUSED_PARAMETER")
    fun createSession(
        width: Int,
        height: Int,
        receiver: Receiver,
    ): VTCompressionSessionRef? = memScoped {
        val sessionOut = alloc<VTCompressionSessionRefVar>()
        val refCon = StableRef.create(receiver).asCPointer()
        val status = VTCompressionSessionCreate(
            allocator = null,
            width = width,
            height = height,
            codecType = kCMVideoCodecType_H264,
            encoderSpecification = null,
            sourceImageBufferAttributes = null,
            compressedDataAllocator = null,
            outputCallback = outputCallback,
            outputCallbackRefCon = refCon,
            compressionSessionOut = sessionOut.ptr
        )
        if (status != 0) {
            // Failed — dispose the StableRef so we don't leak.
            refCon.asStableRef<Receiver>().dispose()
            null
        } else {
            sessionOut.value
        }
    }

    fun release(session: VTCompressionSessionRef?, receiverRef: COpaquePointer?) {
        session?.let {
            VTCompressionSessionInvalidate(it)
            CFRelease(it)
        }
        receiverRef?.asStableRef<Receiver>()?.dispose()
    }
}
