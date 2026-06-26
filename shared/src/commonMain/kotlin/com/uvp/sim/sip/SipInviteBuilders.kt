package com.uvp.sim.sip

import com.uvp.sim.config.SimConfig

/**
 * INVITE / ACK / BYE / MESSAGE 等 dialog 报文构造(saga §3.5 SipBuilders 拆分的 3/4)。
 */
object SipInviteBuilders {

    /**
     * Build a 200 OK response to an incoming INVITE, with SDP body answer.
     * GB28181 § 9.2 strongly recommends a `Subject` header in the form
     * `<deviceId>:<sender_ssrc>,<platformId>:0`.
     */
    fun buildInvite200WithSdp(
        invite: SipRequest,
        deviceContact: String,
        toTag: String,
        sdpBody: String,
        userAgent: String? = null,
        subject: String? = null
    ): SipResponse {
        val body = sdpBody.encodeToByteArray()
        val newHeaders = mutableListOf<SipMessage.Header>()
        var sawTo = false
        var sawContact = false
        for (h in invite.headers) {
            val canonical = SipHeader.canonicalize(h.name)
            when (canonical) {
                SipHeader.VIA, SipHeader.FROM, SipHeader.CALL_ID, SipHeader.CSEQ -> newHeaders += h
                SipHeader.TO -> {
                    val withTag = if (h.value.contains(";tag=")) h.value else "${h.value};tag=$toTag"
                    newHeaders += SipMessage.Header(SipHeader.TO, withTag)
                    sawTo = true
                }
                SipHeader.CONTACT -> {
                    sawContact = true
                    newHeaders += SipMessage.Header(SipHeader.CONTACT, deviceContact)
                }
                SipHeader.MAX_FORWARDS, SipHeader.CONTENT_LENGTH -> Unit
                else -> Unit
            }
        }
        if (!sawTo) error("INVITE missing To header")
        if (!sawContact) newHeaders += SipMessage.Header(SipHeader.CONTACT, deviceContact)
        if (subject != null) newHeaders += SipMessage.Header(SipHeader.SUBJECT, subject)
        if (userAgent != null) newHeaders += SipMessage.Header(SipHeader.USER_AGENT, userAgent)
        newHeaders += SipMessage.Header(SipHeader.DATE, SipHeaders.rfc1123Date())
        newHeaders += SipMessage.Header(SipHeader.CONTENT_TYPE, "application/sdp")
        return SipResponse(statusCode = 200, reasonPhrase = "OK", headers = newHeaders, body = body)
    }

    /**
     * Build a device-initiated INVITE to the platform — voice broadcast (§9.8 / §F.2.1).
     * [localId] MUST be the broadcast channel id (TargetID), NOT the device root id.
     */
    fun buildOutboundInvite(
        config: SimConfig,
        localId: String,
        platformUri: String,
        sourceId: String,
        deviceSsrc: String,
        sdpBody: String,
        localIp: String,
        localPort: Int,
        cseq: Int,
        callId: String,
        branch: String,
        fromTag: String
    ): SipRequest {
        val server = config.server
        val body = sdpBody.encodeToByteArray()
        return SipRequest(
            method = SipMethod.INVITE,
            requestUri = platformUri,
            headers = listOf(
                SipMessage.Header(SipHeader.VIA,
                    "SIP/2.0/${config.transport.name} $localIp:$localPort;rport;branch=$branch"),
                SipMessage.Header(SipHeader.FROM, "<sip:$localId@${server.domain}>;tag=$fromTag"),
                SipMessage.Header(SipHeader.TO, "<$platformUri>"),
                SipMessage.Header(SipHeader.CALL_ID, callId),
                SipMessage.Header(SipHeader.CSEQ, "$cseq INVITE"),
                SipMessage.Header(SipHeader.CONTACT, "<sip:$localId@$localIp:$localPort>"),
                SipMessage.Header(SipHeader.SUBJECT, "$sourceId:0,$localId:$deviceSsrc"),
                SipMessage.Header(SipHeader.CONTENT_TYPE, "application/sdp"),
                SipMessage.Header(SipHeader.MAX_FORWARDS, "70"),
                SipMessage.Header(SipHeader.USER_AGENT, config.userAgent),
                SipMessage.Header(SipHeader.DATE, SipHeaders.rfc1123Date()),
                SipMessage.Header(SipHeader.CONTENT_LENGTH, body.size.toString())
            ),
            body = body
        )
    }

