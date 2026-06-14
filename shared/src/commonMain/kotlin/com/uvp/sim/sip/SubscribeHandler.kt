package com.uvp.sim.sip

import com.uvp.sim.gb28181.ManscdpParser

sealed class SubscribeIntent {
    data class NewSubscription(
        val kind: String,
        val subscriberUri: String,
        val callId: String,
        val fromTag: String,
        val intervalSeconds: Int,
        val expiresSeconds: Int
    ) : SubscribeIntent()

    data class Refresh(
        val callId: String,
        val newExpiresSeconds: Int
    ) : SubscribeIntent()

    data class Cancel(
        val callId: String
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
    private const val DEFAULT_EXPIRES_CATALOG = 86400  // GB §9.3.1 目录订阅典型 24h

    fun parse(request: SipRequest, knownCallIds: Set<String>): SubscribeIntent {
        val event = request.firstHeader(SipHeader.EVENT)
        // GB28181 不同订阅类型的 Event 头不同:
        //   §9.3.1.2 目录订阅:    Event: Catalog
        //   §9.3.5   移动位置订阅: Event: presence
        // 两种都接受,具体 kind 后面看 body CmdType 再确认。
        // Event 头允许带参数,如 "presence;id=xxx"、"Catalog;id=yyy",取分号前主标识。
        val eventName = event?.trim()?.substringBefore(';')?.trim()
        val eventOk = eventName != null && (
            eventName.equals("presence", ignoreCase = true) ||
                eventName.equals("Catalog", ignoreCase = true)
            )
        if (!eventOk) {
            return SubscribeIntent.Reject(489, "Bad Event: ${event ?: "(missing)"}")
        }

        val expiresHeader = request.firstHeader(SipHeader.EXPIRES)?.trim()?.toIntOrNull()

        val callId = request.callId() ?: return SubscribeIntent.Reject(400, "Missing Call-ID")

        if (expiresHeader == 0 && callId in knownCallIds) {
            return SubscribeIntent.Cancel(callId)
        }

        if (expiresHeader != null && expiresHeader > 0 && callId in knownCallIds) {
            return SubscribeIntent.Refresh(callId, expiresHeader)
        }

        val xml = request.body.decodeToString()
        val cmdType = ManscdpParser.cmdType(xml)
            ?: return SubscribeIntent.Reject(400, "Missing CmdType in body")

        val kind = when {
            cmdType.equals("MobilePosition", ignoreCase = true) -> "MobilePosition"
            cmdType.equals("Catalog", ignoreCase = true) -> "Catalog"
            else -> return SubscribeIntent.Ignored(cmdType)
        }

        val defaultExpires = when (kind) {
            "Catalog" -> DEFAULT_EXPIRES_CATALOG
            else -> DEFAULT_EXPIRES_MOBILE_POSITION
        }
        val expires = expiresHeader ?: defaultExpires

        // Interval 对 Catalog 不适用(不周期推送),仍解析便于日志
        val intervalStr = ManscdpParser.tagValue(xml, "Interval")
        val interval = intervalStr?.toIntOrNull() ?: when (kind) {
            "Catalog" -> 0
            else -> 30
        }

        val fromHeader = request.fromHeader() ?: ""
        val subscriberUri = extractUri(fromHeader)
        val fromTag = extractTag(fromHeader) ?: ""

        return SubscribeIntent.NewSubscription(
            kind = kind,
            subscriberUri = subscriberUri,
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
