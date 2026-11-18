package com.uvp.sim.ui.update

import com.uvp.sim.update.UpdateManifest
import com.uvp.sim.update.UpdateManifestParser
import com.uvp.sim.update.UpdateVersion
import com.uvp.sim.ui.PlatformBuildInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 在线升级的界面状态。
 *
 * 和桌面端同一套产品口径：启动时静默检查一次，只提示「发现新版本」，
 * 真正的安装入口固定在「设置 → 关于 → 检查更新」，因为安装会重启 App，
 * 正在跑的注册 / 推流 / 压测都会中断，不能自动替用户做这个决定。
 */
sealed interface UpdateState {

    /** 还没查过。 */
    data object Idle : UpdateState

    data object Checking : UpdateState

    /** 已是最新版本（或清单版本不比当前新）。 */
    data object Latest : UpdateState

    data class Available(val manifest: UpdateManifest) : UpdateState

    data class Downloading(
        val manifest: UpdateManifest,
        val downloaded: Long,
        val total: Long,
    ) : UpdateState

    /** 包已下好，等用户点安装 / 等系统安装器。 */
    data class ReadyToInstall(val manifest: UpdateManifest) : UpdateState

    /** 缺「安装未知应用」权限，已把用户送到授权页。 */
    data class NeedPermission(val manifest: UpdateManifest) : UpdateState

    data class Failed(val message: String, val manual: Boolean) : UpdateState
}

/**
 * 更新检查 / 下载 / 安装的唯一状态机。
 *
 * 做成进程级单例（而不是挂在某个 Screen 里的 remember）有两个原因：
 * 1. 启动时的静默检查发生在 App 根节点，结果要能被后面的「检查更新」页读到；
 * 2. 切页 / 重组不该中断正在进行的下载。
 */
object UpdateCenter {

    /**
     * 清单地址：官网静态目录，和桌面端 `upgrades/desktop/stable/latest.json`
     * 同构。发布时由 `scripts/release/publish-android-update-manifest.py` 写入。
     */
    const val MANIFEST_URL = "https://uvplatform.com/upgrades/android/stable/latest.json"

    private val installer: UpdateInstaller by lazy { platformUpdateInstaller() }
    private val mutex = Mutex()

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    /** 已下载的产物；换版本或失败重试时丢弃，权限弹回来时复用。 */
    private var downloaded: DownloadedApk? = null

    /** 当前安装的版本；读不到时按 0 处理，让比较逻辑退回版本号。 */
    private val currentCode: Long get() = PlatformBuildInfo.versionCode.toLongOrNull() ?: 0L
    private val currentName: String get() = PlatformBuildInfo.versionName

    val supported: Boolean get() = installer.supported

    /**
     * 检查更新。
     *
     * @param silent 启动时的后台检查：任何失败都吞掉（断网不该在冷启动弹错），
     *   命中新版本时返回一句可直接 toast 的提示，否则返回 null。
     */
    suspend fun check(silent: Boolean = false): String? {
        if (!installer.supported) return null
        // 正在下载 / 已下载待安装时不打断当前流程。
        when (_state.value) {
            is UpdateState.Downloading,
            is UpdateState.ReadyToInstall,
            is UpdateState.NeedPermission -> return null
            else -> Unit
        }
        // 静默检查用 tryLock：撞上后台检查就放弃，反正下次启动还会查。
        // 手动检查用 withLock 排队：用户点了按钮必须有反馈，不能因为后台那次
        // 还没跑完就静默退出（那看起来就是「点了没反应」）。
        // 两者都不会和下载抢锁 —— 下载态在上面的 when 里已经提前返回。
        return if (silent) {
            if (!mutex.tryLock()) return null
            try {
                checkLocked(silent = true)
            } finally {
                mutex.unlock()
            }
        } else {
            mutex.withLock { checkLocked(silent = false) }
        }
    }

    private suspend fun checkLocked(silent: Boolean): String? {
        return try {
            if (!silent) _state.value = UpdateState.Checking
            val manifest = loadManifest()
            if (manifest == null) {
                // 静默检查失败不出声：冷启动断网是常态，弹错只会挨骂。
                if (!silent) _state.value = UpdateState.Failed("更新清单格式不正确，请稍后重试。", manual = true)
                return null
            }
            if (!UpdateVersion.isNewer(manifest, currentCode, currentName)) {
                _state.value = UpdateState.Latest
                return null
            }
            _state.value = UpdateState.Available(manifest)
            if (!silent) return null
            if (!installer.markNotifiedOnce(manifest.trimmedVersion())) return null
            return "发现新版本 v${manifest.trimmedVersion()}，可在「设置 → 关于 → 检查更新」中升级"
        } catch (error: Throwable) {
            if (!silent) _state.value = UpdateState.Failed(friendlyError(error), manual = true)
            return null
        }
    }

    /** 下载（可复用已下载产物）并拉起系统安装器。 */
    suspend fun install() {
        val manifest = when (val snapshot = _state.value) {
            is UpdateState.Available -> snapshot.manifest
            is UpdateState.ReadyToInstall -> snapshot.manifest
            is UpdateState.NeedPermission -> snapshot.manifest
            else -> return
        }
        if (!installer.supported) return
        if (!mutex.tryLock()) return
        try {
            var apk = downloaded?.takeIf { it.version == manifest.trimmedVersion() }
            if (apk == null) {
                _state.value = UpdateState.Downloading(manifest, downloaded = 0, total = manifest.apk.bytes)
                apk = try {
                    installer.download(manifest) { done, total ->
                        _state.value = UpdateState.Downloading(
                            manifest = manifest,
                            downloaded = done,
                            total = if (total > 0) total else manifest.apk.bytes,
                        )
                    }
                } catch (error: Throwable) {
                    downloaded = null
                    _state.value = UpdateState.Failed(friendlyError(error), manual = true)
                    return
                }
                downloaded = apk
            }
            _state.value = UpdateState.ReadyToInstall(manifest)
            when (val outcome = installer.launchInstall(apk)) {
                InstallOutcome.Launched -> _state.value = UpdateState.ReadyToInstall(manifest)
                InstallOutcome.NeedUnknownAppsPermission -> _state.value = UpdateState.NeedPermission(manifest)
                is InstallOutcome.Failed -> _state.value = UpdateState.Failed(outcome.message, manual = true)
            }
        } finally {
            mutex.unlock()
        }
    }

    /** 拉清单并解析，格式坏 / 校验字段缺失都返回 null。 */
    private suspend fun loadManifest(): UpdateManifest? {
        val text = installer.fetchManifestText(MANIFEST_URL)
        val manifest = UpdateManifestParser.parse(text) ?: return null
        if (manifest.validationError() != null) return null
        return manifest
    }

    /**
     * 把异常翻译成用户能懂的话。
     *
     * 判据是异常文本里的关键词 —— 各引擎（CIO / Android 系统）的异常类型不统一，
     * 按类型分支反而会漏，文案只求「用户知道下一步该干什么」。
     */
    private fun friendlyError(error: Throwable): String {
        if (error is UpdateFailure) return error.message ?: "升级失败，请稍后重试。"
        val text = error.message.orEmpty()
        return when {
            Regex("timeout|timed out|connect|refused|unreachable|unknownhost|dns", RegexOption.IGNORE_CASE)
                .containsMatchIn(text) ||
                Regex("network|ssl|tls|certificate", RegexOption.IGNORE_CASE).containsMatchIn(text) ->
                "无法连接更新服务器，请检查网络后重试。"
            else -> text.ifBlank { "检查更新失败，请稍后重试。" }
        }
    }
}
