package com.uvp.sim.recording

import android.media.MediaMetadataRetriever
import android.graphics.Bitmap
import android.graphics.Matrix
import com.uvp.sim.observability.LogLevel
import com.uvp.sim.observability.LogTag
import com.uvp.sim.observability.SystemLogger
import java.io.File
import java.io.FileOutputStream

/**
 * 缩略图提取(plan §6)。
 *
 * 在录像 finalize 后调用。从录像中点抽一帧 → 等比缩 320×180 → JPEG 80% → 同名 .jpg。
 * 失败返回 null,列表 UI 用通用占位图。
 */
object ThumbnailExtractor {

    private const val TARGET_W = 320
    private const val TARGET_H = 180
    private const val JPEG_QUALITY = 80

    fun extract(filePath: String, durationMs: Long): String? = runCatching {
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(filePath)
            val timeUs = (durationMs / 2L) * 1000L  // ms → us
            val frame = mmr.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: return@runCatching null
            val scaled = scaleKeepAspect(frame, TARGET_W, TARGET_H)
            val outFile = File(filePath.removeSuffix(".mp4") + ".jpg")
            FileOutputStream(outFile).use { fos ->
                scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, fos)
            }
            scaled.recycle()
            frame.recycle()
            outFile.absolutePath
        } finally {
            runCatching { mmr.release() }
        }
    }.getOrElse {
        SystemLogger.emit(LogLevel.Warning, LogTag.Media, "缩略图抽取失败: ${it.message}")
        null
    }

    private fun scaleKeepAspect(src: Bitmap, targetW: Int, targetH: Int): Bitmap {
        val srcW = src.width.toFloat()
        val srcH = src.height.toFloat()
        val scale = minOf(targetW / srcW, targetH / srcH)
        val matrix = Matrix().apply { postScale(scale, scale) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
    }
}
