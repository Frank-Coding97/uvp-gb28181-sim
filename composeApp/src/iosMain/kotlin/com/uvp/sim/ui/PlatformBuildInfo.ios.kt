package com.uvp.sim.ui

import platform.Foundation.NSBundle

/**
 * iOS 读 NSBundle.mainBundle.infoDictionary。
 *
 * CFBundleShortVersionString 是面向用户的 marketing 版本(1.1.0),
 * CFBundleVersion 是 build number(1),两者定义在 iosApp/project.yml 生成的 Info.plist。
 */
actual object PlatformBuildInfo {
    actual val versionName: String =
        NSBundle.mainBundle.infoDictionary
            ?.get("CFBundleShortVersionString") as? String ?: "?"

    actual val versionCode: String =
        NSBundle.mainBundle.infoDictionary
            ?.get("CFBundleVersion") as? String ?: "?"
}
