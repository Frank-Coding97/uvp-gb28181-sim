package com.uvp.sim.snapshot

import com.uvp.sim.gb28181.SnapShotConfig
import com.uvp.sim.gb28181.SnapShotNotifyBuilder
import com.uvp.sim.gb28181.SnapShotUploadUrlValidator
import com.uvp.sim.observability.ErrorCategory
import com.uvp.sim.observability.LogLevel
import com.uvp.sim.observability.LogTag
import com.uvp.sim.observability.SystemLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

/**
 * 抓拍序列状态机:
 *   每张图: takeJpeg → writeCache → uploadWithRetry(0+3 次) → **记入成功清单**
 *   失败终态(upload 4 次全失败): 不记入清单,继续下一张(spec AC5)
 *   takeJpeg 返 null: 跳过本张,继续下一张
 *   **整批结束后**: 发**一条** A.2.5.7 图像抓拍传输完成通知
 *
 * 串行执行 [SnapShotConfig.snapNum] 张,张间 [SnapShotConfig.intervalMs] 延迟。
 * 入口 [start] fire-and-forget。
 *
 * ⭐ **2026-09-19 改为"整批一条"**（原先每张图发一条自造形态的 NOTIFY）:
 *
 *   标准 A.2.5.7 的语义是「**传输完成**通知」，`SnapShotList/SnapShotFileID` 带的是
 *   **成功上传的**文件标识列表，标准原话：
 *   「无文件标识或文件标识个数**少于要求抓拍的文件个数**，表示全部或部分抓拍或上传
 *   操作异常失败」—— 也就是说**平台判"部分失败"的唯一依据是列表长度与要求张数的差额**。
 *   原先每张一条、且不带 `SnapShotList`，这个判断能力等于不存在。
 *
 *   ⇒ 现在：[SnapShotConfig.snapNum] 张全部走完后发一条，
 *   列表长度 < `snapNum` 即"部分失败"（空列表 = 全部失败），
 *   与 [SnapshotProgress.Finished] 的 `uploaded`/`requested` 一起给 UI 与日志。
 *
 * P2-6 (audit §3) — UploadURL 严格校验:
 *   [start] 前先用 [SnapShotUploadUrlValidator.isValidUploadUrlStrict] 校验 uploadUrl,
 *   不在 [uploadAllowList] 或属危险地址时拒绝整个序列,记 SystemLogger Error。
 *
 * 依赖以 lambda 注入便于测试(避免 commonTest 不能 anonymous override expect class):
 *   - [takeJpeg]: 调用 SnapshotCapture.takeJpeg
 *   - [writeCache]: 调用 JpegLocalCache.write,返回 storagePath
 *   - [uploader]: HTTP PUT 客户端
 *   - [notifySender]: 把构造好的 XML 发出去(SimulatorEngine 注入 buildMessage+transport.send)
 */
