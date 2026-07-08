package com.uvp.sim.recording

import com.uvp.sim.media.AudioCodec
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreAudioTypes.AudioStreamBasicDescription
import platform.CoreAudioTypes.kAudioFormatALaw
import platform.CoreAudioTypes.kAudioFormatMPEG4AAC
import platform.CoreAudioTypes.kAudioFormatULaw
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.kCFAllocatorDefault
import platform.CoreMedia.CMAudioFormatDescriptionCreate
import platform.CoreMedia.CMBlockBufferCreateWithMemoryBlock
import platform.CoreMedia.CMBlockBufferRefVar
import platform.CoreMedia.CMBlockBufferReplaceDataBytes
import platform.CoreMedia.CMFormatDescriptionRef
import platform.CoreMedia.CMFormatDescriptionRefVar
import platform.CoreMedia.CMSampleBufferCreateReady
import platform.CoreMedia.CMSampleBufferRef
import platform.CoreMedia.CMSampleBufferRefVar
import platform.CoreMedia.CMSampleTimingInfo
import platform.CoreMedia.kCMTimeFlags_Valid
import platform.CoreMedia.kCMTimeInvalid
import platform.posix.size_tVar

/**
 * T-B3-3:音频 CMSampleBuffer 构造。
 *
 * 与 [CMSampleBufferBuilder](video)分开:audio 侧 ASBD(AudioStreamBasicDescription)
 * 决定 format,不需要 SPS/PPS 参数集。相同的 CMBlockBuffer + CMSampleBufferCreateReady
 * 流水线。
 */
@OptIn(ExperimentalForeignApi::class)
internal class CMAudioSampleBufferBuilder {

    fun build(
        payload: ByteArray,
        relPtsUs: Long,
        codec: AudioCodec,
    ): CMSampleBufferRef? {
        if (payload.isEmpty()) return null
        return memScoped {
            val formatDesc = buildFormatDescription(codec, payload.size) ?: return@memScoped null

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
                CFRelease(formatDesc)
                return@memScoped null
            }
            val blockBuffer = blockBufferOut.value ?: run {
                CFRelease(formatDesc)
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
                CFRelease(formatDesc)
                return@memScoped null
            }

            val timing = alloc<CMSampleTimingInfo>()
            timing.duration.writeValidTime(durationForCodec(codec, payload.size))
            timing.presentationTimeStamp.writeValidTime(relPtsUs)
            timing.decodeTimeStamp.writeInvalidTime()

            val sampleSize = alloc<size_tVar>()
            sampleSize.value = payload.size.convert()
            val sampleOut = alloc<CMSampleBufferRefVar>()
            val status = CMSampleBufferCreateReady(
                allocator = kCFAllocatorDefault,
                dataBuffer = blockBuffer,
                formatDescription = formatDesc,
                numSamples = 1,
                numSampleTimingEntries = 1,
                sampleTimingArray = timing.ptr,
                numSampleSizeEntries = 1,
                sampleSizeArray = sampleSize.ptr,
                sampleBufferOut = sampleOut.ptr,
            )

            CFRelease(blockBuffer)
            CFRelease(formatDesc)

            if (status != 0) null else sampleOut.value
        }
    }

    private fun buildFormatDescription(codec: AudioCodec, payloadBytes: Int): CMFormatDescriptionRef? =
        memScoped {
            val asbd = alloc<AudioStreamBasicDescription>()
            when (codec) {
                AudioCodec.G711A -> {
                    asbd.mFormatID = kAudioFormatALaw
                    asbd.mSampleRate = 8_000.0
                    asbd.mChannelsPerFrame = 1u
                    asbd.mBytesPerPacket = 1u
                    asbd.mFramesPerPacket = 1u
                    asbd.mBytesPerFrame = 1u
                    asbd.mBitsPerChannel = 8u
                    asbd.mFormatFlags = 0u
                    asbd.mReserved = 0u
                }
                AudioCodec.G711U -> {
                    asbd.mFormatID = kAudioFormatULaw
                    asbd.mSampleRate = 8_000.0
                    asbd.mChannelsPerFrame = 1u
                    asbd.mBytesPerPacket = 1u
                    asbd.mFramesPerPacket = 1u
                    asbd.mBytesPerFrame = 1u
                    asbd.mBitsPerChannel = 8u
                    asbd.mFormatFlags = 0u
                    asbd.mReserved = 0u
                }
                AudioCodec.AAC -> {
                    asbd.mFormatID = kAudioFormatMPEG4AAC
                    asbd.mSampleRate = 44_100.0
                    asbd.mChannelsPerFrame = 1u
                    asbd.mBytesPerPacket = 0u
                    asbd.mFramesPerPacket = 1024u
                    asbd.mBytesPerFrame = 0u
                    asbd.mBitsPerChannel = 0u
                    asbd.mFormatFlags = 2u // MPEG4ObjectID = AAC LC(2)
                    asbd.mReserved = 0u
                }
            }
            val out = alloc<CMFormatDescriptionRefVar>()
            val status = CMAudioFormatDescriptionCreate(
                allocator = kCFAllocatorDefault,
                asbd = asbd.ptr,
                layoutSize = 0u,
                layout = null,
                magicCookieSize = 0u,
                magicCookie = null,
                extensions = null,
                formatDescriptionOut = out.ptr,
            )
            if (status != 0) null else out.value
        }

    /** Approximate per-sample duration in microseconds. */
    private fun durationForCodec(codec: AudioCodec, payloadBytes: Int): Long = when (codec) {
        AudioCodec.G711A, AudioCodec.G711U -> payloadBytes.toLong() * 1_000_000L / 8_000L
        AudioCodec.AAC -> 1024L * 1_000_000L / 44_100L
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
