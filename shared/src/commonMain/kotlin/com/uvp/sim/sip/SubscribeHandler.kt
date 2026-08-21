package com.uvp.sim.sip

import com.uvp.sim.gb28181.ManscdpParser

sealed class SubscribeIntent {
    data class NewSubscription(
        val kind: String,
        val subscriberUri: String,
        val notifierUri: String,
        val notifyRequestUri: String,
        val event: String,
        val callId: String,
        val fromTag: String,
        val intervalSeconds: Int,
        val expiresSeconds: Int
    ) : SubscribeIntent()

    data class Refresh(
        val callId: String,
        val fromTag: String,
        val newExpiresSeconds: Int
    ) : SubscribeIntent()

    data class Cancel(
        val callId: String,
        val fromTag: String
    ) : SubscribeIntent()

    data class Reject(
        val statusCode: Int,
        val reason: String
    ) : SubscribeIntent()

    data class Ignored(
        val cmdType: String
    ) : SubscribeIntent()
}

object SubscribeHandler {

    private const val DEFAULT_EXPIRES_MOBILE_POSITION = 3600
    private const val DEFAULT_EXPIRES_CATALOG = 600    // GB/T 28181-2016 附录 P.2.1 默认 600s
    private const val DEFAULT_EXPIRES_ALARM = 3600     // GB §9.5.2 报警订阅,比 Catalog 短(spec Q7)
    private const val DEFAULT_EXPIRES_PTZ_POSITION = 3600