class SnapshotUploadEngine(
    private val takeJpeg: suspend () -> ByteArray?,
    private val writeCache: suspend (snapShotId: String, bytes: ByteArray) -> String,
    private val uploader: SnapshotHttpUploader,
    private val notifySender: suspend (xml: String) -> Unit,
    private val scope: CoroutineScope,
    private val deviceId: String,
    private val snAllocator: () -> String,
    private val uploadAllowList: List<String> = emptyList(),
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val onProgress: ((SnapshotProgress) -> Unit)? = null,
    private val retryDelaysMs: List<Long> = listOf(1_000L, 2_000L, 4_000L)
) {

    fun start(cfg: SnapShotConfig): Job = scope.launch {
        // P2-6: strict allowList check before any upload
        if (!SnapShotUploadUrlValidator.isValidUploadUrlStrict(cfg.uploadUrl, uploadAllowList)) {
            SystemLogger.emit(
                LogLevel.Warning,
                LogTag.Network,
                "UploadURL ${cfg.uploadUrl} not in allow list or is dangerous address — reject SnapShotConfig SessionID=${cfg.sessionId}",
                category = ErrorCategory.Permanent
            )
            onProgress?.invoke(SnapshotProgress.UrlRejected(cfg.sessionId, cfg.uploadUrl))
            return@launch
        }

        // 成功上传的抓拍标识，顺序即抓拍顺序 —— 它就是 A.2.5.7 `SnapShotList` 的内容。
        val uploaded = mutableListOf<String>()
        for (idx in 0 until cfg.snapNum) {
            if (idx > 0 && cfg.intervalMs > 0) delay(cfg.intervalMs)
            // R3 round-2 #3 (MEDIUM/error_handling):processOne 只特化了 takeJpeg()==null 和 uploadFailed,
            // 任何 writeCache / notifySender 抛错都会冒泡终结整个 snapNum 序列。
            // 改:用 try/catch 隔离单帧失败 → 发 PerShotError 进度 + 继续下一帧。CancellationException 仍冒泡。
            try {
                processOne(cfg, idx, uploaded)
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (t: Throwable) {
                onProgress?.invoke(SnapshotProgress.PerShotError(cfg.sessionId, idx, t.message ?: t::class.simpleName ?: "unknown"))
            }
        }
        // ⭐ 整批结束才发通知（标准是"传输完成通知"，不是逐张通知）。
        sendFinishNotify(cfg, uploaded)
    }

    /**
     * 单张：抓图 → 落本地缓存 → 上传（0+3 次重试）→ 成功则记入 [uploaded]。
     *
     * ⛔ **本函数不发任何 SIP 报文**。发报文是整批结束后 [sendFinishNotify] 的事 ——
     * 在这里发就会退化成"每张一条"，而标准要的是每批一条、靠列表长度表达部分失败。
     */
    private suspend fun processOne(cfg: SnapShotConfig, idx: Int, uploaded: MutableList<String>) {
        val snapShotId = generateSnapShotId(idx)

        val jpeg = takeJpeg()
        if (jpeg == null) {
            onProgress?.invoke(SnapshotProgress.CaptureSkipped(cfg.sessionId, snapShotId))
            return
        }

        // 本地缓存仍要写（模拟中心的抓拍卡片按它列文件），但**不进报文** ——
        // A.2.5.7 里没有 `StoragePath` 这个元素，原先发它属于自造。
        writeCache(snapShotId, jpeg)

        val uploadOk = uploadWithRetry(cfg.uploadUrl, jpeg, snapShotId)
        if (!uploadOk) {
            onProgress?.invoke(SnapshotProgress.UploadFailedFinal(cfg.sessionId, snapShotId))
            return
        }

        uploaded += snapShotId
        onProgress?.invoke(
            SnapshotProgress.Uploaded(cfg.sessionId, snapShotId, uploaded.size, cfg.snapNum)
        )
    }

    /**
     * A.2.5.7 图像抓拍传输完成通知（整批一条）。
     *
     * 发送失败**不上抛**：抓拍本身已经完成并落盘，把整批当作失败会让 UI 显示"抓拍失败"
     * 而实际图片都在 —— 记一条 TransportError 供排障即可。
     */
    private suspend fun sendFinishNotify(cfg: SnapShotConfig, uploaded: List<String>) {
        try {
            val xml = SnapShotNotifyBuilder.build(
                deviceId = deviceId,
                sn = snAllocator(),
                sessionId = cfg.sessionId,
                fileIds = uploaded,
            )
            notifySender(xml)
            SystemLogger.emit(
                LogLevel.Info, LogTag.Network,
                "抓拍完成通知 UploadSnapShotFinished → SessionID=${cfg.sessionId} " +
                    "成功 ${uploaded.size}/${cfg.snapNum} 张",
            )
            onProgress?.invoke(
                SnapshotProgress.Finished(cfg.sessionId, uploaded.size, cfg.snapNum)
            )
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (t: Throwable) {
            onProgress?.invoke(
                SnapshotProgress.FinishNotifyFailed(cfg.sessionId, t.message ?: t::class.simpleName ?: "unknown")
            )
        }
    }

    /** 第 0 次直发,失败按 retryDelaysMs 顺序退避重试,全失败返 false */
    private suspend fun uploadWithRetry(uploadUrl: String, jpeg: ByteArray, snapShotId: String): Boolean {
        var attempt = 0
        while (true) {
            val result = uploader.put(uploadUrl, jpeg, snapShotId)
            if (result is UploadResult.Success) return true
            if (attempt >= retryDelaysMs.size) return false
            delay(retryDelaysMs[attempt])
            attempt += 1
        }
    }

    private fun generateSnapShotId(idx: Int): String {
        val ldt: kotlinx.datetime.LocalDateTime =
            kotlin.time.Instant.fromEpochMilliseconds(nowMs())
                .toLocalDateTime(kotlinx.datetime.TimeZone.currentSystemDefault())
        return buildString {
            append(ldt.year.toString().padStart(4, '0'))
            append(ldt.monthNumber.toString().padStart(2, '0'))
            append(ldt.dayOfMonth.toString().padStart(2, '0'))
            append('T')
            append(ldt.hour.toString().padStart(2, '0'))
            append(ldt.minute.toString().padStart(2, '0'))
            append(ldt.second.toString().padStart(2, '0'))
            append('_')
            append(idx)
        }
    }
}

sealed class SnapshotProgress {
    data class CaptureSkipped(val sessionId: String, val snapShotId: String) : SnapshotProgress()
    data class UploadFailedFinal(val sessionId: String, val snapShotId: String) : SnapshotProgress()

    /**
     * 单张上传成功（**尚未**发通知）。`count` = 至此累计成功张数，`total` = 本次要求张数。
     *
     * ⛔ 原名叫 `NotifySent` —— 那时每张图发一条报文，名字是对的；现在报文改成整批一条，
     * 沿用旧名会让"看到 NotifySent 就以为平台已收到"这个误判留在调用方。
     */
    data class Uploaded(
        val sessionId: String,
        val snapShotId: String,
        val count: Int,
        val total: Int
    ) : SnapshotProgress()

    /**
     * 整批结束、`UploadSnapShotFinished` 已发出。
     *
     * `uploaded < requested` 即标准所说的"全部或部分抓拍或上传操作异常失败"。
     */
    data class Finished(
        val sessionId: String,
        val uploaded: Int,
        val requested: Int
    ) : SnapshotProgress()

    /**
     * 整批结束但**通知没发出去**（传输层失败）。图片本身已抓已传，与抓拍失败要分开报。
     */
    data class FinishNotifyFailed(val sessionId: String, val cause: String) : SnapshotProgress()

    /**
     * P2-6 — UploadURL 未通过 allowList 或属危险地址,整个抓拍序列被拒。
     */
    data class UrlRejected(val sessionId: String, val uploadUrl: String) : SnapshotProgress()

    /**
     * R3 round-2 #3 — 单帧 processOne 抛非 cancellation 异常(writeCache / notifySender 等),
     * 上层 catch 后发本事件并继续下一帧,而不是整个 snapNum 序列被中断。
     */
    data class PerShotError(val sessionId: String, val idx: Int, val cause: String) : SnapshotProgress()
}