    /**
     * Build the ACK for a 2xx response to our outbound broadcast INVITE
     * (RFC 3261 § 13.2.2.4).
     */
    fun buildOutboundAck(
        config: SimConfig,
        requestUri: String,
        callId: String,
        cseq: Int,
        branch: String,
        deviceUri: String,
        fromTag: String,
        platformUri: String,
        remoteTag: String,
        localIp: String,
        localPort: Int
    ): SipRequest = SipRequest(
        method = SipMethod.ACK,
        requestUri = requestUri,
        headers = listOf(
            SipMessage.Header(SipHeader.VIA,
                "SIP/2.0/${config.transport.name} $localIp:$localPort;rport;branch=$branch"),
            SipMessage.Header(SipHeader.FROM, "<$deviceUri>;tag=$fromTag"),
            SipMessage.Header(SipHeader.TO, "<$platformUri>;tag=$remoteTag"),
            SipMessage.Header(SipHeader.CALL_ID, callId),
            SipMessage.Header(SipHeader.CSEQ, "$cseq ACK"),
            SipMessage.Header(SipHeader.MAX_FORWARDS, "70"),
            SipMessage.Header(SipHeader.DATE, SipHeaders.rfc1123Date())
        )
    )

    /**
     * Build a BYE for an established dialog (device-initiated stop).
     * In-dialog routing per RFC 3261 § 12.2.
     */
    fun buildBye(
        config: SimConfig,
        callId: String,
        cseq: Int,
        branch: String,
        localUri: String,
        localTag: String,
        remoteUri: String,
        remoteTag: String,
        remoteTarget: String,
        localIp: String,
        localPort: Int
    ): SipRequest = SipRequest(
        method = SipMethod.BYE,
        requestUri = remoteTarget,
        headers = listOf(
            SipMessage.Header(SipHeader.VIA,
                "SIP/2.0/${config.transport.name} $localIp:$localPort;rport;branch=$branch"),
            SipMessage.Header(SipHeader.FROM, "<$localUri>;tag=$localTag"),
            SipMessage.Header(SipHeader.TO, "<$remoteUri>;tag=$remoteTag"),
            SipMessage.Header(SipHeader.CALL_ID, callId),
            SipMessage.Header(SipHeader.CSEQ, "$cseq BYE"),
            SipMessage.Header(SipHeader.MAX_FORWARDS, "70"),
            SipMessage.Header(SipHeader.USER_AGENT, config.userAgent),
            SipMessage.Header(SipHeader.DATE, SipHeaders.rfc1123Date())
        )
    )

    /**
     * Build a generic outbound MESSAGE carrying a MANSCDP+xml body
     * (used by Catalog response, DeviceInfo response, Alarm Notify, etc.).
     */
    fun buildMessage(
        config: SimConfig,
        cseq: Int,
        callId: String,
        branch: String,
        fromTag: String,
        localIp: String,
        localPort: Int,
        xmlBody: String
    ): SipRequest {
        val server = config.server
        val device = config.device
        val body = xmlBody.encodeToByteArray()
        return SipRequest(
            method = SipMethod.MESSAGE,
            requestUri = "sip:${server.serverId}@${server.domain}",
            headers = listOf(
                SipMessage.Header(SipHeader.VIA,
                    "SIP/2.0/${config.transport.name} $localIp:$localPort;rport;branch=$branch"),
                SipMessage.Header(SipHeader.FROM,
                    "<sip:${device.deviceId}@${server.domain}>;tag=$fromTag"),
                SipMessage.Header(SipHeader.TO,
                    "<sip:${server.serverId}@${server.domain}>"),
                SipMessage.Header(SipHeader.CALL_ID, callId),
                SipMessage.Header(SipHeader.CSEQ, "$cseq MESSAGE"),
                SipMessage.Header(SipHeader.CONTENT_TYPE, "application/MANSCDP+xml"),
                SipMessage.Header(SipHeader.MAX_FORWARDS, "70"),
                SipMessage.Header(SipHeader.USER_AGENT, config.userAgent),
                SipMessage.Header(SipHeader.DATE, SipHeaders.rfc1123Date()),
                SipMessage.Header(SipHeader.CONTENT_LENGTH, body.size.toString())
            ),
            body = body
        )
    }
}
