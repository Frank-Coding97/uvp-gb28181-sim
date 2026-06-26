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

/**
 * DTO → shared SipMessage 反向重建(PR-A-3:从 SipLogTab 搬来).
 *
 * 当下唯一调用方:`SipLogTab.toFlowEventsForExport()`,需把 [SipMessageDto] 喂给
 * `SipFlowEvent`(`shared.observability` 域,内部签名仍是 `SipMessage`)。
 * `SipDialogGrouping.group()` 是纯算法 + UI 不依赖 sip 类型,长期治理可让
 * `SipFlowEvent` 改用 DTO,届时本反向 helper 可彻底删除。
 *
 * 字段无损映射:Request 仅取 method 名做 `SipMethod.valueOf` 反查;`body` 是 UTF-8
 * decoded String,`encodeToByteArray()` 反向(SDP / MANSCDP+xml 均为 UTF-8 文本,实际无损).
 */
fun SipMessageDto.toSipMessage(): SipMessage {
    val sharedHeaders = headers.map { SipMessage.Header(it.name, it.value) }
    val bodyBytes = body.encodeToByteArray()
    return when (this) {
        is SipMessageDto.Request -> SipRequest(
            method = SipMethod.valueOf(method.name),
            requestUri = requestUri,
            sipVersion = sipVersion,
            headers = sharedHeaders,
            body = bodyBytes,
        )
        is SipMessageDto.Response -> SipResponse(
            statusCode = statusCode,
            reasonPhrase = reasonPhrase,
            sipVersion = sipVersion,
            headers = sharedHeaders,
            body = bodyBytes,
        )
    }
}
