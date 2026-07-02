package com.uvp.sim.recording

import com.uvp.sim.api.LogTag
import com.uvp.sim.observability.LogLevel
import com.uvp.sim.observability.SystemLogger
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.create
import platform.Foundation.dataWithBytes
import platform.Foundation.stringByAppendingPathComponent
import platform.Foundation.writeToFile

interface RecordingThumbnailSource {
    suspend fun takeJpeg(): ByteArray?
}

class IosRecordingThumbnail(
    private val source: RecordingThumbnailSource,
    private val writer: ThumbnailFileWriter = NsDataThumbnailFileWriter(),
) {
    suspend fun captureForRecording(recordingPath: String, recordingId: String): String? {
        val jpeg = source.takeJpeg()
        if (jpeg == null || jpeg.isEmpty()) {
            SystemLogger.emit(
                LogLevel.Debug,
                LogTag.Media,
                "IOS_RECORDING_THUMBNAIL_SKIP reason=no_frame path=$recordingPath",
            )
            return null
        }

        val thumbnailPath = thumbnailPathFor(recordingPath, recordingId)
        return if (writer.write(thumbnailPath, jpeg)) {
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
}

interface ThumbnailFileWriter {
    fun write(path: String, bytes: ByteArray): Boolean
}

@OptIn(ExperimentalForeignApi::class)
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
