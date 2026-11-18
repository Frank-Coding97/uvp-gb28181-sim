package com.uvp.sim.ui.update

import com.uvp.sim.update.UpdateManifest

/**
 * iOS 不支持应用内更新：分发只有源码编译一条路，没有合法的侧载安装通道，
 * Play/App Store 之外也无法拉起安装器。这里实现成 unsupported，UI 会自动
 * 隐藏「检查更新」入口，不误导用户。
 */
actual fun platformUpdateInstaller(): UpdateInstaller = IosUpdateInstaller

private object IosUpdateInstaller : UpdateInstaller {

    override val supported: Boolean = false

    override suspend fun fetchManifestText(url: String): String = throw unsupported()

    override suspend fun download(
        manifest: UpdateManifest,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): DownloadedApk = throw unsupported()

    override fun launchInstall(apk: DownloadedApk): InstallOutcome =
        InstallOutcome.Failed(UNSUPPORTED_MESSAGE)

    override fun markNotifiedOnce(version: String): Boolean = false

    private fun unsupported() = UpdateFailure(UNSUPPORTED_MESSAGE)

    private const val UNSUPPORTED_MESSAGE = "iOS 版本仅提供源码编译，无应用内更新。"
}
