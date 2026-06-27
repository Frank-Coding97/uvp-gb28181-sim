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
 * P2-6 (audit §3) — SnapshotUploadEngine allowList 集成测试。
 *
 * 验证:
 *  - allowList 命中时正常上传
 *  - allowList 未命中时拒绝整个序列,不调 uploader
 *  - 危险地址(loopback / link-local)即使在 allowList 也拒绝
 *  - 空 allowList 拒绝任意 URL(零信任默认)
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SnapshotUploadEngineAllowListTest {

    private fun cfg(uploadUrl: String) = SnapShotConfig(
        sessionId = "S001",
        uploadUrl = uploadUrl,
        snapNum = 1,
        intervalMs = 0L
    )

    private inner class Harness(
        val allowList: List<String>,
        scope: kotlinx.coroutines.CoroutineScope,
        val mockResponses: List<HttpStatusCode> = listOf(HttpStatusCode.OK)
    ) {
        var uploaderCalls = 0
            private set
        val sentNotifies = mutableListOf<String>()
        val progress = mutableListOf<SnapshotProgress>()
        private var snCounter = 1

        private val client = HttpClient(MockEngine) {
            engine {
                addHandler {
                    uploaderCalls += 1
                    val status = if (mockResponses.isNotEmpty()) mockResponses[0] else HttpStatusCode.OK
                    respond(content = ByteReadChannel.Empty, status = status, headers = headersOf())
                }
            }
        }

        val engine = SnapshotUploadEngine(
            takeJpeg = { byteArrayOf(0xFF.toByte()) },
            writeCache = { id, _ -> "/tmp/$id.jpg" },
            uploader = SnapshotHttpUploader(client),
            notifySender = { xml -> sentNotifies.add(xml) },
            scope = scope,
            deviceId = "34020000001320000001",
            snAllocator = { (snCounter++).toString() },
            uploadAllowList = allowList,
            nowMs = { 1_700_000_000_000L },
            onProgress = { progress.add(it) },
            retryDelaysMs = listOf(10L, 20L, 40L)
        )
    }

    // T1 — allowList 命中:正常上传 + NOTIFY
    @Test
    fun allows_upload_when_host_in_allow_list() = runTest {
        val h = Harness(allowList = listOf("192.168.1.10"), scope = this)
        h.engine.start(cfg("http://192.168.1.10:8088/snap/")).join()
        assertEquals(1, h.uploaderCalls, "uploader should be called")
        assertEquals(1, h.sentNotifies.size, "NOTIFY should be sent")
        assertEquals(1, h.progress.count { it is SnapshotProgress.NotifySent })
        assertEquals(0, h.progress.count { it is SnapshotProgress.UrlRejected })
    }

    // T2 — allowList 未命中:拒绝,不调 uploader
    @Test
    fun rejects_upload_when_host_not_in_allow_list() = runTest {
        val h = Harness(allowList = listOf("allowed.com"), scope = this)
        h.engine.start(cfg("http://notallowed.com/snap/")).join()
        assertEquals(0, h.uploaderCalls, "uploader should NOT be called")
        assertEquals(0, h.sentNotifies.size, "no NOTIFY should be sent")
        assertEquals(1, h.progress.count { it is SnapshotProgress.UrlRejected })
    }

    // T3 — allowList 空(零信任):拒绝任意 URL
    @Test
    fun rejects_any_url_when_allow_list_empty() = runTest {
        val h = Harness(allowList = emptyList(), scope = this)
        h.engine.start(cfg("http://192.168.1.10/snap/")).join()
        assertEquals(0, h.uploaderCalls)
        assertEquals(0, h.sentNotifies.size)
        assertEquals(1, h.progress.count { it is SnapshotProgress.UrlRejected })
    }

    // T4 — loopback 即使在 allowList 也拒绝
    @Test
    fun rejects_loopback_even_if_in_allow_list() = runTest {
        val h = Harness(allowList = listOf("127.0.0.1"), scope = this)
        h.engine.start(cfg("http://127.0.0.1:8088/snap/")).join()
        assertEquals(0, h.uploaderCalls)
        assertEquals(0, h.sentNotifies.size)
        assertEquals(1, h.progress.count { it is SnapshotProgress.UrlRejected })
    }

    // T5 — link-local 即使在 allowList 也拒绝
    @Test
    fun rejects_link_local_even_if_in_allow_list() = runTest {
        val h = Harness(allowList = listOf("169.254.169.254"), scope = this)
        h.engine.start(cfg("http://169.254.169.254/latest/meta-data/")).join()
        assertEquals(0, h.uploaderCalls)
        assertEquals(0, h.sentNotifies.size)
        assertEquals(1, h.progress.count { it is SnapshotProgress.UrlRejected })
    }

    // T6 — localhost 字面量即使在 allowList 也拒绝
    @Test
    fun rejects_localhost_even_if_in_allow_list() = runTest {
        val h = Harness(allowList = listOf("localhost"), scope = this)
        h.engine.start(cfg("http://localhost:8080/snap/")).join()
        assertEquals(0, h.uploaderCalls)
        assertEquals(0, h.sentNotifies.size)
        assertEquals(1, h.progress.count { it is SnapshotProgress.UrlRejected })
    }

    // T7 — IPv6 loopback 拒绝
    @Test
    fun rejects_ipv6_loopback() = runTest {
        val h = Harness(allowList = listOf("::1"), scope = this)
        h.engine.start(cfg("http://[::1]/snap/")).join()
        assertEquals(0, h.uploaderCalls)
        assertEquals(0, h.sentNotifies.size)
        assertEquals(1, h.progress.count { it is SnapshotProgress.UrlRejected })
    }

    // T8 — 多 host allowList:命中第二个
    @Test
    fun allows_when_matching_any_allow_list_entry() = runTest {
        val h = Harness(
            allowList = listOf("first.com", "192.168.1.20", "third.com"),
            scope = this
        )
        h.engine.start(cfg("http://192.168.1.20:9000/snap/")).join()
        assertEquals(1, h.uploaderCalls)
        assertEquals(1, h.sentNotifies.size)
    }

    // T9 — https 也受 allowList 约束
    @Test
    fun enforces_allow_list_for_https() = runTest {
        val h = Harness(allowList = listOf("secure.example.com"), scope = this)
        h.engine.start(cfg("https://secure.example.com/upload")).join()
        assertEquals(1, h.uploaderCalls)
        assertEquals(1, h.sentNotifies.size)

        // not in list → reject
        val h2 = Harness(allowList = listOf("other.com"), scope = this)
        h2.engine.start(cfg("https://secure.example.com/upload")).join()
        assertEquals(0, h2.uploaderCalls)
    }

    // T10 — 拒绝后 progress 含 UrlRejected 事件
    @Test
    fun emits_url_rejected_progress_when_rejected() = runTest {
        val h = Harness(allowList = listOf("allowed.com"), scope = this)
        h.engine.start(cfg("http://bad.com/snap/")).join()
        val rejected = h.progress.filterIsInstance<SnapshotProgress.UrlRejected>()
        assertEquals(1, rejected.size)
        assertEquals("S001", rejected[0].sessionId)
        assertEquals("http://bad.com/snap/", rejected[0].uploadUrl)
    }

    // T11 — snapNum=3,URL 被拒后不调 uploader 任何一张
    @Test
    fun rejects_entire_sequence_when_url_rejected() = runTest {
        val h = Harness(allowList = emptyList(), scope = this)
        val cfg = SnapShotConfig(
            sessionId = "S002",
            uploadUrl = "http://any.com/",
            snapNum = 3,
            intervalMs = 0L
        )
        h.engine.start(cfg).join()
        assertEquals(0, h.uploaderCalls, "no shots should be uploaded")
        assertEquals(0, h.sentNotifies.size, "no NOTIFY should be sent")
        assertTrue(h.progress.any { it is SnapshotProgress.UrlRejected })
    }

    // T12 — 域名匹配区分大小写?不,host 提取后按字面量匹配
    @Test
    fun host_matching_is_case_sensitive() = runTest {
        // allowList 中是 "Example.COM",URL 是 "example.com" → 不匹配(精确字面量)
        val h = Harness(allowList = listOf("Example.COM"), scope = this)
        h.engine.start(cfg("http://example.com/snap/")).join()
        assertEquals(0, h.uploaderCalls, "case-sensitive literal match should fail")

        // 精确匹配
        val h2 = Harness(allowList = listOf("example.com"), scope = this)
        h2.engine.start(cfg("http://example.com/snap/")).join()
        assertEquals(1, h2.uploaderCalls, "exact match should pass")
    }
}
