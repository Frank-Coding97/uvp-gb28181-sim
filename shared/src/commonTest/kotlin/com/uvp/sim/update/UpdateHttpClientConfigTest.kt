package com.uvp.sim.update

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 盯住「升级用 HttpClient 能建出来、能发请求」这两件事。
 *
 * 背景：v1.0.12 里 `requestTimeoutMillis` 被写成 0（想表达"不限时"），
 * Ktor 判非法并抛 `IllegalArgumentException`，而异常发生在**构造 HttpClient 时**，
 * 于是整个客户端建不出来 —— 界面上表现为每次检查更新都报「无法连接更新服务器」。
 * 当时的单测只覆盖清单解析，HTTP 这一层完全没测，所以全绿上线。
 *
 * ⛔ 这两条用例的价值在于：只要有人再把超时字段写成非法值，构造就抛，
 * 测试立刻红，不会等到装机才发现。
 */
class UpdateHttpClientConfigTest {

    @Test
    fun `config is accepted by ktor`() {
        // 构造过程不抛异常，就说明超时字段都是合法值（0 会在这里炸）。
        val client = HttpClient(MockEngine { respond("ok") }) {
            expectSuccess = false
            applyUpdateTimeouts()
        }
        client.close()
    }

    @Test
    fun `client built with this config can perform a request`() = runTest {
        val payload = """{"version":"1.0.13"}"""
        val engine = MockEngine {
            respond(
                content = payload,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val client = HttpClient(engine) {
            expectSuccess = false
            applyUpdateTimeouts()
        }
        val text = client.get("https://example.com/upgrades/android/stable/latest.json").bodyAsText()
        assertEquals(payload, text)
        client.close()
    }

    @Test
    fun `timeouts used by the update client are positive`() {
        // 配置里出现非正数时 Ktor 会拒绝，这里在配置层面再挡一次。
        assertEquals(true, UPDATE_CONNECT_TIMEOUT_MS > 0)
        assertEquals(true, UPDATE_SOCKET_TIMEOUT_MS > 0)
    }
}
