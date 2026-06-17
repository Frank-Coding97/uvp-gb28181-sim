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

@OptIn(ExperimentalCoroutinesApi::class)
class SnapshotUploadEngineTest {

    private fun cfg(snapNum: Int = 1, intervalMs: Long = 0L) = SnapShotConfig(
        sessionId = "S001",
        uploadUrl = "http://h:8088/snap/",
        snapNum = snapNum,
        intervalMs = intervalMs
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
        retryDelays: List<Long> = listOf(1_000L, 2_000L, 4_000L)
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
            notifySender = { xml -> sentNotifies.add(xml) },
            scope = scope,
            deviceId = "34020000001320000001",
            snAllocator = { (snCounter++).toString() },
            nowMs = { 1_700_000_000_000L + sentNotifies.size * 1000L + progress.size * 100L },
            onProgress = { progress.add(it) },
            retryDelaysMs = retryDelays
        )
    }

    // T8.1
    @Test
    fun snap_num_1_happy_path() = runTest {
        val h = Harness(scope = this)
        h.engine.start(cfg(snapNum = 1)).join()
        assertEquals(1, h.captureCalls)
        assertEquals(1, h.cacheWrites.size)
        assertEquals(1, h.sentNotifies.size)
        assertEquals(1, h.progress.count { it is SnapshotProgress.NotifySent })
    }

    // T8.2
    @Test
    fun snap_num_3_happy_path() = runTest {
        val h = Harness(
            captureBytes = listOf(byteArrayOf(1), byteArrayOf(2), byteArrayOf(3)),
            responses = listOf(HttpStatusCode.OK, HttpStatusCode.OK, HttpStatusCode.OK),
            scope = this
        )
        h.engine.start(cfg(snapNum = 3)).join()
        assertEquals(3, h.captureCalls)
        assertEquals(3, h.cacheWrites.size)
        assertEquals(3, h.sentNotifies.size)
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
        assertEquals(1, h.sentNotifies.size, "only the non-null shot sends NOTIFY")
        assertEquals(1, h.progress.count { it is SnapshotProgress.CaptureSkipped })
        assertEquals(1, h.progress.count { it is SnapshotProgress.NotifySent })
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
    }

    // T8.5 — 第 1 张 4 次都失败,第 2 张成功
    @Test
    fun four_failures_skip_notify_continue_next() = runTest {
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
        assertEquals(1, h.sentNotifies.size, "only 2nd shot NOTIFY")
        assertEquals(1, h.progress.count { it is SnapshotProgress.UploadFailedFinal })
        assertEquals(1, h.progress.count { it is SnapshotProgress.NotifySent })
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
        assertEquals(2, h.sentNotifies.size)
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
        assertEquals(2, h.sentNotifies.size)
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
        val ids = h.progress.filterIsInstance<SnapshotProgress.NotifySent>().map { it.snapShotId }
        assertEquals(3, ids.size)
        assertEquals(ids.toSet().size, ids.size, "all snapShotIds unique")
    }
}
