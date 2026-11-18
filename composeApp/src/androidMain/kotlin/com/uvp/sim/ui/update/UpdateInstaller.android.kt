package com.uvp.sim.ui.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import com.uvp.sim.update.UpdateManifest
import com.uvp.sim.update.applyUpdateTimeouts
import com.uvp.sim.ui.ShareContextHolder
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Android 的在线升级实现：查清单 → 下载 → 三重校验 → 拉起系统安装器。
 *
 * 几个刻意的取舍：
 *
 * 1. **不用 DownloadManager**：它把文件交给系统下载器，我们拿不到"下载的同时
 *    算 sha256"，还得再读一遍 36MB。自己用 Ktor 流式写盘能一遍算完。
 * 2. **安装走 ACTION_VIEW + FileProvider**，不用 PackageInstaller Session：
 *    前者是系统安装器的标准入口，厂商 ROM 兼容性最好；Session 在部分 ROM 上
 *    会被「应用安装校验」拦掉，反而更脆。
 * 3. **校验做满三件事**：sha256（防错包/坏包）、包名 + versionCode（防拿错产品线
 *    的包）、签名证书比对（防被替换成同包名的第三方包）。任何一项不过就删文件。
 */
actual fun platformUpdateInstaller(): UpdateInstaller = AndroidUpdateInstaller()

private class AndroidUpdateInstaller : UpdateInstaller {

    override val supported: Boolean = true

    private val http: HttpClient by lazy {
        HttpClient(CIO) {
            expectSuccess = false
            // 超时策略放在 shared 里，和单测共用同一份实现 —— 见 UpdateHttpClient.kt
            // 里关于 requestTimeoutMillis 不能写 0 的那段说明。
            applyUpdateTimeouts()
        }
    }

    override suspend fun fetchManifestText(url: String): String {
        return try {
            val response = http.get(url) {
                timeout { requestTimeoutMillis = CHECK_TIMEOUT_MS }
            }
            Log.i(TAG, "清单响应 HTTP ${response.status.value} — $url")
            if (!response.status.isSuccess()) {
                throw UpdateFailure("更新服务器返回异常（HTTP ${response.status.value}），请稍后重试。")
            }
            response.bodyAsText()
        } catch (error: Throwable) {
            // 这里必须留痕：release 包开了 R8，异常被上层转成人话文案后就没有任何现场了，
            // 线上出问题只能靠这一行定位（设备上 `adb logcat -s UvpUpdate`）。
            Log.w(TAG, "清单请求失败 — $url", error)
            throw error
        }
    }

    override suspend fun download(
        manifest: UpdateManifest,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): DownloadedApk {
        val context = appContext() ?: throw UpdateFailure("应用尚未初始化完成，请稍后重试。")
        val version = manifest.trimmedVersion()
        val targetDir = File(context.cacheDir, DIR_NAME).apply { mkdirs() }
        // 只留当前要装的版本：上一次失败/回滚留下的包没有留存价值，还占空间。
        targetDir.listFiles()?.forEach { if (!it.name.endsWith("-$version.apk")) it.delete() }

        val finalFile = File(targetDir, "uvp-gb28181-sim-$version.apk")
        val partFile = File(targetDir, "${finalFile.name}.part")
        partFile.delete()

        val digest = MessageDigest.getInstance("SHA-256")
        try {
            withContext(Dispatchers.IO) {
                http.prepareGet(manifest.apk.url).execute { response ->
                    if (!response.status.isSuccess()) {
                        throw UpdateFailure("下载失败（HTTP ${response.status.value}），请稍后重试。")
                    }
                    val total = response.contentLength() ?: manifest.apk.bytes
                    var downloaded = 0L
                    var lastReported = 0L
                    val buffer = ByteArray(BUFFER_SIZE)
                    val channel = response.bodyAsChannel()
                    FileOutputStream(partFile).use { output ->
                        while (true) {
                            val read = channel.readAvailable(buffer, 0, buffer.size)
                            if (read < 0) break
                            if (read == 0) continue
                            output.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                            downloaded += read
                            // 36MB 按 64KB 回调会产生 ~550 次状态更新，重组过于频繁；
                            // 每 256KB 报一次，界面进度仍然顺滑。
                            if (downloaded - lastReported >= PROGRESS_STEP || downloaded >= total) {
                                lastReported = downloaded
                                onProgress(downloaded, total)
                            }
                        }
                    }
                    onProgress(downloaded, total)
                }
            }
        } catch (error: UpdateFailure) {
            partFile.delete()
            throw error
        } catch (error: Throwable) {
            partFile.delete()
            Log.w(TAG, "安装包下载失败 — ${manifest.apk.url}", error)
            throw UpdateFailure("下载中断，请检查网络后重试。")
        }

        val actualHash = digest.digest().toHex()
        if (!actualHash.equals(manifest.apk.sha256, ignoreCase = true)) {
            partFile.delete()
            throw UpdateFailure("安装包校验失败（文件可能损坏），已取消安装。请重试。")
        }
        if (!partFile.renameTo(finalFile)) {
            partFile.delete()
            throw UpdateFailure("安装包保存失败，请检查设备存储空间后重试。")
        }
        verifyArchive(context, finalFile, manifest)
        return DownloadedApk(path = finalFile.absolutePath, version = version)
    }

