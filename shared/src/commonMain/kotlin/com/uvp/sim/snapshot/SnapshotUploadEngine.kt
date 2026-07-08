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
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * 抓拍序列状态机:
 *   每张图: takeJpeg → writeCache → uploadWithRetry(0+3 次) → buildNotify → sendNotify
 *   失败终态(upload 4 次全失败): 不发 NOTIFY,继续下一张(spec AC5)
 *   takeJpeg 返 null: 跳过本张,继续下一张
 *
 * 串行执行 [SnapShotConfig.snapNum] 张,张间 [SnapShotConfig.intervalMs] 延迟。
 * 入口 [start] fire-and-forget。
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

        for (idx in 0 until cfg.snapNum) {
            if (idx > 0 && cfg.intervalMs > 0) delay(cfg.intervalMs)
            // R3 round-2 #3 (MEDIUM/error_handling):processOne 只特化了 takeJpeg()==null 和 uploadFailed,
            // 任何 writeCache / notifySender 抛错都会冒泡终结整个 snapNum 序列。
            // 改:用 try/catch 隔离单帧失败 → 发 PerShotError 进度 + 继续下一帧。CancellationException 仍冒泡。
            try {
                processOne(cfg, idx)
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (t: Throwable) {
                onProgress?.invoke(SnapshotProgress.PerShotError(cfg.sessionId, idx, t.message ?: t::class.simpleName ?: "unknown"))
            }
        }
    }

    private suspend fun processOne(cfg: SnapShotConfig, idx: Int) {
        val snapShotId = generateSnapShotId(idx)
        val timeIso = generateTimeIso()

        val jpeg = takeJpeg()
        if (jpeg == null) {
            onProgress?.invoke(SnapshotProgress.CaptureSkipped(cfg.sessionId, snapShotId))
            return
        }

        val storagePath = writeCache(snapShotId, jpeg)

        val uploadOk = uploadWithRetry(cfg.uploadUrl, jpeg, snapShotId)
        if (!uploadOk) {
            onProgress?.invoke(SnapshotProgress.UploadFailedFinal(cfg.sessionId, snapShotId))
            return
        }

        val xml = SnapShotNotifyBuilder.build(
            deviceId = deviceId,
            sn = snAllocator(),
            sessionId = cfg.sessionId,
            snapShotId = snapShotId,
            timeIso = timeIso,
            storagePath = storagePath
        )
        notifySender(xml)
        onProgress?.invoke(
            SnapshotProgress.NotifySent(cfg.sessionId, snapShotId, idx + 1, cfg.snapNum)
        )
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
        val ldt: LocalDateTime = Instant.fromEpochMilliseconds(nowMs())
            .toLocalDateTime(TimeZone.currentSystemDefault())
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

    private fun generateTimeIso(): String {
        val ldt: LocalDateTime = Instant.fromEpochMilliseconds(nowMs())
            .toLocalDateTime(TimeZone.UTC)
        val ms = ldt.nanosecond / 1_000_000
        return buildString {
            append(ldt.year.toString().padStart(4, '0'))
            append('-')
            append(ldt.monthNumber.toString().padStart(2, '0'))
            append('-')
            append(ldt.dayOfMonth.toString().padStart(2, '0'))
            append('T')
            append(ldt.hour.toString().padStart(2, '0'))
            append(':')
            append(ldt.minute.toString().padStart(2, '0'))
            append(':')
            append(ldt.second.toString().padStart(2, '0'))
            append('.')
            append(ms.toString().padStart(3, '0'))
            append('Z')
        }
    }
}

sealed class SnapshotProgress {
    data class CaptureSkipped(val sessionId: String, val snapShotId: String) : SnapshotProgress()
    data class UploadFailedFinal(val sessionId: String, val snapShotId: String) : SnapshotProgress()
    data class NotifySent(
        val sessionId: String,
        val snapShotId: String,
        val count: Int,
        val total: Int
    ) : SnapshotProgress()
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
