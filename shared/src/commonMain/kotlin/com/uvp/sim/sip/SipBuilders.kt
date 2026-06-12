package com.uvp.sim.sip

import com.uvp.sim.config.SimConfig
import kotlin.random.Random

/**
 * Helpers to construct outbound SIP messages from a [SimConfig] + per-call state.
 *
 * Centralized so the test suite can reproduce well-formed messages and the
 * engine can stay terse.
 */
object SipBuilders {

    /** Build a REGISTER (no auth) — the first hop in the registration flow. */
    fun buildRegister(
        config: SimConfig,
        cseq: Int,
        callId: String,
        branch: String,
        fromTag: String,
        localIp: String,
        localPort: Int
    ): SipRequest {
        val server = config.server
        val device = config.device
        val requestUri = "sip:${server.serverId}@${server.domain}"
        val deviceContact = "<sip:${device.deviceId}@$localIp:$localPort>"
        return SipRequest(
            method = SipMethod.REGISTER,
            requestUri = requestUri,
            headers = listOf(
                SipMessage.Header(SipHeader.VIA,
                    "SIP/2.0/${config.transport.name} $localIp:$localPort;rport;branch=$branch"),
                SipMessage.Header(SipHeader.FROM,
                    "<sip:${device.deviceId}@${server.domain}>;tag=$fromTag"),
                SipMessage.Header(SipHeader.TO,
                    "<sip:${device.deviceId}@${server.domain}>"),
                SipMessage.Header(SipHeader.CALL_ID, callId),
                SipMessage.Header(SipHeader.CSEQ, "$cseq REGISTER"),
                SipMessage.Header(SipHeader.CONTACT, deviceContact),
                SipMessage.Header(SipHeader.MAX_FORWARDS, "70"),
                SipMessage.Header(SipHeader.USER_AGENT, config.userAgent),
                SipMessage.Header(SipHeader.EXPIRES, config.expiresSeconds.toString())
            )
        )
    }

    /** Take a REGISTER and add the Authorization header for re-send after a 401. */
    fun addAuthorization(
        register: SipRequest,
        authorizationHeader: String,
        newCseq: Int,
        newBranch: String
    ): SipRequest {
        val updatedHeaders = register.headers.map { h ->
            when (SipHeader.canonicalize(h.name)) {
                SipHeader.CSEQ -> SipMessage.Header(SipHeader.CSEQ, "$newCseq REGISTER")
                SipHeader.VIA -> SipMessage.Header(
                    SipHeader.VIA,
                    h.value.replace(Regex("branch=[^;]+"), "branch=$newBranch")
                )
                else -> h
            }
        } + SipMessage.Header(SipHeader.AUTHORIZATION, authorizationHeader)
        return register.copy(headers = updatedHeaders)
    }

    /** Build an "Unregister" — REGISTER with Expires: 0. */
    fun buildUnregister(
        config: SimConfig,
        cseq: Int,
        callId: String,
        branch: String,
        fromTag: String,
        localIp: String,
        localPort: Int
    ): SipRequest {
        val base = buildRegister(config, cseq, callId, branch, fromTag, localIp, localPort)
        val updatedHeaders = base.headers.map { h ->
            if (SipHeader.canonicalize(h.name) == SipHeader.EXPIRES) {
                SipMessage.Header(SipHeader.EXPIRES, "0")
            } else h
        }
        return base.copy(headers = updatedHeaders)
    }