    override fun launchInstall(apk: DownloadedApk): InstallOutcome {
        val context = appContext() ?: return InstallOutcome.Failed("应用尚未初始化完成，请稍后重试。")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            // 没授权时把用户送到本应用的「安装未知应用」开关，返回后可直接继续安装，
            // 已下载的包会被复用，不会让用户重下 36MB。
            val opened = runCatching {
                context.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${context.packageName}"),
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }.isSuccess
            return if (opened) {
                InstallOutcome.NeedUnknownAppsPermission
            } else {
                InstallOutcome.Failed("无法打开「安装未知应用」设置，请手动在系统设置中授权后重试。")
            }
        }
        return runCatching {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                File(apk.path),
            )
            context.startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, APK_MIME)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
            InstallOutcome.Launched
        }.getOrElse {
            InstallOutcome.Failed("无法启动系统安装器，请确认设备允许安装应用后重试。")
        }
    }

    override fun markNotifiedOnce(version: String): Boolean {
        val context = appContext() ?: return false
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getString(KEY_NOTIFIED_VERSION, null) == version) return false
        prefs.edit().putString(KEY_NOTIFIED_VERSION, version).apply()
        return true
    }

    /**
     * 安装前把包拆开看一眼：包名、版本号、签名证书。
     *
     * 这三项是「能装成功且装的是我们的包」的充要条件，缺一项都可能装出一个
     * 陌生应用或者直接失败在系统安装器里（那时用户只会看到一句英文错误）。
     */
    private fun verifyArchive(context: Context, file: File, manifest: UpdateManifest) {
        if (manifest.apk.bytes > 0 && file.length() != manifest.apk.bytes) {
            file.delete()
            throw UpdateFailure("安装包大小与清单不一致，可能下载不完整，请重试。")
        }
        val pm = context.packageManager
        val archive = pm.getPackageArchiveInfo(file.absolutePath, PACKAGE_FLAGS)
            ?: run {
                file.delete()
                throw UpdateFailure("下载的文件不是有效的安装包，已取消安装。")
            }
        if (archive.packageName != context.packageName) {
            file.delete()
            throw UpdateFailure("安装包包名与当前应用不一致，已拒绝安装。")
        }
        val archiveCode = archive.longVersionCodeCompat()
        val installedCode = pm.getPackageInfo(context.packageName, 0).longVersionCodeCompat()
        if (manifest.versionCode > 0 && archiveCode != manifest.versionCode) {
            file.delete()
            throw UpdateFailure("安装包版本与更新清单不一致，已拒绝安装。")
        }
        if (archiveCode <= installedCode) {
            file.delete()
            throw UpdateFailure("安装包版本不高于当前版本，已拒绝安装。")
        }
        if (manifest.minSdk > 0 && Build.VERSION.SDK_INT < manifest.minSdk) {
            file.delete()
            throw UpdateFailure("新版本要求更高的 Android 系统版本，当前设备不支持。")
        }
        val archiveSigners = archive.signerDigests()
        val installedSigners = pm.getPackageInfo(context.packageName, PACKAGE_FLAGS).signerDigests()
        if (archiveSigners.isEmpty() || installedSigners.isEmpty()) {
            file.delete()
            throw UpdateFailure("无法读取安装包签名信息，已拒绝安装。")
        }
        if (archiveSigners != installedSigners) {
            file.delete()
            throw UpdateFailure("安装包签名与当前应用不一致，已拒绝安装。请到官网下载正式版本。")
        }
    }

    private fun appContext(): Context? = ShareContextHolder.context?.applicationContext

    private fun PackageInfo.longVersionCodeCompat(): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            longVersionCode
        } else {
            @Suppress("DEPRECATION") versionCode.toLong()
        }

    /** 取包内签名证书的 sha256 摘要集合；多签名时按集合比对。 */
    private fun PackageInfo.signerDigests(): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION") signatures
        } ?: return emptySet()
        return signatures.mapTo(LinkedHashSet()) { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).toHex()
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { byte ->
        val value = byte.toInt() and 0xFF
        if (value < 0x10) "0${value.toString(16)}" else value.toString(16)
    }

    private companion object {
        const val TAG = "UvpUpdate"
        const val CHECK_TIMEOUT_MS = 20_000L
        const val BUFFER_SIZE = 64 * 1024
        const val PROGRESS_STEP = 256L * 1024
        const val DIR_NAME = "updates"
        const val APK_MIME = "application/vnd.android.package-archive"
        const val PREFS_NAME = "uvp_update"
        const val KEY_NOTIFIED_VERSION = "notified_version"

        /**
         * API 28+ 用 GET_SIGNING_CERTIFICATES（GET_SIGNATURES 已废弃且对
         * 多签名包语义不同）；老系统保留旧 flag。
         */
        val PACKAGE_FLAGS: Int
            get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageManager.GET_SIGNING_CERTIFICATES
            } else {
                @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
            }
    }
}
