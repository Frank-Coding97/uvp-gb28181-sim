package com.uvp.sim.snapshot

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class SnapshotHttpUploaderTest {

    private val sampleBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x42)

    private fun client(
        captured: MutableList<HttpRequestData>? = null,
        respondWith: (HttpRequestData) -> HttpStatusCode
    ): HttpClient = HttpClient(MockEngine) {
        engine {
            addHandler { req ->
                captured?.add(req)
                respond(content = ByteReadChannel.Empty, status = respondWith(req), headers = headersOf())
            }
        }
    }

    private fun throwingClient(error: Throwable): HttpClient = HttpClient(MockEngine) {
        engine { addHandler { throw error } }
    }

    // T7.1
    @Test
    fun returns_success_on_200() = runTest {
        val uploader = SnapshotHttpUploader(client { HttpStatusCode.OK })
        val result = uploader.put("http://h:8088/snap/", sampleBytes, "snap_001")
        assertEquals(UploadResult.Success, result)
    }

    // T7.2
    @Test
    fun returns_failure_on_404() = runTest {
        val uploader = SnapshotHttpUploader(client { HttpStatusCode.NotFound })
        val result = uploader.put("http://h:8088/snap/", sampleBytes, "snap_001")
        assertIs<UploadResult.Failure>(result)
        assertEquals(404, result.statusCode)
    }

    // T7.3
    @Test
    fun returns_failure_on_500() = runTest {
        val uploader = SnapshotHttpUploader(client { HttpStatusCode.InternalServerError })
        val result = uploader.put("http://h:8088/snap/", sampleBytes, "snap_001")
        assertIs<UploadResult.Failure>(result)
        assertEquals(500, result.statusCode)
    }

    // T7.4
    @Test
    fun returns_failure_on_exception() = runTest {
        val uploader = SnapshotHttpUploader(throwingClient(RuntimeException("simulated network down")))
        val result = uploader.put("http://h:8088/snap/", sampleBytes, "snap_001")
        assertIs<UploadResult.Failure>(result)
        assertEquals(null, result.statusCode)
        assertTrue(result.cause.contains("simulated"), "cause must contain exception message")
    }

    // T7.5
    @Test
    fun appends_filename_when_url_ends_with_slash() = runTest {
        val captured = mutableListOf<HttpRequestData>()
        val uploader = SnapshotHttpUploader(client(captured) { HttpStatusCode.OK })
        uploader.put("http://h:8088/snap/", sampleBytes, "snap_xyz")
        val u = captured.single().url.toString()
        assertTrue(u.endsWith("/snap/snap_xyz.jpg"), "got=$u")
    }

    // T7.6
    @Test
    fun does_not_append_when_url_is_complete_path() = runTest {
        val captured = mutableListOf<HttpRequestData>()
        val uploader = SnapshotHttpUploader(client(captured) { HttpStatusCode.OK })
        uploader.put("http://h:8088/snap/snap_xyz.jpg", sampleBytes, "ignored")
        val u = captured.single().url.toString()
        assertTrue(u.endsWith("/snap_xyz.jpg"), "got=$u")
        assertTrue(!u.contains("ignored"), "fileName must not appear in complete-path mode")
    }

    // T7.7
    @Test
    fun sets_content_type_image_jpeg() = runTest {
        val captured = mutableListOf<HttpRequestData>()
        val uploader = SnapshotHttpUploader(client(captured) { HttpStatusCode.OK })
        uploader.put("http://h:8088/snap/", sampleBytes, "snap_001")
        val ct = captured.single().body.contentType?.toString() ?: captured.single().headers[HttpHeaders.ContentType]
        assertTrue(ct?.startsWith("image/jpeg") == true, "got=$ct")
    }
}
