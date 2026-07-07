package com.uvp.sim.recording

import com.uvp.sim.media.NalType
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.cValuesOf
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.kCFAllocatorDefault
import platform.CoreMedia.CMBlockBufferCreateWithMemoryBlock
import platform.CoreMedia.CMBlockBufferRefVar
import platform.CoreMedia.CMBlockBufferReplaceDataBytes
import platform.CoreMedia.CMFormatDescriptionRef
import platform.CoreMedia.CMFormatDescriptionRefVar
import platform.CoreMedia.CMSampleBufferCreateReady
import platform.CoreMedia.CMSampleBufferRef
import platform.CoreMedia.CMSampleBufferRefVar
import platform.CoreMedia.CMSampleTimingInfo
import platform.CoreMedia.CMVideoFormatDescriptionCreateFromH264ParameterSets
import platform.CoreMedia.kCMTimeFlags_Valid
import platform.CoreMedia.kCMTimeInvalid

@OptIn(ExperimentalForeignApi::class)
internal class CMSampleBufferBuilder {
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null

    fun observeParameterSets(nalUnits: List<ByteArray>) {
        nalUnits.forEach { nal ->
            if (nal.isEmpty()) return@forEach
            when (nal[0].toInt() and 0x1F) {
                NalType.SPS -> sps = nal.copyOf()
                NalType.PPS -> pps = nal.copyOf()
            }
        }
    }

    fun hasFormatDescriptionInputs(): Boolean =
        sps != null && pps != null

    fun reset() {
        sps = null
        pps = null
    }

    fun buildAvccPayload(nalUnits: List<ByteArray>): ByteArray {
        // AVAssetWriterInput passthrough 要求 AVCC data stream 里只有 slice NAL。
        // SPS(7)/PPS(8) 已通过 sourceFormatHint(formatDescription) 告知 writer,
        // 若再出现在 data stream 里,appendSampleBuffer 直接返回 false。
        val filtered = nalUnits.filter { nal ->
            if (nal.isEmpty()) return@filter false
            val nalType = nal[0].toInt() and 0x1F
            nalType != NalType.SPS && nalType != NalType.PPS
        }
        val totalSize = filtered.sumOf { 4 + it.size }
        val out = ByteArray(totalSize)
        var offset = 0
        filtered.forEach { nal ->
            val len = nal.size
            out[offset] = ((len ushr 24) and 0xFF).toByte()
            out[offset + 1] = ((len ushr 16) and 0xFF).toByte()
            out[offset + 2] = ((len ushr 8) and 0xFF).toByte()
            out[offset + 3] = (len and 0xFF).toByte()
            offset += 4
            nal.copyInto(out, offset)
            offset += nal.size
        }
        return out
    }

    fun buildVideoSampleBuffer(
        nalUnits: List<ByteArray>,
        ptsUs: Long,
        durationUs: Long,
    ): CMSampleBufferRef? = memScoped {
        val formatDescription = buildFormatDescription() ?: return@memScoped null
        val payload = buildAvccPayload(nalUnits)
        if (payload.isEmpty()) {
            CFRelease(formatDescription)
            return@memScoped null
        }

        val blockBufferOut = alloc<CMBlockBufferRefVar>()
        val createBlockStatus = CMBlockBufferCreateWithMemoryBlock(
            structureAllocator = kCFAllocatorDefault,
            memoryBlock = null,
            blockLength = payload.size.convert(),
            blockAllocator = kCFAllocatorDefault,
            customBlockSource = null,
            offsetToData = 0u,
            dataLength = payload.size.convert(),
            flags = 0u,
            blockBufferOut = blockBufferOut.ptr,
        )
        if (createBlockStatus != 0) {
            CFRelease(formatDescription)
            return@memScoped null
        }
        val blockBuffer = blockBufferOut.value ?: run {
            CFRelease(formatDescription)
            return@memScoped null
        }

        val replaceStatus = payload.usePinned { pinned ->
            CMBlockBufferReplaceDataBytes(
                sourceBytes = pinned.addressOf(0),
                destinationBuffer = blockBuffer,
                offsetIntoDestination = 0u,
                dataLength = payload.size.convert(),
            )
        }
        if (replaceStatus != 0) {
            CFRelease(blockBuffer)
            CFRelease(formatDescription)
            return@memScoped null
        }

        val timing = alloc<CMSampleTimingInfo>()
        timing.duration.writeValidTime(durationUs)
        timing.presentationTimeStamp.writeValidTime(ptsUs)
        timing.decodeTimeStamp.writeInvalidTime()

        val sampleSize = alloc<platform.posix.size_tVar>()
        sampleSize.value = payload.size.convert()
        val sampleOut = alloc<CMSampleBufferRefVar>()
        val sampleStatus = CMSampleBufferCreateReady(
            allocator = kCFAllocatorDefault,
            dataBuffer = blockBuffer,
            formatDescription = formatDescription,
            numSamples = 1,
            numSampleTimingEntries = 1,
            sampleTimingArray = timing.ptr,
            numSampleSizeEntries = 1,
            sampleSizeArray = sampleSize.ptr,
            sampleBufferOut = sampleOut.ptr,
        )

        CFRelease(blockBuffer)
        CFRelease(formatDescription)

        if (sampleStatus != 0) return@memScoped null
        sampleOut.value
    }

    /**
     * 暴露 formatDescription 给 IosRecordingService,让 openWriter 阶段 2
     * (第一个 keyframe 到达时)能用它做 AVAssetWriterInput sourceFormatHint。
     *
     * 调用方拿到 CMFormatDescriptionRef 后**必须** CFRelease。
     */
    internal fun buildFormatDescriptionOrNull(): CMFormatDescriptionRef? =
        buildFormatDescription()

    private fun buildFormatDescription(): CMFormatDescriptionRef? = memScoped {
        val spsBytes = sps ?: return@memScoped null
        val ppsBytes = pps ?: return@memScoped null
        if (spsBytes.isEmpty() || ppsBytes.isEmpty()) return@memScoped null

        val pointerOut = alloc<CMFormatDescriptionRefVar>()
        val status = spsBytes.usePinned { spsPinned ->
            ppsBytes.usePinned { ppsPinned ->
                CMVideoFormatDescriptionCreateFromH264ParameterSets(
                    allocator = kCFAllocatorDefault,
                    parameterSetCount = 2u,
                    parameterSetPointers = cValuesOf(
                        spsPinned.addressOf(0).reinterpret<UByteVar>(),
                        ppsPinned.addressOf(0).reinterpret<UByteVar>(),
                    ),
                    parameterSetSizes = cValuesOf(
                        spsBytes.size.toULong(),
                        ppsBytes.size.toULong(),
                    ),
                    NALUnitHeaderLength = 4,
                    formatDescriptionOut = pointerOut.ptr,
                )
            }
        }
        if (status != 0) null else pointerOut.value
    }
}

private fun platform.CoreMedia.CMTime.writeValidTime(value: Long) {
    this.value = value
    this.timescale = 1_000_000
    this.flags = kCMTimeFlags_Valid
    this.epoch = 0L
}

private fun platform.CoreMedia.CMTime.writeInvalidTime() {
    value = kCMTimeInvalid.value
    timescale = kCMTimeInvalid.timescale
    flags = kCMTimeInvalid.flags
    epoch = kCMTimeInvalid.epoch
}
