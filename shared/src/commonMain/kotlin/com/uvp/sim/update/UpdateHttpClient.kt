package com.uvp.sim.update

import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig

/**
 * 升级用的 HTTP 超时策略 —— 两个平台共用一份，不要各写一遍。
 *
 * ⛔ 为什么单独抽出来：`requestTimeoutMillis` **不接受 0**。Ktor 只允许正数，
 * 表示「不限时」必须用 [HttpTimeout.INFINITE_TIMEOUT_MS]。写成 0 抛的是
 * `IllegalArgumentException: Only positive timeout values are allowed`，
 * 而且抛出点在 **HttpClient 构造阶段** —— 客户端根本建不出来，
 * 于是「检查更新」永远回「无法连接更新服务器」。
 *
 * 这个坑只在真机 release 包上暴露过（单测不碰 HTTP 层），
 * 所以这里配了 [com.uvp.sim.update.UpdateHttpClientConfigTest] 盯着。
 */
fun HttpClientConfig<*>.applyUpdateTimeouts() {
    install(HttpTimeout) {
        connectTimeoutMillis = UPDATE_CONNECT_TIMEOUT_MS
        socketTimeoutMillis = UPDATE_SOCKET_TIMEOUT_MS
        // 36MB 安装包在弱网下可能要几分钟，整体请求不能设上限；
        // 清单这种小请求由调用方用 request 级 timeout 单独卡住。
        requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
    }
}

/** 建连超时。 */
const val UPDATE_CONNECT_TIMEOUT_MS = 15_000L

/** 单次读写阻塞超时（不能等于整体超时，否则弱网大包会被误杀）。 */
const val UPDATE_SOCKET_TIMEOUT_MS = 30_000L
