package com.uvp.sim.config

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T7 测试:扫码回填 SIP 接入信息 — shared 层。
 *
 * 覆盖 tasks 文档 T7 的 27 条用例(7.1-7.27):
 *   7.1-7.7   QrTokenParser  — 只认 fragment 形式的 token
 *   7.8-7.17  QrProvisionClient — envelope 解析 + 状态码映射 + 取消冒泡 + 不跟随重定向
 *   7.18-7.27 QrPayloadValidator — 六元组完整校验
 */
class QrProvisionTest {

    private val validToken = "AbCdEfGhIjKlMnOpQrStUv" // 22 字符 base64url

    private fun okPayloadJson(
        serverId: String = "34020000002000000001",
        domain: String = "3402000000",
        ip: String = "192.168.1.10",
        port: Int = 5060,
        transport: String = "udp",
        password: String = "uvp-secret"
    ) = """
        {"code":0,"message":"ok","data":{"serverId":"$serverId","domain":"$domain",
        "ip":"$ip","port":$port,"transport":"$transport","password":"$password"}}
    """.trimIndent()

    private fun payload(
        serverId: String = "34020000002000000001",
        domain: String = "3402000000",
        ip: String = "192.168.1.10",
        port: Int = 5060,
        transport: String = "udp",
        password: String = "uvp-secret"
    ) = QrProvisionPayload(serverId, domain, ip, port, transport, password)

    private fun jsonClient(
        captured: MutableList<HttpRequestData>? = null,
        status: HttpStatusCode = HttpStatusCode.OK,
        body: String = "",
        headers: io.ktor.http.Headers = headersOf(
            HttpHeaders.ContentType,
            ContentType.Application.Json.toString()
        )
    ): HttpClient = QrProvisionClient.newHttpClient(
        MockEngine { req ->
            captured?.add(req)
            respond(content = body, status = status, headers = headers)
        }
    )

    private fun throwingClient(error: Throwable): HttpClient =
        QrProvisionClient.newHttpClient(MockEngine { throw error })

    // ---------- QrTokenParser 7.1-7.7 ----------

    // 7.1 解析 fragment token
    @Test
    fun `parses token from http fragment`() {
        val target = QrTokenParser.parseQrScan("http://192.168.1.10:8280/gb28181/qr#t=$validToken")
        assertNotNull(target)
        assertEquals("http://192.168.1.10:8280", target.baseUrl)
        assertEquals(validToken, target.token)
    }

    // 7.2 https 亦可
    @Test
    fun `parses token from https fragment`() {
        val target = QrTokenParser.parseQrScan("https://uvp.example.com/gb28181/qr#t=$validToken")
        assertNotNull(target)
        assertEquals("https://uvp.example.com", target.baseUrl)
        assertEquals(validToken, target.token)
    }

    // 7.3 query 形式必须拒绝(D6 回归防线)
    @Test
    fun `rejects query form token`() {
        assertNull(QrTokenParser.parseQrScan("http://192.168.1.10:8280/gb28181/qr?t=$validToken"))
    }

    // 7.4 非 URL 拒绝
    @Test
    fun `rejects non url text`() {
        assertNull(QrTokenParser.parseQrScan("hello world"))
    }

    // 7.5 无 fragment 拒绝
    @Test
    fun `rejects url without fragment`() {
        assertNull(QrTokenParser.parseQrScan("http://192.168.1.10:8280/gb28181/qr"))
    }

    // 7.6 空字符串
    @Test
    fun `rejects empty string`() {
        assertNull(QrTokenParser.parseQrScan(""))
    }

    // 7.7 token 格式非法
    @Test
    fun `rejects malformed token in fragment`() {
        assertNull(QrTokenParser.parseQrScan("http://192.168.1.10:8280/gb28181/qr#t=abc"))
    }

    // ---------- QrProvisionClient 7.8-7.17 ----------

    // 7.8 正常兑换解 envelope
    @Test
    fun `exchange unwraps envelope on success`() = runTest {
        val captured = mutableListOf<HttpRequestData>()
        val client = QrProvisionClient(jsonClient(captured, body = okPayloadJson()))
        val result = client.exchange("http://192.168.1.10:8280", validToken)
        assertIs<QrFetchResult.Success>(result)
        assertEquals("34020000002000000001", result.payload.serverId)
        assertEquals("3402000000", result.payload.domain)
        assertEquals("192.168.1.10", result.payload.ip)
        assertEquals(5060, result.payload.port)
        assertEquals("udp", result.payload.transport)
        assertEquals("uvp-secret", result.payload.password)
        val url = captured.single().url.toString()
        assertTrue(url.endsWith("/api/gb28181/sip/qr/exchange"), "got=$url")
    }

    // 7.9 HTTP 200 但 code != 0
    @Test
    fun `exchange maps non zero business code to invalidated`() = runTest {
        val body = """{"code":1,"message":"二维码已失效,请在平台重新生成"}"""
        val client = QrProvisionClient(jsonClient(body = body))
        assertIs<QrFetchResult.Invalidated>(client.exchange("http://h:8280", validToken))
    }

    // 7.10 data 为 null
    @Test
    fun `exchange maps null data to malformed`() = runTest {
        val client = QrProvisionClient(jsonClient(body = """{"code":0,"message":"ok","data":null}"""))
        assertIs<QrFetchResult.Malformed>(client.exchange("http://h:8280", validToken))
    }