    fun parse(request: SipRequest, knownCallIds: Set<String>): SubscribeIntent {
        val event = request.firstHeader(SipHeader.EVENT)
        // GB/T 28181-2016 的订阅示例既有 Event:presence(J.20),也有
        // 附录 P.4.1 的 Event:Catalog;id=num;对话内必须原样回显收到的 Event。
        // 移动位置使用 presence,报警使用 presence/Alarm 取决于对端实现。
        // GB/T 28181-2022 PTZ 精准位置订阅:Event:PTZPosition。
        // 四种都接受,具体 kind:Alarm 直接由 Event 头判定(报警 SUBSCRIBE
        // body 可能不带标准 CmdType),presence/Catalog 看 body CmdType 再确认。
        // Event 头允许带参数,如 "presence;id=xxx"、"PTZPosition;id=yyy",取分号前主标识。
        val eventName = event?.trim()?.substringBefore(';')?.trim()
        val isAlarmEvent = eventName != null && eventName.equals("Alarm", ignoreCase = true)
        val isPtzPositionEvent = eventName != null && eventName.equals("PTZPosition", ignoreCase = true)
        val eventOk = eventName != null && (
            eventName.equals("presence", ignoreCase = true) ||
                eventName.equals("Catalog", ignoreCase = true) ||
                isPtzPositionEvent ||
                isAlarmEvent
            )
        if (!eventOk) {
            return SubscribeIntent.Reject(489, "Bad Event: ${event ?: "(missing)"}")
        }

        val expiresHeader = request.firstHeader(SipHeader.EXPIRES)?.trim()?.toIntOrNull()

        val callId = request.callId() ?: return SubscribeIntent.Reject(400, "Missing Call-ID")

        // R2 #6:Refresh / Cancel 不能只看 Call-ID,把入站 From tag 带出去给 router 跟已注册
        // dialog.fromTag 对比,防止同链路下其他订阅者凭 Call-ID 复用 / 旁路掉别人的 dialog。
        val incomingFromTag = extractTag(request.fromHeader() ?: "") ?: ""

        if (expiresHeader == 0 && callId in knownCallIds) {
            return SubscribeIntent.Cancel(callId, incomingFromTag)
        }

        if (expiresHeader != null && expiresHeader > 0 && callId in knownCallIds) {
            return SubscribeIntent.Refresh(callId, incomingFromTag, expiresHeader)
        }

        val fromHeader = request.fromHeader() ?: ""
        val subscriberUri = extractUri(fromHeader)
        val notifierUri = extractUri(
            request.toHeader() ?: return SubscribeIntent.Reject(400, "Missing To")
        )
        if (SipHeaderHelpers.parseUriUser(notifierUri).isBlank()) {
            return SubscribeIntent.Reject(400, "To URI must contain device identity")
        }
        val notifyRequestUri = request.firstHeader(SipHeader.CONTACT)
            ?.let(::extractUri)
            ?.takeIf { it.isNotBlank() }
            ?: return SubscribeIntent.Reject(400, "Missing Contact")
        val fromTag = incomingFromTag

        // Event: Alarm 短路 — 报警是事件流,不依赖 body CmdType,不周期推送(interval=0)。
        if (isAlarmEvent) {
            return SubscribeIntent.NewSubscription(
                kind = "Alarm",
                subscriberUri = subscriberUri,
                notifierUri = notifierUri,
                notifyRequestUri = notifyRequestUri,
                event = event.trim(),
                callId = callId,
                fromTag = fromTag,
                intervalSeconds = 0,
                expiresSeconds = expiresHeader ?: DEFAULT_EXPIRES_ALARM
            )
        }

        val xml = request.body.decodeToString()
        val cmdType = ManscdpParser.cmdType(xml)
            ?: return SubscribeIntent.Reject(400, "Missing CmdType in body")
        if (isPtzPositionEvent && !cmdType.equals("PTZPosition", ignoreCase = true)) {
            return SubscribeIntent.Reject(400, "Event PTZPosition requires CmdType PTZPosition")
        }

        // WVP-Pro 报警订阅:Event: presence + body <CmdType>Alarm</CmdType>(不是裸 Event:Alarm)
        val kind = when {
            cmdType.equals("MobilePosition", ignoreCase = true) -> "MobilePosition"
            cmdType.equals("Catalog", ignoreCase = true) -> "Catalog"
            cmdType.equals("Alarm", ignoreCase = true) -> "Alarm"
            cmdType.equals("PTZPosition", ignoreCase = true) -> "PtzPrecisePosition"
            else -> return SubscribeIntent.Ignored(cmdType)
        }

        val defaultExpires = when (kind) {
            "Catalog" -> DEFAULT_EXPIRES_CATALOG
            "Alarm" -> DEFAULT_EXPIRES_ALARM
            "PtzPrecisePosition" -> DEFAULT_EXPIRES_PTZ_POSITION
            else -> DEFAULT_EXPIRES_MOBILE_POSITION
        }
        val expires = expiresHeader ?: defaultExpires

        // Interval 对 Catalog / Alarm 不适用(不周期推送),仍解析便于日志
        val intervalStr = ManscdpParser.tagValue(xml, "Interval")
        val interval = intervalStr?.toIntOrNull() ?: when (kind) {
            "Catalog", "Alarm", "PtzPrecisePosition" -> 0
            else -> 30
        }

        return SubscribeIntent.NewSubscription(
            kind = kind,
            subscriberUri = subscriberUri,
            notifierUri = notifierUri,
            notifyRequestUri = notifyRequestUri,
            event = event.trim(),
            callId = callId,
            fromTag = fromTag,
            intervalSeconds = interval,
            expiresSeconds = expires
        )
    }

    private fun extractUri(header: String): String {
        val start = header.indexOf('<')
        val end = header.indexOf('>')
        return if (start >= 0 && end > start) header.substring(start + 1, end) else header.trim()
    }

    private fun extractTag(header: String): String? {
        val tagIdx = header.indexOf(";tag=")
        if (tagIdx < 0) return null
        val afterTag = header.substring(tagIdx + 5)
        val endIdx = afterTag.indexOfFirst { it == ';' || it == '>' || it == ' ' }
        return if (endIdx >= 0) afterTag.substring(0, endIdx) else afterTag
    }
}
