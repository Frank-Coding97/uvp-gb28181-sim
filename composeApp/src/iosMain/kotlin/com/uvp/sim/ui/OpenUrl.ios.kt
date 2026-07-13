package com.uvp.sim.ui

import com.uvp.sim.observability.LogLevel
import com.uvp.sim.observability.LogTag
import com.uvp.sim.observability.SystemLogger
import platform.Foundation.NSURL
import platform.UIKit.UIApplication

actual fun openUrl(url: String) {
    val nsUrl = NSURL.URLWithString(url) ?: run {
        SystemLogger.emit(LogLevel.Warning, LogTag.User, "URL 格式错误: $url")
        return
    }
    if (UIApplication.sharedApplication.canOpenURL(nsUrl)) {
        UIApplication.sharedApplication.openURL(
            nsUrl,
            options = emptyMap<Any?, Any?>(),
            completionHandler = null,
        )
    } else {
        SystemLogger.emit(LogLevel.Warning, LogTag.User, "无法打开外链: $url")
    }
}
