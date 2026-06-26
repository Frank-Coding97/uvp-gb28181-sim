package com.uvp.sim.sip

/**
 * 通用响应 / NOTIFY 报文构造(saga §3.5 SipBuilders 拆分的 4/4):
 *  - buildSimple200 / buildOptionsResponse / buildSimpleResponse / buildSimpleError
 *  - buildSubscribe200 / buildNotify
 */
object SipResponseBuilders {

    /** Build a simple 200 OK with no body for non-INVITE requests (MESSAGE, BYE). */
    fun buildSimple200(
        request: SipRequest,
        toTag: String? = null,
        userAgent: String? = null
    ): SipResponse = buildSimpleResponse(request, 200, "OK", toTag, userAgent)

    /**
     * RFC 3261 §11.2 OPTIONS 200 OK 响应构造器(M5 平台兼容性补漏 batch1).
     * 平台用 OPTIONS 探活,设备只需回 200 + Allow 头列出真实支持的 SIP 方法.
     */
    fun buildOptionsResponse(
        request: SipRequest,
        allowedMethods: List<SipMethod>,
        userAgent: String? = null
    ): SipResponse {
        val base = buildSimpleResponse(request, 200, "OK", toTag = null, userAgent = userAgent)
        val allowValue = allowedMethods.joinToString(", ") { it.name }
        return base.copy(headers = base.headers + SipMessage.Header("Allow", allowValue))
    }

    /** Build a simple non-2xx response (no body). Used for 486 Busy / 487 Terminated. */
    fun buildSimpleResponse(
        request: SipRequest,
        statusCode: Int,
        reasonPhrase: String,
        toTag: String? = null,
        userAgent: String? = null
    ): SipResponse {
        val newHeaders = mutableListOf<SipMessage.Header>()
        for (h in request.headers) {
            val canonical = SipHeader.canonicalize(h.name)
            when (canonical) {
                SipHeader.VIA, SipHeader.FROM, SipHeader.CALL_ID, SipHeader.CSEQ -> newHeaders += h
                SipHeader.TO -> {
                    val v = if (toTag != null && !h.value.contains(";tag=")) "${h.value};tag=$toTag" else h.value
                    newHeaders += SipMessage.Header(SipHeader.TO, v)
                }
                else -> Unit
            }
        }
        if (userAgent != null) newHeaders += SipMessage.Header(SipHeader.USER_AGENT, userAgent)
        newHeaders += SipMessage.Header(SipHeader.DATE, SipHeaders.rfc1123Date())
        return SipResponse(statusCode = statusCode, reasonPhrase = reasonPhrase, headers = newHeaders)
    }

    /**
     * 通用 4xx/5xx 错误响应构造器 — 跟 [buildSimple200] 套路一致,只换 status/reason。
     */
    fun buildSimpleError(
        request: SipRequest,
        statusCode: Int,
        reasonPhrase: String,
        toTag: String? = null
    ): SipResponse {
        val newHeaders = mutableListOf<SipMessage.Header>()
        for (h in request.headers) {
            val canonical = SipHeader.canonicalize(h.name)
            when (canonical) {
                SipHeader.VIA, SipHeader.FROM, SipHeader.CALL_ID, SipHeader.CSEQ -> newHeaders += h
                SipHeader.TO -> {
                    val v = if (toTag != null && !h.value.contains(";tag=")) "${h.value};tag=$toTag" else h.value
                    newHeaders += SipMessage.Header(SipHeader.TO, v)
                }
                else -> Unit
            }
        }
        return SipResponse(statusCode = statusCode, reasonPhrase = reasonPhrase, headers = newHeaders)
    }

    /** Build a 200 OK for SUBSCRIBE with Subscription-State and Expires (RFC 6665 § 4.2.1). */
    fun buildSubscribe200(
        request: SipRequest,
        toTag: String,
        expires: Int,
        terminated: Boolean = false,
        userAgent: String? = null
    ): SipResponse {
        val newHeaders = mutableListOf<SipMessage.Header>()
        for (h in request.headers) {
            val canonical = SipHeader.canonicalize(h.name)
            when (canonical) {
                SipHeader.VIA, SipHeader.FROM, SipHeader.CALL_ID, SipHeader.CSEQ -> newHeaders += h
                SipHeader.TO -> {
                    val v = if (!h.value.contains(";tag=")) "${h.value};tag=$toTag" else h.value
                    newHeaders += SipMessage.Header(SipHeader.TO, v)
                }
                else -> Unit
            }
        }
        newHeaders += SipMessage.Header(SipHeader.EXPIRES, expires.toString())
        val ssValue = if (terminated) "terminated;reason=timeout" else "active;expires=$expires"
        newHeaders += SipMessage.Header(SipHeader.SUBSCRIPTION_STATE, ssValue)
        if (userAgent != null) newHeaders += SipMessage.Header(SipHeader.USER_AGENT, userAgent)
        newHeaders += SipMessage.Header(SipHeader.DATE, SipHeaders.rfc1123Date())
        return SipResponse(statusCode = 200, reasonPhrase = "OK", headers = newHeaders)
    }

    /**
     * Build a NOTIFY request for in-dialog subscription notification.
     * From/To are from the device perspective (From = device, To = subscriber).
     */
    fun buildNotify(
        subscriberUri: String,
        callId: String,
        fromTag: String,
        toTag: String,
        event: String,
        subscriptionState: String,
        cseq: Int,
        xmlBody: String,
        localIp: String,
        localPort: Int,
        transport: String = "UDP",
        userAgent: String? = null
    ): SipRequest {
        val body = xmlBody.encodeToByteArray()
        val branch = SipHeaders.randomBranch()
        val headers = mutableListOf(
            SipMessage.Header(SipHeader.VIA,
                "SIP/2.0/$transport $localIp:$localPort;rport;branch=$branch"),
            SipMessage.Header(SipHeader.FROM, "<sip:$localIp:$localPort>;tag=$fromTag"),
            SipMessage.Header(SipHeader.TO, "<$subscriberUri>;tag=$toTag"),
            SipMessage.Header(SipHeader.CALL_ID, callId),
            SipMessage.Header(SipHeader.CSEQ, "$cseq NOTIFY"),
            SipMessage.Header(SipHeader.MAX_FORWARDS, "70"),
            SipMessage.Header(SipHeader.EVENT, event),
            SipMessage.Header(SipHeader.SUBSCRIPTION_STATE, subscriptionState),
            SipMessage.Header(SipHeader.CONTENT_TYPE, "Application/MANSCDP+xml")
        )
        if (userAgent != null) headers += SipMessage.Header(SipHeader.USER_AGENT, userAgent)
        headers += SipMessage.Header(SipHeader.DATE, SipHeaders.rfc1123Date())
        headers += SipMessage.Header(SipHeader.CONTENT_LENGTH, body.size.toString())
        return SipRequest(
            method = SipMethod.NOTIFY,
            requestUri = subscriberUri,
            headers = headers,
            body = body
        )
    }
}