    // 7.11 410 映射
    @Test
    fun `exchange maps 410 to invalidated`() = runTest {
        val client = QrProvisionClient(
            jsonClient(
                status = HttpStatusCode.Gone,
                body = """{"code":1,"message":"二维码已失效,请在平台重新生成"}"""
            )
        )
        assertIs<QrFetchResult.Invalidated>(client.exchange("http://h:8280", validToken))
    }

    // 7.12 400 映射
    @Test
    fun `exchange maps 400 to malformed`() = runTest {
        val client = QrProvisionClient(
            jsonClient(
                status = HttpStatusCode.BadRequest,
                body = """{"code":1,"message":"二维码内容已损坏"}"""
            )
        )
        assertIs<QrFetchResult.Malformed>(client.exchange("http://h:8280", validToken))
    }

    // 7.13 500 映射 ServerError,不是 NetworkError
    @Test
    fun `exchange maps 500 to server error not network error`() = runTest {
        val client = QrProvisionClient(jsonClient(status = HttpStatusCode.InternalServerError, body = ""))
        val result = client.exchange("http://h:8280", validToken)
        assertIs<QrFetchResult.ServerError>(result)
        assertEquals(500, result.status)
    }

    // 7.14 连接失败
    @Test
    fun `exchange maps io failure to network error`() = runTest {
        val client = QrProvisionClient(throwingClient(RuntimeException("connection refused")))
        val result = client.exchange("http://h:8280", validToken)
        assertIs<QrFetchResult.NetworkError>(result)
        assertTrue(result.message.contains("connection refused"), "got=${result.message}")
    }

    // 7.15 多余字段不炸
    @Test
    fun `exchange ignores unknown fields`() = runTest {
        val body = """
            {"code":0,"message":"ok","traceId":"abc","data":{"serverId":"34020000002000000001",
            "domain":"3402000000","ip":"192.168.1.10","port":5060,"transport":"udp",
            "password":"uvp-secret","extra":true}}
        """.trimIndent()
        val client = QrProvisionClient(jsonClient(body = body))
        assertIs<QrFetchResult.Success>(client.exchange("http://h:8280", validToken))
    }

    // 7.16 取消必须冒泡
    @Test
    fun `exchange rethrows cancellation`() = runTest {
        val client = QrProvisionClient(throwingClient(CancellationException("scope cancelled")))
        var thrown: Throwable? = null
        try {
            client.exchange("http://h:8280", validToken)
        } catch (ce: CancellationException) {
            thrown = ce
        }
        assertNotNull(thrown, "CancellationException 必须重抛,不能包成 NetworkError")
    }

    // 7.17 不跟随重定向(防跨 host 绕过用户看到的 host)
    @Test
    fun `exchange does not follow redirect to another host`() = runTest {
        val captured = mutableListOf<HttpRequestData>()
        val client = QrProvisionClient(
            QrProvisionClient.newHttpClient(
                MockEngine { req ->
                    captured.add(req)
                    if (captured.size == 1) {
                        respond(
                            content = "",
                            status = HttpStatusCode.Found,
                            headers = headersOf(HttpHeaders.Location, "http://evil.example.com/api/gb28181/sip/qr/exchange")
                        )
                    } else {
                        respondError(HttpStatusCode.OK)
                    }
                }
            )
        )
        val result = client.exchange("http://192.168.1.10:8280", validToken)
        assertEquals(1, captured.size, "不能跟随重定向发第二个请求")
        assertTrue(result !is QrFetchResult.Success, "重定向不能算成功,got=$result")
    }

    // ---------- QrPayloadValidator 7.18-7.27 ----------

    // 7.18 serverId 19 位
    @Test
    fun `validator rejects 19 digit serverId`() {
        val msg = QrPayloadValidator.validate(payload(serverId = "3402000000200000000"))
        assertNotNull(msg)
        assertTrue(msg.isNotBlank())
    }

    // 7.19 domain 非 10 位
    @Test
    fun `validator rejects wrong length domain`() {
        assertNotNull(QrPayloadValidator.validate(payload(domain = "340200000")))
    }

    // 7.20 port 0
    @Test
    fun `validator rejects port zero`() {
        assertNotNull(QrPayloadValidator.validate(payload(port = 0)))
    }

    // 7.21 port 65536
    @Test
    fun `validator rejects port above range`() {
        assertNotNull(QrPayloadValidator.validate(payload(port = 65536)))
    }

    // 7.22 port 65535 合法
    @Test
    fun `validator accepts port 65535`() {
        assertNull(QrPayloadValidator.validate(payload(port = 65535)))
    }

    // 7.23 transport 未知值
    @Test
    fun `validator rejects unknown transport`() {
        assertNotNull(QrPayloadValidator.validate(payload(transport = "sctp")))
    }

    // 7.24 transport 大写通过(大小写不敏感)
    @Test
    fun `validator accepts uppercase transport`() {
        assertNull(QrPayloadValidator.validate(payload(transport = "UDP")))
        assertNull(QrPayloadValidator.validate(payload(transport = "TCP")))
    }

    // 7.25 密码空
    @Test
    fun `validator rejects blank password`() {
        assertNotNull(QrPayloadValidator.validate(payload(password = "")))
    }

    // 7.26 ip 空
    @Test
    fun `validator rejects blank ip`() {
        assertNotNull(QrPayloadValidator.validate(payload(ip = "")))
    }

    // 7.27 全合法
    @Test
    fun `validator accepts valid payload`() {
        assertNull(QrPayloadValidator.validate(payload()))
    }
}
