package com.uvp.sim.recording

import com.uvp.sim.media.H265NalType
import com.uvp.sim.media.NalType
import com.uvp.sim.media.VideoCodec
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
import platform.CoreMedia.CMVideoFormatDescriptionCreateFromHEVCParameterSets
import platform.CoreMedia.kCMTimeFlags_Valid
import platform.CoreMedia.kCMTimeInvalid

/**
 * Build [CMSampleBufferRef] from encoded NAL frames for [AVAssetWriter] passthrough input.
 *
 * ## Codec-aware (2026-07-13)
 *
 * Started life as H.264-only,which broke silently the moment users switched the encoder to
 * HEVC — the low-5-bit NAL-type mask matches nothing meaningful in H.265,so SPS/PPS never
 * arrive at the writer,`hasFormatDescriptionInputs()` stays false forever,every video frame
 * gets dropped with `missing_format`, and the session finalizes empty (`didWriteAny=false`).
 *
 * Fix: the builder now tracks the active [VideoCodec] and dispatches NAL parsing + format
 * description construction accordingly:
 *
 * - **H.264** parameter sets: SPS(7) / PPS(8) → [CMVideoFormatDescriptionCreateFromH264ParameterSets].
 * - **H.265** parameter sets: VPS(32) / SPS(33) / PPS(34) → [CMVideoFormatDescriptionCreateFromHEVCParameterSets].
 *
 * NAL header parsing is uniformly delegated to [VideoCodec.nalType] and [VideoCodec.isParameterSet],
 * so the low-level bit twiddling lives in exactly one place.
 */
@OptIn(ExperimentalForeignApi::class)
internal class CMSampleBufferBuilder {
    private var codec: VideoCodec = VideoCodec.H264

    // H.264: sps + pps 就够;H.265 多一个 vps。H.264 会话下 vps 永远保持 null。
    private var vps: ByteArray? = null
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null

    /**
     * 切换活跃 codec。会话开始前调 —— 主服务 openWriter 时会先 reset(codec) 保证
     * 上次录像残留的 sps/pps/vps 不跨会话污染。
     */
    fun configureCodec(codec: VideoCodec) {
        this.codec = codec
    }

    fun observeParameterSets(nalUnits: List<ByteArray>) {
        nalUnits.forEach { nal ->
            if (nal.isEmpty()) return@forEach
            val nalType = codec.nalType(nal[0])
            if (!codec.isParameterSet(nalType)) return@forEach
            when (codec) {
                VideoCodec.H264 -> when (nalType) {
                    NalType.SPS -> sps = nal.copyOf()
                    NalType.PPS -> pps = nal.copyOf()
                }
                VideoCodec.H265 -> when (nalType) {
                    H265NalType.VPS_NUT -> vps = nal.copyOf()
                    H265NalType.SPS_NUT -> sps = nal.copyOf()
                    H265NalType.PPS_NUT -> pps = nal.copyOf()
                }
            }
        }
    }

    /**
     * H.264 只需要 SPS+PPS 两件套,H.265 需要 VPS+SPS+PPS 三件套 —— 缺一 CoreMedia 就
     * 无法建 CMVideoFormatDescription,writer 拿不到 sourceFormatHint。
     */
    fun hasFormatDescriptionInputs(): Boolean = when (codec) {
        VideoCodec.H264 -> sps != null && pps != null
        VideoCodec.H265 -> vps != null && sps != null && pps != null
    }

    fun reset() {
        vps = null
        sps = null
        pps = null
    }

    fun buildAvccPayload(nalUnits: List<ByteArray>): ByteArray {
        // AVAssetWriterInput passthrough 要求 AVCC data stream 里只有 slice NAL。
        // 参数集(H.264 SPS/PPS / H.265 VPS/SPS/PPS)已通过 sourceFormatHint 告知 writer,
        // 若再出现在 data stream 里,appendSampleBuffer 直接返回 false。
        val filtered = nalUnits.filter { nal ->
            if (nal.isEmpty()) return@filter false
            !codec.isParameterSet(codec.nalType(nal[0]))
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

    /** 测试可见:当前活跃 codec(不影响生产语义)。 */
    internal fun activeCodec(): VideoCodec = codec

    private fun buildFormatDescription(): CMFormatDescriptionRef? = when (codec) {
        VideoCodec.H264 -> buildH264FormatDescription()
        VideoCodec.H265 -> buildHevcFormatDescription()
    }

    private fun buildH264FormatDescription(): CMFormatDescriptionRef? = memScoped {
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

    /**
     * HEVC 版本:CMVideoFormatDescriptionCreateFromHEVCParameterSets 要求 VPS/SPS/PPS
     * 三个 parameter set(iOS 11+ 提供)。extensions 传 null,CoreMedia 从 SPS 里解出
     * 分辨率 / profile / level 等信息。
     */
    private fun buildHevcFormatDescription(): CMFormatDescriptionRef? = memScoped {
        val vpsBytes = vps ?: return@memScoped null
        val spsBytes = sps ?: return@memScoped null
        val ppsBytes = pps ?: return@memScoped null
        if (vpsBytes.isEmpty() || spsBytes.isEmpty() || ppsBytes.isEmpty()) return@memScoped null

        val pointerOut = alloc<CMFormatDescriptionRefVar>()
        val status = vpsBytes.usePinned { vpsPinned ->
            spsBytes.usePinned { spsPinned ->
                ppsBytes.usePinned { ppsPinned ->
                    CMVideoFormatDescriptionCreateFromHEVCParameterSets(
                        allocator = kCFAllocatorDefault,
                        parameterSetCount = 3u,
                        parameterSetPointers = cValuesOf(
                            vpsPinned.addressOf(0).reinterpret<UByteVar>(),
                            spsPinned.addressOf(0).reinterpret<UByteVar>(),
                            ppsPinned.addressOf(0).reinterpret<UByteVar>(),
                        ),
                        parameterSetSizes = cValuesOf(
                            vpsBytes.size.toULong(),
                            spsBytes.size.toULong(),
                            ppsBytes.size.toULong(),
                        ),
                        NALUnitHeaderLength = 4,
                        extensions = null,
                        formatDescriptionOut = pointerOut.ptr,
                    )
                }
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
