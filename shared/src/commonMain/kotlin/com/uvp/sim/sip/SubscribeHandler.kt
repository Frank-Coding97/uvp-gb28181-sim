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

    fun parse(request: SipRequest, knownCallIds: Set<String>): SubscribeIntent {
        val event = request.firstHeader(SipHeader.EVENT)
        // GB28181 平台可能发 "Event: presence" 也可能 "Event: presence;id=xxx",
        // 取分号前的主标识比对。
        val eventName = event?.trim()?.substringBefore(';')?.trim()
        if (eventName == null || !eventName.equals("presence", ignoreCase = true)) {
            return SubscribeIntent.Reject(489, "Bad Event: ${event ?: "(missing)"}")
        }

        val expiresStr = request.firstHeader(SipHeader.EXPIRES)
        val expires = expiresStr?.trim()?.toIntOrNull() ?: 3600

        val callId = request.callId() ?: return SubscribeIntent.Reject(400, "Missing Call-ID")

        if (expires == 0 && callId in knownCallIds) {
            return SubscribeIntent.Cancel(callId)
        }

        if (expires > 0 && callId in knownCallIds) {
            return SubscribeIntent.Refresh(callId, expires)
        }

        val xml = request.body.decodeToString()
        val cmdType = ManscdpParser.cmdType(xml)
            ?: return SubscribeIntent.Reject(400, "Missing CmdType in body")

        if (!cmdType.equals("MobilePosition", ignoreCase = true)) {
            return SubscribeIntent.Ignored(cmdType)
        }

        val intervalStr = ManscdpParser.tagValue(xml, "Interval")
        val interval = intervalStr?.toIntOrNull() ?: 30

        val fromHeader = request.fromHeader() ?: ""
        val subscriberUri = extractUri(fromHeader)

        val fromTag = extractTag(fromHeader) ?: ""

        return SubscribeIntent.NewSubscription(
            kind = "MobilePosition",
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
