package com.uvp.sim.config

import kotlinx.serialization.Serializable

/**
 * 平台扫码兑换返回的 SIP 接入六元组(plan §5.3)。
 *
 * 字段名与平台 `POST /api/gb28181/sip/qr/exchange` 响应 `data` 段严格一致。
 * `transport` 是平台原始字符串("udp"/"tcp",大小写不定),落盘前由
 * [QrPayloadValidator] 校验、由调用方转成 [com.uvp.sim.network.TransportType]。
 */
@Serializable
data class QrProvisionPayload(
    val serverId: String,
    val domain: String,
    val ip: String,
    val port: Int,
    val transport: String,
    val password: String,
)

/**
 * 平台响应 envelope(plan §5.4,评审 CRITICAL)。
 *
 * 平台所有 API 都是 `{code, message, data}` 三层包装,**不是**裸六元组。
 * 判定成功必须同时满足 `code == 0 && data != null` —— 项目响应拦截器的惯例
 * 正是"HTTP 200 + 业务 code 非 0"表示失败。
 */
@Serializable
data class QrExchangeResponse(
    val code: Int,
    val message: String = "",
    val data: QrProvisionPayload? = null,
)

/** 兑换请求体:token 走 POST body,不进 query(plan §2.2)。 */
@Serializable
data class QrExchangeRequest(val token: String)
