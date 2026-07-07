package com.uvp.sim.recording

import com.uvp.sim.api.LogTag
import com.uvp.sim.observability.LogLevel
import com.uvp.sim.observability.SystemLogger
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.AVFoundation.AVAssetImageGenerator
import platform.AVFoundation.AVURLAsset
import platform.CoreFoundation.CFRelease
import platform.CoreMedia.CMTimeMake
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.create
import platform.Foundation.dataWithBytes
import platform.Foundation.stringByAppendingPathComponent
import platform.Foundation.writeToFile
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation

interface RecordingThumbnailSource {
    suspend fun takeJpeg(): ByteArray?
}

class IosRecordingThumbnail(
    private val source: RecordingThumbnailSource,
    private val writer: ThumbnailFileWriter = NsDataThumbnailFileWriter(),
) {
    suspend fun captureForRecording(recordingPath: String, recordingId: String): String? {
        val snapshotJpeg = source.takeJpeg()
        val fromSnapshot = snapshotJpeg != null && snapshotJpeg.isNotEmpty()
        val jpeg = if (fromSnapshot) {
            snapshotJpeg
        } else {
            extractJpegFromRecording(recordingPath)
        }
        if (jpeg == null || jpeg.isEmpty()) {
            SystemLogger.emit(
                LogLevel.Debug,
                LogTag.Media,
                "IOS_RECORDING_THUMBNAIL_SKIP reason=no_frame_or_asset_image path=$recordingPath",
            )
            return null
        }

        val thumbnailPath = thumbnailPathFor(recordingPath, recordingId)
        return if (writer.write(thumbnailPath, jpeg)) {
            SystemLogger.emit(
                LogLevel.Debug,
                LogTag.Media,
                "IOS_RECORDING_THUMBNAIL_OK path=$thumbnailPath source=${if (fromSnapshot) "snapshot" else "asset"}",
            )
            thumbnailPath
        } else {
            SystemLogger.emit(
                LogLevel.Warning,
                LogTag.Media,
                "IOS_RECORDING_THUMBNAIL_WRITE_FAIL path=$thumbnailPath",
            )
            null
        }
    }

    internal fun thumbnailPathFor(recordingPath: String, recordingId: String): String {
        val dir = parentPath(recordingPath)
        val base = recordingId.ifBlank { deletingPathExtension(lastPathComponent(recordingPath)) }
        return (dir as NSString).stringByAppendingPathComponent("$base.jpg")
    }

    @OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
    private fun extractJpegFromRecording(recordingPath: String): ByteArray? = memScoped {
        val asset = AVURLAsset(
            uRL = NSURL(fileURLWithPath = recordingPath),
            options = null,
        )
        val generator = AVAssetImageGenerator(asset = asset).apply {
            appliesPreferredTrackTransform = true
        }
        val errorPtr = alloc<ObjCObjectVar<NSError?>>()
        val cgImage = generator.copyCGImageAtTime(
            requestedTime = CMTimeMake(value = 0L, timescale = 1),
            actualTime = null,
            error = errorPtr.ptr,
        )
        if (cgImage == null) {
            SystemLogger.emit(
                LogLevel.Debug,
                LogTag.Media,
                "IOS_RECORDING_THUMBNAIL_ASSET_FAIL path=$recordingPath msg=${errorPtr.value?.localizedDescription ?: "unknown"}",
            )
            return@memScoped null
        }
        try {
            val image = UIImage.imageWithCGImage(cgImage)
            UIImageJPEGRepresentation(image, compressionQuality = 0.78)?.toByteArray()
        } finally {
            CFRelease(cgImage)
        }
    }
}

interface ThumbnailFileWriter {
    fun write(path: String, bytes: ByteArray): Boolean
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private class NsDataThumbnailFileWriter : ThumbnailFileWriter {
    override fun write(path: String, bytes: ByteArray): Boolean {
        val parent = parentPath(path)
        NSFileManager.defaultManager.createDirectoryAtPath(
            path = parent,
            withIntermediateDirectories = true,
            attributes = null,
            error = null,
        )
        val data = bytes.toNSData()
        return data.writeToFile(path, atomically = true)
    }

    private fun ByteArray.toNSData(): NSData = if (isEmpty()) {
        NSData.create(bytes = null, length = 0uL)
    } else {
        usePinned { pinned ->
            NSData.dataWithBytes(bytes = pinned.addressOf(0), length = size.toULong())
        }
    }
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun NSData.toByteArray(): ByteArray {
    val len = length.toInt()
    if (len == 0) return ByteArray(0)
    val bytesPtr = bytes ?: return ByteArray(0)
    return bytesPtr.reinterpret<ByteVar>().readBytes(len)
}

private fun parentPath(path: String): String {
    val idx = path.lastIndexOf('/')
    return if (idx <= 0) path else path.substring(0, idx)
}

private fun lastPathComponent(path: String): String {
    val idx = path.lastIndexOf('/')
    return if (idx < 0) path else path.substring(idx + 1)
}

private fun deletingPathExtension(path: String): String {
    val idx = path.lastIndexOf('.')
    return if (idx <= 0) path else path.substring(0, idx)
}
