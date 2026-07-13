package com.uvp.sim.ui

import android.content.Intent
import android.net.Uri
import com.uvp.sim.observability.LogLevel
import com.uvp.sim.observability.LogTag
import com.uvp.sim.observability.SystemLogger

actual fun openUrl(url: String) {
    val ctx = ShareContextHolder.context ?: return
    runCatching {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(intent)
    }.onFailure {
        SystemLogger.emit(LogLevel.Warning, LogTag.User, "打开外链失败: $url · ${it.message}")
    }
}
