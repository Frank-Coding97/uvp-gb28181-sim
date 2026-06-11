package com.uvp.sim.sip

/**
 * 真实 SIP 报文样例(改写自 wvp-pro 抓包),供 SipParser 解析往返测试。
 * 全部使用 CRLF 行尾(SIP 标准)。
 */
object SipSamples {

    private const val CRLF = "\r\n"

    val registerRequest: String = listOf(
        "REGISTER sip:34020000002000000001@3402000000 SIP/2.0",
        "Via: SIP/2.0/UDP 192.168.10.50:5060;rport;branch=z9hG4bK1234567890",
        "From: <sip:34020000001110000001@3402000000>;tag=abc123",
        "To: <sip:34020000001110000001@3402000000>",
        "Call-ID: 8a9e2f5c@192.168.10.50",
        "CSeq: 1 REGISTER",
        "Contact: <sip:34020000001110000001@192.168.10.50:5060>",
        "Max-Forwards: 70",
        "User-Agent: UVP-Sim/0.1",
        "Expires: 3600",
        "Content-Length: 0",
        "",
        ""
    ).joinToString(CRLF)

    val register401: String = listOf(
        "SIP/2.0 401 Unauthorized",
        "Via: SIP/2.0/UDP 192.168.10.50:5060;rport=5060;branch=z9hG4bK1234567890",
        "From: <sip:34020000001110000001@3402000000>;tag=abc123",
        "To: <sip:34020000001110000001@3402000000>;tag=server-tag-456",
        "Call-ID: 8a9e2f5c@192.168.10.50",
        "CSeq: 1 REGISTER",
        "WWW-Authenticate: Digest realm=\"3402000000\",nonce=\"1234abcd5678efgh\",algorithm=MD5",
        "Content-Length: 0",
        "",
        ""
    ).joinToString(CRLF)

    val register200: String = listOf(
        "SIP/2.0 200 OK",
        "Via: SIP/2.0/UDP 192.168.10.50:5060;rport=5060;branch=z9hG4bK0987654321",
        "From: <sip:34020000001110000001@3402000000>;tag=abc123",
        "To: <sip:34020000001110000001@3402000000>;tag=server-tag-789",
        "Call-ID: 8a9e2f5c@192.168.10.50",
        "CSeq: 2 REGISTER",
        "Contact: <sip:34020000001110000001@192.168.10.50:5060>;expires=3600",
        "Date: Wed, 11 Jun 2026 09:00:00 GMT",
        "Content-Length: 0",
        "",
        ""
    ).joinToString(CRLF)

    private val keepaliveBody = listOf(
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>",
        "<Notify>",
        "<CmdType>Keepalive</CmdType>",
        "<SN>12</SN>",
        "<DeviceID>34020000001110000001</DeviceID>",
        "<Status>OK</Status>",
        "</Notify>"
    ).joinToString("\n")

    /** Keepalive MESSAGE 含 XML body */
    val keepaliveMessage: String = run {
        val bodyLen = keepaliveBody.encodeToByteArray().size
        listOf(
            "MESSAGE sip:34020000002000000001@3402000000 SIP/2.0",
            "Via: SIP/2.0/UDP 192.168.10.50:5060;rport;branch=z9hG4bKkeepalive123",
            "From: <sip:34020000001110000001@3402000000>;tag=abc123",
            "To: <sip:34020000002000000001@3402000000>",
            "Call-ID: keepalive-call-id@192.168.10.50",
            "CSeq: 12 MESSAGE",
            "Content-Type: application/MANSCDP+xml",
            "Max-Forwards: 70",
            "User-Agent: UVP-Sim/0.1",
            "Content-Length: $bodyLen",
            "",
            keepaliveBody
        ).joinToString(CRLF)
    }

    private val sdpAnswer = listOf(
        "v=0",
        "o=34020000002000000001 0 0 IN IP4 192.168.10.222",
        "s=Play",
        "c=IN IP4 192.168.10.222",
        "t=0 0",
        "m=video 6000 RTP/AVP 96",
        "a=recvonly",
        "a=rtpmap:96 PS/90000",
        "y=0100000001"
    ).joinToString("\r\n")

    val inviteRealplay: String = run {
        val bodyLen = sdpAnswer.encodeToByteArray().size
        listOf(
            "INVITE sip:34020000001320000001@192.168.10.50:5060 SIP/2.0",
            "Via: SIP/2.0/UDP 192.168.10.222:5060;rport;branch=z9hG4bKinvite789",
            "From: <sip:34020000002000000001@3402000000>;tag=server-from-tag",
            "To: <sip:34020000001320000001@3402000000>",
            "Call-ID: invite-call-id-abc@192.168.10.222",
            "CSeq: 20 INVITE",
            "Contact: <sip:34020000002000000001@192.168.10.222:5060>",
            "Max-Forwards: 70",
            "Subject: 34020000001320000001:0,34020000002000000001:0",
            "Content-Type: application/sdp",
            "Content-Length: $bodyLen",
            "",
            sdpAnswer
        ).joinToString(CRLF)
    }

    val bye: String = listOf(
        "BYE sip:34020000001320000001@192.168.10.50:5060 SIP/2.0",
        "Via: SIP/2.0/UDP 192.168.10.222:5060;rport;branch=z9hG4bKbye999",
        "From: <sip:34020000002000000001@3402000000>;tag=server-from-tag",
        "To: <sip:34020000001320000001@3402000000>;tag=device-to-tag",
        "Call-ID: invite-call-id-abc@192.168.10.222",
        "CSeq: 21 BYE",
        "Max-Forwards: 70",
        "Content-Length: 0",
        "",
        ""
    ).joinToString(CRLF)

    /** Folded header(continuation line)样例 — RFC 3261 § 7.3.1 */
    val foldedHeaderMessage: String = listOf(
        "REGISTER sip:test@example.com SIP/2.0",
        "Via: SIP/2.0/UDP 10.0.0.1:5060",
        "Subject: My very long subject",
        " continuation of the subject",
        "\tand another fold",
        "From: <sip:user@example.com>;tag=t1",
        "To: <sip:user@example.com>",
        "Call-ID: f@10.0.0.1",
        "CSeq: 1 REGISTER",
        "Content-Length: 0",
        "",
        ""
    ).joinToString(CRLF)

    /** 紧凑头(short form)样例 — RFC 3261 § 20 */
    val compactHeaderMessage: String = listOf(
        "REGISTER sip:test@example.com SIP/2.0",
        "v: SIP/2.0/UDP 10.0.0.1:5060",
        "f: <sip:user@example.com>;tag=t1",
        "t: <sip:user@example.com>",
        "i: c@10.0.0.1",
        "CSeq: 1 REGISTER",
        "l: 0",
        "",
        ""
    ).joinToString(CRLF)

    /** LF-only line endings(部分服务器违规) */
    val lfOnlyMessage: String = listOf(
        "REGISTER sip:test@example.com SIP/2.0",
        "Via: SIP/2.0/UDP 10.0.0.1:5060",
        "From: <sip:user@example.com>;tag=t1",
        "To: <sip:user@example.com>",
        "Call-ID: lf@10.0.0.1",
        "CSeq: 1 REGISTER",
        "Content-Length: 0",
        "",
        ""
    ).joinToString("\n")
}
