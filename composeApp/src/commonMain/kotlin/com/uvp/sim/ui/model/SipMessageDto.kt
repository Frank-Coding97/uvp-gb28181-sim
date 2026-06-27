package com.uvp.sim.ui.model

/**
 * UI 层 SIP 消息 DTO. T-A2 实施完成后, UI 不再 import com.uvp.sim.sip.SipMessage.
 * body 字段从 ByteArray 升格为 String(预 UTF-8 解码,Compose 永不见二进制).
 *
 * P2-7:双字段设计 — [headers] 是原始值(业务路径如 Coordinator 做 Digest auth 需要),
 * [redactedHeaders] 是脱敏版(UI 显示/复制/导出用)。Mapper 生成时同时填充两份,
 * UI 默认拿 redactedHeaders,避免 Authorization 等敏感头泄露到剪贴板/分享。
 */
sealed class SipMessageDto {
    abstract val headers: List<Header>
    abstract val redactedHeaders: List<Header>
    abstract val body: String
    abstract val sipVersion: String

    data class Header(val name: String, val value: String)

    data class Request(
        val method: SipMethodDto,
        val requestUri: String,
        override val sipVersion: String,
        override val headers: List<Header>,
        override val redactedHeaders: List<Header>,
        override val body: String,
    ) : SipMessageDto()

    data class Response(
        val statusCode: Int,
        val reasonPhrase: String,
        override val sipVersion: String,
        override val headers: List<Header>,
        override val redactedHeaders: List<Header>,
        override val body: String,
    ) : SipMessageDto()
}
