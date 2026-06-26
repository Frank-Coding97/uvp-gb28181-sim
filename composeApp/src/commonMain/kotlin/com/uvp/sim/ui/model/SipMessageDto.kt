package com.uvp.sim.ui.model

/**
 * UI 层 SIP 消息 DTO. T-A2 实施完成后, UI 不再 import com.uvp.sim.sip.SipMessage.
 * body 字段从 ByteArray 升格为 String(预 UTF-8 解码,Compose 永不见二进制).
 */
sealed class SipMessageDto {
    abstract val headers: List<Header>
    abstract val body: String
    abstract val sipVersion: String

    data class Header(val name: String, val value: String)

    data class Request(
        val method: SipMethodDto,
        val requestUri: String,
        override val sipVersion: String,
        override val headers: List<Header>,
        override val body: String,
    ) : SipMessageDto()

    data class Response(
        val statusCode: Int,
        val reasonPhrase: String,
        override val sipVersion: String,
        override val headers: List<Header>,
        override val body: String,
    ) : SipMessageDto()
}
