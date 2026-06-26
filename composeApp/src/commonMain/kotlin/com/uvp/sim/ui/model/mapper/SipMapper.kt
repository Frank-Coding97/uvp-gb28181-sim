package com.uvp.sim.ui.model.mapper

import com.uvp.sim.sip.SipMessage
import com.uvp.sim.sip.SipMethod
import com.uvp.sim.sip.SipRequest
import com.uvp.sim.sip.SipResponse
import com.uvp.sim.sip.SipState
import com.uvp.sim.ui.model.SipMessageDto
import com.uvp.sim.ui.model.SipMethodDto
import com.uvp.sim.ui.model.SipStateDto

/**
 * SIP 消息 / 方法 / 状态 domain → DTO 单向映射. PR-A T2.2.
 *
 * ByteArray.body 升格 String (plan §1.4): UTF-8 解码, Compose 永不见二进制.
 * SipState / SipMethod 是 enum, valueOf(name) 1:1 映射.
 */

fun SipMessage.toDto(): SipMessageDto = when (this) {
    is SipRequest -> SipMessageDto.Request(
        method = method.toDto(),
        requestUri = requestUri,
        sipVersion = sipVersion,
        headers = headers.map { it.toDto() },
        body = body.decodeToString(),
    )
    is SipResponse -> SipMessageDto.Response(
        statusCode = statusCode,
        reasonPhrase = reasonPhrase,
        sipVersion = sipVersion,
        headers = headers.map { it.toDto() },
        body = body.decodeToString(),
    )
}

fun SipMessage.Header.toDto(): SipMessageDto.Header =
    SipMessageDto.Header(name, value)

fun SipMethod.toDto(): SipMethodDto = SipMethodDto.valueOf(name)

fun SipState.toDto(): SipStateDto = SipStateDto.valueOf(name)