    /**
     * Build a Keepalive MESSAGE per GB/T 28181 § 9.4.
     */
    fun buildKeepalive(
        config: SimConfig,
        sn: Int,
        cseq: Int,
        callId: String,
        branch: String,
        fromTag: String,
        localIp: String,
        localPort: Int
    ): SipRequest {
        val server = config.server
        val device = config.device
        val body = """<?xml version="1.0" encoding="UTF-8"?>
<Notify>
<CmdType>Keepalive</CmdType>
<SN>$sn</SN>
<DeviceID>${device.deviceId}</DeviceID>
<Status>OK</Status>
</Notify>""".encodeToByteArray()
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
                SipMessage.Header(SipHeader.CONTENT_LENGTH, body.size.toString())
            ),
            body = body
        )
    }

    fun randomBranch(): String {
        val alpha = "abcdefghijklmnopqrstuvwxyz0123456789"
        return "z9hG4bK" + (1..16).map { alpha.random(Random) }.joinToString("")
    }

    fun randomTag(): String {
        val alpha = "abcdef0123456789"
        return (1..10).map { alpha.random(Random) }.joinToString("")
    }

    fun randomCallId(localIp: String): String {
        val alpha = "abcdef0123456789"
        val rand = (1..16).map { alpha.random(Random) }.joinToString("")
        return "$rand@$localIp"
    }

    /**
     * Build a 200 OK response to an incoming INVITE, with SDP body answer.
     *
     * Per RFC 3261 § 8.2.6.2, the response copies Via/From/To/Call-ID/CSeq from
     * the request verbatim, but the To header gets a tag (we generate one).
     * Contact is required for in-dialog routing of the subsequent ACK/BYE.
     */
    fun buildInvite200WithSdp(
        invite: SipRequest,
        deviceContact: String,
        toTag: String,
        sdpBody: String
    ): SipResponse {
        val body = sdpBody.encodeToByteArray()
        val newHeaders = mutableListOf<SipMessage.Header>()
        var sawTo = false
        var sawContact = false
        for (h in invite.headers) {
            val canonical = SipHeader.canonicalize(h.name)
            when (canonical) {
                SipHeader.VIA, SipHeader.FROM, SipHeader.CALL_ID, SipHeader.CSEQ -> {
                    newHeaders += h
                }
                SipHeader.TO -> {
                    val withTag = if (h.value.contains(";tag=")) h.value
                    else "${h.value};tag=$toTag"
                    newHeaders += SipMessage.Header(SipHeader.TO, withTag)
                    sawTo = true
                }
                SipHeader.CONTACT -> {
                    sawContact = true
                    newHeaders += SipMessage.Header(SipHeader.CONTACT, deviceContact)
                }
                SipHeader.MAX_FORWARDS, SipHeader.CONTENT_LENGTH -> {
                    // Skip; Content-Length is auto-set by serializer
                }
                else -> { /* drop other request-only headers */ }
            }
        }
        if (!sawTo) error("INVITE missing To header")
        if (!sawContact) {
            newHeaders += SipMessage.Header(SipHeader.CONTACT, deviceContact)
        }
        newHeaders += SipMessage.Header(SipHeader.CONTENT_TYPE, "application/sdp")
        return SipResponse(
            statusCode = 200,
            reasonPhrase = "OK",
            headers = newHeaders,
            body = body
        )
    }

    /** Build a simple 200 OK with no body for non-INVITE requests (MESSAGE, BYE). */
    fun buildSimple200(request: SipRequest, toTag: String? = null): SipResponse =
        buildSimpleResponse(request, 200, "OK", toTag)

    /** Build a simple non-2xx response (no body). Used for 486 Busy / 487 Terminated. */
    fun buildSimpleResponse(
        request: SipRequest,
        statusCode: Int,
        reasonPhrase: String,
        toTag: String? = null
    ): SipResponse {
        val newHeaders = mutableListOf<SipMessage.Header>()
        for (h in request.headers) {
            val canonical = SipHeader.canonicalize(h.name)
            when (canonical) {
                SipHeader.VIA, SipHeader.FROM, SipHeader.CALL_ID, SipHeader.CSEQ -> {
                    newHeaders += h
                }
                SipHeader.TO -> {
                    val v = if (toTag != null && !h.value.contains(";tag=")) {
                        "${h.value};tag=$toTag"
                    } else h.value
                    newHeaders += SipMessage.Header(SipHeader.TO, v)
                }
                else -> { /* drop */ }
            }
        }
        return SipResponse(statusCode = statusCode, reasonPhrase = reasonPhrase, headers = newHeaders)
    }

    /**
     * Build a generic outbound MESSAGE carrying a MANSCDP+xml body
     * (used by Catalog response, DeviceInfo response, Alarm Notify, etc.).
     *
     * From / To are between the device (us) and the upper-level SIP server.
     */
    fun buildMessage(
        config: com.uvp.sim.config.SimConfig,
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
                SipMessage.Header(SipHeader.CONTENT_LENGTH, body.size.toString())
            ),
            body = body
        )
    }
}
