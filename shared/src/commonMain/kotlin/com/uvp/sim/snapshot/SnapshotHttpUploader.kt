package com.uvp.sim.snapshot

import io.ktor.client.HttpClient
import io.ktor.client.request.headers
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders

/**
 * 抓拍 JPEG HTTP 上传结果。
 */
sealed class UploadResult {
    object Success : UploadResult()
    data class Failure(val statusCode: Int?, val cause: String) : UploadResult()
}

/**
 * 抓拍 JPEG HTTP 上传器。
 *
 * 单一职责:把 JPEG 字节 PUT 到平台下发的 UploadURL。重试 / 状态机 / NOTIFY 逻辑在
 * [SnapshotUploadEngine] 层。
 *
 * URL 末尾带 `/` 视为目录,自动追加 `${fileName}.jpg`;否则视为完整路径。
 */
class SnapshotHttpUploader(private val client: HttpClient) {

    suspend fun put(uploadUrl: String, bytes: ByteArray, fileName: String): UploadResult {
        val targetUrl = resolveTargetUrl(uploadUrl, fileName)
        return try {
            val response = client.put(targetUrl) {
                headers { append(HttpHeaders.ContentType, ContentType.Image.JPEG.toString()) }
                setBody(bytes)
            }
            val code = response.status.value
            if (code in 200..299) UploadResult.Success
            else UploadResult.Failure(code, response.status.description)
        } catch (e: Throwable) {
            UploadResult.Failure(null, e.message ?: e::class.simpleName ?: "unknown")
        }
    }

    private fun resolveTargetUrl(baseUrl: String, fileName: String): String =
        if (baseUrl.endsWith('/')) "$baseUrl$fileName.jpg" else baseUrl
}
