package com.uvp.sim.snapshot

import com.uvp.sim.gb28181.SnapShotConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest

/**
 * 抓拍序列状态机测试。
 *
 * ⭐ 2026-09-19 断言整体改写：报文从「每张一条」变成**整批一条** A.2.5.7 通知。
 * 原先几乎每个用例都断言 `sentNotifies.size == snapNum` —— 那个数字本身就是在固化
 * 错误形态（平台会收到 N 条谁都匹配不上的自造报文）。现在：
 *
 *  - `sentNotifies.size` 恒为 **1**（只要序列跑完且 URL 通过校验）；
 *  - 单张的进展看 `SnapshotProgress.Uploaded` 的 `count`；
 *  - 整批的结果看 `SnapshotProgress.Finished` 的 `uploaded / requested`
 *    —— `uploaded < requested` 就是标准所说的"部分失败"。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SnapshotUploadEngineTest {

    private fun cfg(snapNum: Int = 1, intervalMs: Long = 0L) = SnapShotConfig(
        sessionId = "S001",
        uploadUrl = "http://h:8088/snap/",
        snapNum = snapNum,
        intervalMs = intervalMs,
        // A.2.5.7 的 SessionID 标准限 32~128 字节；本引擎原样透传，这里给足长度以贴近真实。
        // （不裁剪是有意的：SessionID 由平台下发、用于关联抓拍请求。）
    )

    private fun mockUploader(responses: List<HttpStatusCode>): SnapshotHttpUploader {
        val q = ArrayDeque(responses)
        val client = HttpClient(MockEngine) {
            engine {
                addHandler {
                    val s = if (q.isNotEmpty()) q.removeFirst() else HttpStatusCode.OK
                    respond(content = ByteReadChannel.Empty, status = s, headers = headersOf())
                }
            }
        }
        return SnapshotHttpUploader(client)
    }

    private inner class Harness(
        captureBytes: List<ByteArray?> = listOf(byteArrayOf(0xFF.toByte())),
        responses: List<HttpStatusCode> = listOf(HttpStatusCode.OK),
        scope: kotlinx.coroutines.CoroutineScope,
        retryDelays: List<Long> = listOf(1_000L, 2_000L, 4_000L),
        notifyFails: Boolean = false,
    ) {
        private val captureQueue = ArrayDeque(captureBytes)
        var captureCalls = 0
            private set
        val cacheWrites = mutableListOf<Pair<String, ByteArray>>()
        val sentNotifies = mutableListOf<String>()
        val progress = mutableListOf<SnapshotProgress>()
        private var snCounter = 1

        val engine: SnapshotUploadEngine = SnapshotUploadEngine(
            takeJpeg = {
                captureCalls += 1
                if (captureQueue.isNotEmpty()) captureQueue.removeFirst() else byteArrayOf(0xFF.toByte())
            },
            writeCache = { id, bytes ->
                cacheWrites.add(id to bytes)
                "/tmp/$id.jpg"
            },
            uploader = mockUploader(responses),
            notifySender = { xml ->
                if (notifyFails) throw IllegalStateException("transport down")
                sentNotifies.add(xml)
            },
            scope = scope,
            deviceId = "34020000001320000001",
            snAllocator = { (snCounter++).toString() },
            uploadAllowList = listOf("h"), // test URL host is "h"
            nowMs = { 1_700_000_000_000L + cacheWrites.size * 1000L },
            onProgress = { progress.add(it) },
            retryDelaysMs = retryDelays
        )
    }

    private fun Harness.uploaded(): List<SnapshotProgress.Uploaded> =
        progress.filterIsInstance<SnapshotProgress.Uploaded>()

    private fun Harness.finished(): SnapshotProgress.Finished =
        progress.filterIsInstance<SnapshotProgress.Finished>().single()

    // T8.1
    @Test
    fun snap_num_1_happy_path() = runTest {
        val h = Harness(scope = this)
        h.engine.start(cfg(snapNum = 1)).join()
        assertEquals(1, h.captureCalls)
        assertEquals(1, h.cacheWrites.size)
        assertEquals(1, h.sentNotifies.size, "整批一条通知")
        assertEquals(1, h.uploaded().size)
        assertEquals(SnapshotProgress.Finished("S001", uploaded = 1, requested = 1), h.finished())
    }

    // T8.2 —— ⭐ 关键语义变更：3 张也只发 1 条通知
    @Test
    fun snap_num_3_sendsExactlyOneBatchNotify() = runTest {
        val h = Harness(
            captureBytes = listOf(byteArrayOf(1), byteArrayOf(2), byteArrayOf(3)),
            responses = listOf(HttpStatusCode.OK, HttpStatusCode.OK, HttpStatusCode.OK),
            scope = this
        )
        h.engine.start(cfg(snapNum = 3)).join()
        assertEquals(3, h.captureCalls)
        assertEquals(3, h.cacheWrites.size)
        assertEquals(1, h.sentNotifies.size, "A.2.5.7 是「传输完成」通知，整批一条而不是每张一条")
        assertEquals(3, h.uploaded().size)
        assertEquals(SnapshotProgress.Finished("S001", uploaded = 3, requested = 3), h.finished())
    }

    /** 通知里必须带上**全部**成功上传的标识——平台判部分失败的唯一依据就是列表长度。 */
    @Test
    fun batchNotify_carriesAllUploadedFileIds() = runTest {
        val h = Harness(
            captureBytes = listOf(byteArrayOf(1), byteArrayOf(2), byteArrayOf(3)),
            responses = listOf(HttpStatusCode.OK, HttpStatusCode.OK, HttpStatusCode.OK),
            scope = this
        )
        h.engine.start(cfg(snapNum = 3)).join()
        val xml = h.sentNotifies.single()
        assertTrue(xml.contains("<CmdType>UploadSnapShotFinished</CmdType>"), "actual: $xml")
        assertEquals(3, Regex("<SnapShotFileID>").findAll(xml).count(), "actual: $xml")
        // 与 SnapshotProgress.Uploaded 报出的标识是同一批
        h.uploaded().forEach { assertTrue(xml.contains("<SnapShotFileID>${it.snapShotId}</SnapShotFileID>")) }
    }

    // T8.3
    @Test
    fun capture_null_skips_that_index_continues_next() = runTest {
        val h = Harness(
            captureBytes = listOf(null, byteArrayOf(2)),
            responses = listOf(HttpStatusCode.OK),
            scope = this
        )
        h.engine.start(cfg(snapNum = 2)).join()
        assertEquals(2, h.captureCalls)
        assertEquals(1, h.cacheWrites.size, "only the non-null shot writes cache")
        assertEquals(1, h.uploaded().size)
        assertEquals(1, h.progress.count { it is SnapshotProgress.CaptureSkipped })
        // ⭐ 抓拍失败一张，但通知**仍然发**（且列表只有 1 条）——
        //    这正是平台能看出"部分失败"的方式。
        assertEquals(1, h.sentNotifies.size, "部分失败也要发通知，否则平台只会一直等")
        assertEquals(SnapshotProgress.Finished("S001", uploaded = 1, requested = 2), h.finished())
    }

    // T8.4 — 第 1 次 500,第 2 次 200 OK
    @Test
    fun retry_succeeds_on_second_attempt() = runTest {
        val h = Harness(
            responses = listOf(HttpStatusCode.InternalServerError, HttpStatusCode.OK),
            scope = this,
            retryDelays = listOf(10L, 20L, 40L)
        )
        h.engine.start(cfg(snapNum = 1)).join()
        assertEquals(1, h.sentNotifies.size, "NOTIFY sent after retry")
        assertEquals(1, h.uploaded().size)
    }

    // T8.5 — 第 1 张 4 次都失败,第 2 张成功
    @Test
    fun four_failures_skip_onlyThatShot_andNotifyHoldsOneId() = runTest {
        val h = Harness(
            captureBytes = listOf(byteArrayOf(1), byteArrayOf(2)),
            // 4 次失败 = 1 直发 + 3 次 retry;然后第 2 张成功
            responses = listOf(
                HttpStatusCode.InternalServerError,
                HttpStatusCode.InternalServerError,
                HttpStatusCode.InternalServerError,
                HttpStatusCode.InternalServerError,
                HttpStatusCode.OK
            ),
            scope = this,
            retryDelays = listOf(10L, 20L, 40L)
        )
        h.engine.start(cfg(snapNum = 2)).join()
        assertEquals(1, h.progress.count { it is SnapshotProgress.UploadFailedFinal })
        assertEquals(1, h.uploaded().size)
        assertEquals(1, h.sentNotifies.size)
        assertEquals(
            1, Regex("<SnapShotFileID>").findAll(h.sentNotifies.single()).count(),
            "只有第 2 张成功，列表里就只该有它"
        )
        assertEquals(SnapshotProgress.Finished("S001", uploaded = 1, requested = 2), h.finished())
    }

    /** 整批**全失败** → 仍然发通知，且是 `<SnapShotList/>`（标准允许且必须这样表达）。 */
    @Test
    fun allShotsFail_stillSendsEmptySnapShotList() = runTest {
        val h = Harness(
            captureBytes = listOf(byteArrayOf(1)),
            responses = List(4) { HttpStatusCode.InternalServerError },
            scope = this,
            retryDelays = listOf(10L, 20L, 40L)
        )
        h.engine.start(cfg(snapNum = 1)).join()
        assertEquals(1, h.sentNotifies.size, "全失败也要发通知（空列表=全部失败）")
        assertTrue(h.sentNotifies.single().contains("<SnapShotList/>"), "actual: ${h.sentNotifies.single()}")
        assertEquals(SnapshotProgress.Finished("S001", uploaded = 0, requested = 1), h.finished())
    }

    /**
     * 通知发送失败**不得**把整批当失败：图片已抓已传，从 UI 看应是"抓拍成功但通知没出去"。
     */
    @Test
    fun notifyTransportFailure_reportsFinishNotifyFailed_notFinished() = runTest {
        val h = Harness(scope = this, notifyFails = true)
        h.engine.start(cfg(snapNum = 1)).join()
        assertEquals(0, h.sentNotifies.size)
        assertEquals(1, h.cacheWrites.size, "图片确实抓了也落了盘")
        assertEquals(1, h.progress.count { it is SnapshotProgress.FinishNotifyFailed })
        assertEquals(0, h.progress.count { it is SnapshotProgress.Finished })
    }

    // T8.6 — intervalMs=0 完成
    @Test
    fun interval_zero_completes_immediately() = runTest {
        val h = Harness(
            captureBytes = listOf(byteArrayOf(1), byteArrayOf(2)),
            responses = listOf(HttpStatusCode.OK, HttpStatusCode.OK),
            scope = this
        )
        h.engine.start(cfg(snapNum = 2, intervalMs = 0L)).join()
        assertEquals(1, h.sentNotifies.size)
        assertEquals(2, h.uploaded().size)
    }

    // T8.7 — intervalMs=1000,虚拟时间至少前进 1000ms
    @Test
    fun interval_applied_between_shots() = runTest {
        val scope = this
        val h = Harness(
            captureBytes = listOf(byteArrayOf(1), byteArrayOf(2)),
            responses = listOf(HttpStatusCode.OK, HttpStatusCode.OK),
            scope = scope
        )
        val before = scope.testScheduler.currentTime
        h.engine.start(cfg(snapNum = 2, intervalMs = 1000L)).join()
        val elapsed = scope.testScheduler.currentTime - before
        assertTrue(elapsed >= 1000L, "must delay >= intervalMs between shots, got $elapsed")
        assertEquals(1, h.sentNotifies.size)
    }

    // T8.8 snapShotId 唯一
    @Test
    fun snap_shot_ids_are_unique_across_shots() = runTest {
        val h = Harness(
            captureBytes = listOf(byteArrayOf(1), byteArrayOf(2), byteArrayOf(3)),
            responses = listOf(HttpStatusCode.OK, HttpStatusCode.OK, HttpStatusCode.OK),
            scope = this
        )
        h.engine.start(cfg(snapNum = 3)).join()
        val ids = h.uploaded().map { it.snapShotId }
        assertEquals(3, ids.size)
        assertEquals(ids.toSet().size, ids.size, "all snapShotIds unique")
    }

    /** 单张上报的 count 必须递增到 total，UI 的进度条才有东西可画。 */
    @Test
    fun uploadedProgress_countsUpToTotal() = runTest {
        val h = Harness(
            captureBytes = listOf(byteArrayOf(1), byteArrayOf(2), byteArrayOf(3)),
            responses = listOf(HttpStatusCode.OK, HttpStatusCode.OK, HttpStatusCode.OK),
            scope = this
        )
        h.engine.start(cfg(snapNum = 3)).join()
        assertEquals(listOf(1, 2, 3), h.uploaded().map { it.count })
        assertTrue(h.uploaded().all { it.total == 3 })
        assertEquals("S001", h.uploaded().first().sessionId)
    }
}
