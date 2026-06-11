package com.uvp.sim.sip

/**
 * SIP message parser — RFC 3261 § 25 (ABNF).
 *
 * Accepts raw bytes; returns SipRequest / SipResponse.
 * Throws [SipParseException] on malformed input.
 *
 * Strategy:
 * 1. Find header / body boundary (CRLF CRLF or LF LF for tolerance).
 * 2. Parse start-line (request-line vs status-line).
 * 3. Parse header lines (with continuation lines collapsed).
 * 4. Read body of length given by Content-Length (or to end-of-data).
 *
 * The simulator only needs the GB28181 subset; we do not implement multipart bodies,
 * Route preprocessing, or fancy URI normalization here — that lives at the application
 * layer if ever needed.
 */
object SipParser {

    private val CRLF = "\r\n"
    private val SUPPORTED_VERSION = "SIP/2.0"

    fun parse(raw: ByteArray): SipMessage {
        if (raw.isEmpty()) throw SipParseException("Empty message")

        // 1. Find header / body boundary
        val boundary = findHeaderBodyBoundary(raw)
        val headerBytes = raw.copyOfRange(0, boundary.headerEnd)
        val bodyStart = boundary.bodyStart
        val headerText = headerBytes.decodeToString()
        val lines = headerText.split(CRLF, "\n")
            .let { l -> if (l.isNotEmpty() && l.last().isEmpty()) l.dropLast(1) else l }
        if (lines.isEmpty()) throw SipParseException("No header lines")

        val startLine = lines[0]

        // 2. Header lines with continuation collapse (RFC 3261 § 7.3.1: leading WSP folds).
        val rawHeaderLines = mutableListOf<String>()
        for (line in lines.drop(1)) {
            if (line.isEmpty()) continue
            if (line.startsWith(' ') || line.startsWith('\t')) {
                if (rawHeaderLines.isEmpty()) throw SipParseException("Continuation line without header: $line")
                rawHeaderLines[rawHeaderLines.lastIndex] = rawHeaderLines.last() + " " + line.trim()
            } else {
                rawHeaderLines += line
            }
        }
        val headers = rawHeaderLines.mapNotNull { line ->
            val sep = line.indexOf(':')
            if (sep < 0) throw SipParseException("Malformed header: $line")
            val name = line.substring(0, sep).trim()
            val value = line.substring(sep + 1).trim()
            if (name.isEmpty()) throw SipParseException("Empty header name: $line")
            SipMessage.Header(name, value)
        }

        // 3. Body — respect Content-Length if present, else take rest.
        val cl = headers.firstOrNull { SipHeader.canonicalize(it.name) == SipHeader.CONTENT_LENGTH }
            ?.value?.trim()?.toIntOrNull()
        val bodyAvailable = raw.size - bodyStart
        val bodySize = if (cl != null) {
            if (cl > bodyAvailable) {
                throw SipParseException("Content-Length declared $cl but only $bodyAvailable bytes available")
            }
            cl
        } else {
            bodyAvailable
        }
        val body = if (bodySize > 0) raw.copyOfRange(bodyStart, bodyStart + bodySize) else ByteArray(0)

        // 4. Dispatch on start line
        return parseStartLine(startLine, headers, body)
    }

    private data class Boundary(val headerEnd: Int, val bodyStart: Int)

    /**
     * Find the empty line that ends the header section.
     * Tolerates LF-only line endings (some servers emit non-CRLF).
     */
    private fun findHeaderBodyBoundary(raw: ByteArray): Boundary {
        // Look for CRLF CRLF
        for (i in 0..raw.size - 4) {
            if (raw[i] == 0x0D.toByte() && raw[i + 1] == 0x0A.toByte() &&
                raw[i + 2] == 0x0D.toByte() && raw[i + 3] == 0x0A.toByte()
            ) {
                return Boundary(i + 2, i + 4)
            }
        }
        // Fallback: LF LF
        for (i in 0..raw.size - 2) {
            if (raw[i] == 0x0A.toByte() && raw[i + 1] == 0x0A.toByte()) {
                return Boundary(i + 1, i + 2)
            }
        }
        // No body separator — treat whole input as headers
        return Boundary(raw.size, raw.size)
    }

    private fun parseStartLine(line: String, headers: List<SipMessage.Header>, body: ByteArray): SipMessage {
        val parts = line.split(' ', limit = 3)
        if (parts.size < 3) throw SipParseException("Malformed start line: $line")

        return if (parts[0].startsWith("SIP/")) {
            // Status line: SIP/2.0 <code> <reason>
            val version = parts[0]
            val code = parts[1].toIntOrNull() ?: throw SipParseException("Bad status code: ${parts[1]}")
            if (code < 100 || code > 699) throw SipParseException("Status code out of range: $code")
            val reason = parts[2]
            if (version != SUPPORTED_VERSION) throw SipParseException("Unsupported SIP version: $version")
            SipResponse(code, reason, version, headers, body)
        } else {
            // Request line: METHOD URI SIP/2.0
            val methodName = parts[0]
            val method = SipMethod.fromString(methodName)
                ?: throw SipParseException("Unknown method: $methodName")
            val uri = parts[1]
            val version = parts[2]
            if (version != SUPPORTED_VERSION) throw SipParseException("Unsupported SIP version: $version")
            SipRequest(method, uri, version, headers, body)
        }
    }
}

class SipParseException(message: String) : RuntimeException(message)
