package com.uvp.sim.config

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json

/**
 * 扫码兑换结果。
 *
 * [ServerError] 与 [NetworkError] 必须分开:500 是平台内部错误,归到"网络错误"会让用户
 * 去查手机 Wi-Fi,而真正该看的是平台日志(plan §5.9)。
 */
sealed class QrFetchResult {
    data class Success(val payload: QrProvisionPayload) : QrFetchResult()

    /** token 已过期 / 已被消费(HTTP 410,或 200 + 业务 code≠0)。 */
    data class Invalidated(val message: String) : QrFetchResult()

    /** 二维码内容损坏 / 响应结构不符(HTTP 400,或 code==0 但 data 为空)。 */
    data class Malformed(val message: String) : QrFetchResult()

    /** 平台内部错误(5xx 及其他非预期状态码)。 */
    data class ServerError(val status: Int) : QrFetchResult()

    /** 连不上 / 超时 / 解析异常。 */
    data class NetworkError(val message: String) : QrFetchResult()
}

/**
 * 一次性 token → SIP 六元组(plan §5.3/§5.4)。
 *
 * 形状照 [com.uvp.sim.snapshot.SnapshotHttpUploader]:构造注入 [HttpClient],
 * 结果用 sealed class,client 生命周期由宿主(SipViewModel / IosAppHost)管理。
 */
class QrProvisionClient(private val client: HttpClient) {

    suspend fun exchange(baseUrl: String, token: String): QrFetchResult {
        val url = baseUrl.trimEnd('/') + EXCHANGE_PATH
        return try {
            val response = client.post(url) {
                headers { append(HttpHeaders.ContentType, ContentType.Application.Json.toString()) }
                setBody(json.encodeToString(QrExchangeRequest.serializer(), QrExchangeRequest(token)))
            }
            val status = response.status.value
            val text = response.bodyAsText()
            when {
                status == 200 -> parseEnvelope(text)
                status == 410 -> QrFetchResult.Invalidated(messageOf(text, INVALIDATED_FALLBACK))
                status == 400 -> QrFetchResult.Malformed(messageOf(text, MALFORMED_FALLBACK))
                else -> QrFetchResult.ServerError(status)
            }
        } catch (ce: CancellationException) {
            // 同 SnapshotHttpUploader.kt:39-43 的 cross-review 教训:scope/job 取消必须冒泡,
            // 不能伪装成 NetworkError,否则上层会把"取消"当网络故障提示用户检查 Wi-Fi。
            throw ce
        } catch (e: Throwable) {
            QrFetchResult.NetworkError(e.message ?: e::class.simpleName ?: "unknown")
        }
    }

    /**
     * 解 envelope(评审 CRITICAL):平台返回 `{code, message, data}`,不是裸六元组。
     * `code == 0 && data != null` 才算成功。
     */
    private fun parseEnvelope(text: String): QrFetchResult {
        val envelope = runCatching {
            json.decodeFromString(QrExchangeResponse.serializer(), text)
        }.getOrNull() ?: return QrFetchResult.Malformed(MALFORMED_FALLBACK)

        if (envelope.code != 0) {
            return QrFetchResult.Invalidated(envelope.message.ifBlank { INVALIDATED_FALLBACK })
        }
        val payload = envelope.data ?: return QrFetchResult.Malformed(MALFORMED_FALLBACK)
        return QrFetchResult.Success(payload)
    }

    private fun messageOf(text: String, fallback: String): String =
        runCatching {
            json.decodeFromString(QrExchangeResponse.serializer(), text).message
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: fallback

    companion object {
        private const val EXCHANGE_PATH = "/api/gb28181/sip/qr/exchange"
        private const val INVALIDATED_FALLBACK = "二维码已失效,请在平台重新生成"
        private const val MALFORMED_FALLBACK = "二维码内容已损坏"

        private val json = Json { ignoreUnknownKeys = true }

        /**
         * 建兑换专用 [HttpClient]。
         *
         * `followRedirects = false` 是安全要求(plan §5.5):否则平台侧一个 302 就能把
         * 带 token 的请求转到用户在确认页**没看到**的 host,绕过整套 host 信任边界。
         */
        fun newHttpClient(engine: HttpClientEngine): HttpClient =
            HttpClient(engine) { followRedirects = false }
    }
}
