package com.uvp.sim.ui

import android.content.pm.PackageManager
import android.os.Build

/**
 * Android 读 PackageManager — 复用现成的 [ShareContextHolder.context],
 * 避免为拿版本号单开一个 Context 注入点。
 *
 * versionName / versionCode 在 androidApp/build.gradle.kts defaultConfig 里定义。
 * 拿不到时(极罕见,Context 还没初始化)兜底 "?"。
 */
actual object PlatformBuildInfo {
    private val info: Pair<String, String> by lazy {
        val ctx = ShareContextHolder.context ?: return@lazy "?" to "?"
        runCatching {
            val pm = ctx.packageManager
            val pkg = pm.getPackageInfo(ctx.packageName, 0)
            val name = pkg.versionName ?: "?"
            val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pkg.longVersionCode.toString()
            } else {
                @Suppress("DEPRECATION")
                pkg.versionCode.toString()
            }
            name to code
        }.getOrElse { "?" to "?" }
    }

    actual val versionName: String get() = info.first
    actual val versionCode: String get() = info.second
}
