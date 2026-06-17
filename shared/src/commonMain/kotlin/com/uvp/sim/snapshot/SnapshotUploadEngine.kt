package com.uvp.sim.snapshot

import com.uvp.sim.gb28181.SnapShotConfig
import com.uvp.sim.gb28181.SnapShotNotifyBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
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
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val onProgress: ((SnapshotProgress) -> Unit)? = null,
    private val retryDelaysMs: List<Long> = listOf(1_000L, 2_000L, 4_000L)
) {

    fun start(cfg: SnapShotConfig): Job = scope.launch {
        for (idx in 0 until cfg.snapNum) {
            if (idx > 0 && cfg.intervalMs > 0) delay(cfg.intervalMs)
            processOne(cfg, idx)
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
        return "%04d%02d%02dT%02d%02d%02d_%d".format(
            ldt.year, ldt.monthNumber, ldt.dayOfMonth,
            ldt.hour, ldt.minute, ldt.second,
            idx
        )
    }

    private fun generateTimeIso(): String {
        val ldt: LocalDateTime = Instant.fromEpochMilliseconds(nowMs())
            .toLocalDateTime(TimeZone.UTC)
        return "%04d-%02d-%02dT%02d:%02d:%02d.%03dZ".format(
            ldt.year, ldt.monthNumber, ldt.dayOfMonth,
            ldt.hour, ldt.minute, ldt.second, ldt.nanosecond / 1_000_000
        )
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
}
