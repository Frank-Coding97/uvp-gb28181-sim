package com.uvp.sim.sip

import com.uvp.sim.config.SimConfig
import kotlinx.datetime.Clock
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.Instant
import kotlinx.datetime.Month
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.random.Random

/**
 * Helpers to construct outbound SIP messages from a [SimConfig] + per-call state.
 *
 * Centralized so the test suite can reproduce well-formed messages and the
 * engine can stay terse.
 */
object SipBuilders {

    /**
     * RFC 1123 date string in GMT, e.g. `Sun, 06 Nov 1994 08:49:37 GMT`.
     * Mandatory for SIP per RFC 3261 § 20.17, and §10.4 of the device matrix.
     */
    fun rfc1123Date(instant: Instant = Clock.System.now()): String {
        val ldt = instant.toLocalDateTime(TimeZone.UTC)
        val dow = when (ldt.dayOfWeek) {
            DayOfWeek.MONDAY -> "Mon"
            DayOfWeek.TUESDAY -> "Tue"
            DayOfWeek.WEDNESDAY -> "Wed"
            DayOfWeek.THURSDAY -> "Thu"
            DayOfWeek.FRIDAY -> "Fri"
            DayOfWeek.SATURDAY -> "Sat"
            DayOfWeek.SUNDAY -> "Sun"
            else -> "Mon"
        }
        val mon = when (ldt.month) {
            Month.JANUARY -> "Jan"; Month.FEBRUARY -> "Feb"; Month.MARCH -> "Mar"
            Month.APRIL -> "Apr"; Month.MAY -> "May"; Month.JUNE -> "Jun"
            Month.JULY -> "Jul"; Month.AUGUST -> "Aug"; Month.SEPTEMBER -> "Sep"
            Month.OCTOBER -> "Oct"; Month.NOVEMBER -> "Nov"; Month.DECEMBER -> "Dec"
            else -> "Jan"
        }
        fun p2(v: Int) = v.toString().padStart(2, '0')
        return "$dow, ${p2(ldt.dayOfMonth)} $mon ${ldt.year} " +
            "${p2(ldt.hour)}:${p2(ldt.minute)}:${p2(ldt.second)} GMT"
    }

