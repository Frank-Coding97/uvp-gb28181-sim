package com.uvp.sim.sip

/**
 * SIP message — request or response. RFC 3261 § 7.
 *
 * Headers are stored as an ordered list (header names may repeat, e.g. multiple Via).
 * `body` is raw bytes — application/sdp or application/MANSCDP+xml etc.
 *
 * Use [SipParser.parse] to decode raw bytes; use [toBytes] to encode for the wire.
 */
sealed class SipMessage {
    abstract val headers: List<Header>
    abstract val body: ByteArray
    abstract val sipVersion: String

    /** Get first header by canonical name (case-insensitive). */
    fun firstHeader(name: String): String? {
        val canonical = SipHeader.canonicalize(name)
        return headers.firstOrNull { SipHeader.canonicalize(it.name) == canonical }?.value
    }

    /** Get all headers by canonical name. */
    fun allHeaders(name: String): List<String> {
        val canonical = SipHeader.canonicalize(name)
        return headers.filter { SipHeader.canonicalize(it.name) == canonical }.map { it.value }
    }

    /** Required Call-ID, CSeq, From, To, Via per RFC 3261 § 8.1.1. */
    fun callId(): String? = firstHeader(SipHeader.CALL_ID)
    fun cseqRaw(): String? = firstHeader(SipHeader.CSEQ)
    fun fromHeader(): String? = firstHeader(SipHeader.FROM)
    fun toHeader(): String? = firstHeader(SipHeader.TO)
    fun via(): String? = firstHeader(SipHeader.VIA)

    fun toBytes(): ByteArray = SipSerializer.serialize(this)

    data class Header(val name: String, val value: String)
}

/** SIP request — REGISTER / INVITE / MESSAGE / etc. */
data class SipRequest(
    val method: SipMethod,
    val requestUri: String,
    override val sipVersion: String = "SIP/2.0",
    override val headers: List<SipMessage.Header>,
    override val body: ByteArray = ByteArray(0)
) : SipMessage() {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SipRequest) return false
        return method == other.method && requestUri == other.requestUri &&
            sipVersion == other.sipVersion && headers == other.headers && body.contentEquals(other.body)
    }
    override fun hashCode(): Int {
        var r = method.hashCode()
        r = 31 * r + requestUri.hashCode()
        r = 31 * r + sipVersion.hashCode()
        r = 31 * r + headers.hashCode()
        r = 31 * r + body.contentHashCode()
        return r
    }
}

/** SIP response — 200 OK / 401 Unauthorized / etc. */
data class SipResponse(
    val statusCode: Int,
    val reasonPhrase: String,
    override val sipVersion: String = "SIP/2.0",
    override val headers: List<SipMessage.Header>,
    override val body: ByteArray = ByteArray(0)
) : SipMessage() {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SipResponse) return false
        return statusCode == other.statusCode && reasonPhrase == other.reasonPhrase &&
            sipVersion == other.sipVersion && headers == other.headers && body.contentEquals(other.body)
    }
    override fun hashCode(): Int {
        var r = statusCode
        r = 31 * r + reasonPhrase.hashCode()
        r = 31 * r + sipVersion.hashCode()
        r = 31 * r + headers.hashCode()
        r = 31 * r + body.contentHashCode()
        return r
    }
}
