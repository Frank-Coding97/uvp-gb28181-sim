package com.uvp.sim.ui

import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.uvp.sim.observability.LogLevel
import com.uvp.sim.observability.LogTag
import com.uvp.sim.observability.SystemLogger
import java.io.File

actual fun openVideoExternally(filePath: String) {
    val ctx = ShareContextHolder.context ?: return
    runCatching {
        val file = File(filePath)
        if (!file.exists()) {
            SystemLogger.emit(LogLevel.Warning, LogTag.Media, "录像文件不存在: $filePath")
            return
        }
        val authority = ctx.packageName + ".fileprovider"
        val uri: Uri = FileProvider.getUriForFile(ctx, authority, file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "video/mp4")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(
            Intent.createChooser(intent, "选择播放器").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
        SystemLogger.emit(LogLevel.Info, LogTag.Media, "调起系统播放器 → $filePath")
    }.onFailure {
        SystemLogger.emit(LogLevel.Error, LogTag.Media, "调起播放器失败: ${it.message}")
    }
}
