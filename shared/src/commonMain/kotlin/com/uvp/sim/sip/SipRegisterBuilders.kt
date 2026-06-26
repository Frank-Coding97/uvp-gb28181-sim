package com.uvp.sim.sip

import com.uvp.sim.config.SimConfig

/**
 * REGISTER 域报文构造(saga §3.5 SipBuilders 拆分的 2/4):
 *  - REGISTER / Unregister(Expires=0)/ Keepalive MESSAGE / addAuthorization
 */
object SipRegisterBuilders {

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
                SipMessage.Header(SipHeader.DATE, SipHeaders.rfc1123Date()),
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
        // Drop any stale Authorization first: on a 401 loop the same pending REGISTER
        // gets re-authed repeatedly, and appending would stack multiple Authorization
        // headers — a malformed message the platform rejects with yet another 401.
        val updatedHeaders = register.headers
            .filter { SipHeader.canonicalize(it.name) != SipHeader.AUTHORIZATION }
            .map { h ->
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

    /** Build a Keepalive MESSAGE per GB/T 28181 § 9.4. */
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
</Notify>""".trimIndent().encodeToByteArray()
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
