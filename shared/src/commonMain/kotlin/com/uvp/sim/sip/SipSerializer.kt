package com.uvp.sim.sip

/**
 * SIP message serializer — RFC 3261 § 25 inverse of SipParser.
 *
 * Output uses CRLF line endings (required by RFC 3261).
 * Content-Length is auto-computed if absent or stale.
 */
object SipSerializer {

    private val CRLF = "\r\n"

    fun serialize(message: SipMessage): ByteArray {
        val sb = StringBuilder()

        when (message) {
            is SipRequest -> sb.append(message.method.name).append(' ')
                .append(message.requestUri).append(' ').append(message.sipVersion).append(CRLF)
            is SipResponse -> sb.append(message.sipVersion).append(' ')
                .append(message.statusCode).append(' ')
                .append(message.reasonPhrase).append(CRLF)
        }

        // Auto-set Content-Length to body.size if absent or stale
        val bodyLen = message.body.size
        val seenContentLength = message.headers.any {
            SipHeader.canonicalize(it.name) == SipHeader.CONTENT_LENGTH
        }

        for (h in message.headers) {
            val canonical = SipHeader.canonicalize(h.name)
            if (canonical == SipHeader.CONTENT_LENGTH) {
                // Replace with computed length
                sb.append(SipHeader.CONTENT_LENGTH).append(": ").append(bodyLen).append(CRLF)
            } else {
                sb.append(canonical).append(": ").append(h.value).append(CRLF)
            }
        }
        if (!seenContentLength) {
            sb.append(SipHeader.CONTENT_LENGTH).append(": ").append(bodyLen).append(CRLF)
        }
        sb.append(CRLF)

        val headerBytes = sb.toString().encodeToByteArray()
        if (bodyLen == 0) return headerBytes
        val out = ByteArray(headerBytes.size + bodyLen)
        headerBytes.copyInto(out)
        message.body.copyInto(out, destinationOffset = headerBytes.size)
        return out
    }
}