    /** GB28181 § 9.2 Subject header for INVITE-side dialogs:
     *  `<senderId>:<sender_ssrc>,<receiverId>:0` */
    fun subject(senderId: String, ssrc: String, receiverId: String): String =
        "$senderId:$ssrc,$receiverId:0"

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
                SipMessage.Header(SipHeader.DATE, rfc1123Date()),
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
                SipMessage.Header(SipHeader.DATE, rfc1123Date()),
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
     *
     * GB28181 § 9.2 strongly recommends a `Subject` header in the form
     * `<deviceId>:<sender_ssrc>,<platformId>:0` so platforms (WVP / EasyCVR /
     * LiveGBS) can match the SIP dialog with the RTP stream — pass it via
     * [subject] when building the answer for a Play / Playback INVITE.
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
        if (subject != null) {
            newHeaders += SipMessage.Header(SipHeader.SUBJECT, subject)
        }
        if (userAgent != null) {
            newHeaders += SipMessage.Header(SipHeader.USER_AGENT, userAgent)
        }
        newHeaders += SipMessage.Header(SipHeader.DATE, rfc1123Date())
        newHeaders += SipMessage.Header(SipHeader.CONTENT_TYPE, "application/sdp")
        return SipResponse(
            statusCode = 200,
            reasonPhrase = "OK",
            headers = newHeaders,
            body = body
        )
    }

    /**
     * Build a device-initiated INVITE to the platform — voice broadcast (§9.8 / §F.2.1).
     *
     * Unlike the inbound Play INVITE the device answers, here the device is the UAC:
     * it offers an `m=audio recvonly` SDP and asks the platform to push G.711 audio.
     *
     * Subject header per spec Q2: `{sourceId}:0,{deviceId}:{deviceSsrc}` — the audio
     * source is the platform (SSRC 0, not advertised), the device is the receiver
     * (self-generated SSRC).
     */
    fun buildOutboundInvite(
        config: SimConfig,
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
        val device = config.device
        val server = config.server
        val body = sdpBody.encodeToByteArray()
        return SipRequest(
            method = SipMethod.INVITE,
            requestUri = platformUri,
            headers = listOf(
                SipMessage.Header(SipHeader.VIA,
                    "SIP/2.0/${config.transport.name} $localIp:$localPort;rport;branch=$branch"),
                SipMessage.Header(SipHeader.FROM,
                    "<sip:${device.deviceId}@${server.domain}>;tag=$fromTag"),
                SipMessage.Header(SipHeader.TO, "<$platformUri>"),
                SipMessage.Header(SipHeader.CALL_ID, callId),
                SipMessage.Header(SipHeader.CSEQ, "$cseq INVITE"),
                SipMessage.Header(SipHeader.CONTACT, "<sip:${device.deviceId}@$localIp:$localPort>"),
                SipMessage.Header(SipHeader.SUBJECT, "$sourceId:0,${device.deviceId}:$deviceSsrc"),
                SipMessage.Header(SipHeader.CONTENT_TYPE, "application/sdp"),
                SipMessage.Header(SipHeader.MAX_FORWARDS, "70"),
                SipMessage.Header(SipHeader.USER_AGENT, config.userAgent),
                SipMessage.Header(SipHeader.DATE, rfc1123Date()),
                SipMessage.Header(SipHeader.CONTENT_LENGTH, body.size.toString())
            ),
            body = body
        )
    }

    /**
     * Build the ACK for a 2xx response to our outbound broadcast INVITE
     * (RFC 3261 § 13.2.2.4). CSeq number equals the INVITE's; method is ACK.
     * Request-URI is the remote target (platform Contact, falling back to To URI).
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
            SipMessage.Header(SipHeader.DATE, rfc1123Date())
        )
    )

    /** Build a simple 200 OK with no body for non-INVITE requests (MESSAGE, BYE). */
    fun buildSimple200(
        request: SipRequest,
        toTag: String? = null,
        userAgent: String? = null
    ): SipResponse =
        buildSimpleResponse(request, 200, "OK", toTag, userAgent)

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
        if (userAgent != null) {
            newHeaders += SipMessage.Header(SipHeader.USER_AGENT, userAgent)
        }
        newHeaders += SipMessage.Header(SipHeader.DATE, rfc1123Date())
        return SipResponse(statusCode = statusCode, reasonPhrase = reasonPhrase, headers = newHeaders)
    }

    /**
     * 通用 4xx/5xx 错误响应构造器 — 跟 [buildSimple200] 套路一致,只换 status/reason。
     * 用于拒绝 INVITE 等场景:488 Not Acceptable Here、404 Not Found 等。
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
     * Build a 200 OK for SUBSCRIBE with Subscription-State and Expires.
     * Per RFC 6665 § 4.2.1.
     */
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
                SipHeader.VIA, SipHeader.FROM, SipHeader.CALL_ID, SipHeader.CSEQ -> {
                    newHeaders += h
                }
                SipHeader.TO -> {
                    val v = if (!h.value.contains(";tag=")) "${h.value};tag=$toTag" else h.value
                    newHeaders += SipMessage.Header(SipHeader.TO, v)
                }
                else -> { /* drop */ }
            }
        }
        newHeaders += SipMessage.Header(SipHeader.EXPIRES, expires.toString())
        val ssValue = if (terminated) "terminated;reason=timeout" else "active;expires=$expires"
        newHeaders += SipMessage.Header(SipHeader.SUBSCRIPTION_STATE, ssValue)
        if (userAgent != null) {
            newHeaders += SipMessage.Header(SipHeader.USER_AGENT, userAgent)
        }
        newHeaders += SipMessage.Header(SipHeader.DATE, rfc1123Date())
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
        val branch = randomBranch()
        val headers = mutableListOf(
            SipMessage.Header(SipHeader.VIA,
                "SIP/2.0/$transport $localIp:$localPort;rport;branch=$branch"),
            SipMessage.Header(SipHeader.FROM,
                "<sip:$localIp:$localPort>;tag=$fromTag"),
            SipMessage.Header(SipHeader.TO,
                "<$subscriberUri>;tag=$toTag"),
            SipMessage.Header(SipHeader.CALL_ID, callId),
            SipMessage.Header(SipHeader.CSEQ, "$cseq NOTIFY"),
            SipMessage.Header(SipHeader.MAX_FORWARDS, "70"),
            SipMessage.Header(SipHeader.EVENT, event),
            SipMessage.Header(SipHeader.SUBSCRIPTION_STATE, subscriptionState),
            SipMessage.Header(SipHeader.CONTENT_TYPE, "Application/MANSCDP+xml")
        )
        if (userAgent != null) {
            headers += SipMessage.Header(SipHeader.USER_AGENT, userAgent)
        }
        headers += SipMessage.Header(SipHeader.DATE, rfc1123Date())
        headers += SipMessage.Header(SipHeader.CONTENT_LENGTH, body.size.toString())
        return SipRequest(
            method = SipMethod.NOTIFY,
            requestUri = subscriberUri,
            headers = headers,
            body = body
        )
    }

    /**
     * Build a BYE for an established dialog (device-initiated stop).
     *
     * In-dialog routing per RFC 3261 § 12.2:
     *   - Request-URI = remote target (peer's Contact URI from the INVITE)
     *   - From = local URI + local tag (we sent the 200 OK so this is To-tag of the dialog)
     *   - To = remote URI + remote tag (came from INVITE's From header)
     *   - Call-ID = dialog Call-ID
     *   - CSeq = local CSeq + 1, method=BYE
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
    ): SipRequest {
        return SipRequest(
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
                SipMessage.Header(SipHeader.DATE, rfc1123Date())
            )
        )
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
                SipMessage.Header(SipHeader.DATE, rfc1123Date()),
                SipMessage.Header(SipHeader.CONTENT_LENGTH, body.size.toString())
            ),
            body = body
        )
    }
}
