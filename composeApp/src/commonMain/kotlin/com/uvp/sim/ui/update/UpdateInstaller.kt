package com.uvp.sim.ui.update

import com.uvp.sim.update.UpdateManifest

/** 下载完成并通过校验的安装包。path 是可直接交给安装器的绝对路径。 */
data class DownloadedApk(val path: String, val version: String)

/** 拉起系统安装器的结果。 */
sealed interface InstallOutcome {
    /** 系统安装器已弹出，接下来由用户确认。 */
    data object Launched : InstallOutcome

    /** 本应用还没有「安装未知应用」权限，已把用户送到授权页。 */
    data object NeedUnknownAppsPermission : InstallOutcome

    data class Failed(val message: String) : InstallOutcome
}

/**
 * 在线升级的平台能力边界。
 *
 * 只有 Android 有实现：iOS 不走侧载安装，App Store 之外没有合法安装通道，
 * 所以 iOS 端 `supported = false`，UI 直接隐藏升级入口。
 *
 * 分工刻意做成「下载」和「安装」两步，而不是一个 downloadAndInstall：
 * 「安装未知应用」权限未开时，安装会被弹到系统设置，用户返回后不该让他
 * 重新下 36MB 的包 —— 已下载产物要能复用。
 */
interface UpdateInstaller {

    val supported: Boolean

    /** 拉取清单原文（JSON 文本）。网络异常直接抛，由 [UpdateCenter] 翻译成人话。 */
    suspend fun fetchManifestText(url: String): String

    /**
     * 下载安装包并做完整性（sha256）+ 合法性（包名/版本/签名）校验。
     *
     * @param onProgress (已下载字节, 总字节)；总量未知时传 0。
     * @throws UpdateFailure 校验不通过时抛出，message 可直接展示给用户。
     */
    suspend fun download(
        manifest: UpdateManifest,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): DownloadedApk

    /** 拉起系统安装器。同步完成（只是 startActivity），真正的安装由系统接管。 */
    fun launchInstall(apk: DownloadedApk): InstallOutcome

    /**
     * 静默提示去重：同一版本只提示一次。
     *
     * @return true 表示本次是首次提示（调用方应当提示），false 表示已经提示过。
     */
    fun markNotifiedOnce(version: String): Boolean
}

/** 已经翻译成用户话术的失败，UI 原样展示 message。 */
class UpdateFailure(message: String) : Exception(message)

/** 各平台的实现；Android 真做，iOS 是 no-op。 */
expect fun platformUpdateInstaller(): UpdateInstaller
