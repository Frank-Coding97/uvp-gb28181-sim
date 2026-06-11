package com.uvp.sim.sip

/**
 * HTTP Digest authentication for SIP — RFC 2617 + RFC 3261 § 22.4.
 *
 * Used by the simulator when the platform replies 401 Unauthorized to a REGISTER:
 * we parse the WWW-Authenticate challenge, compute the response, and add an
 * Authorization header on the next REGISTER attempt.
 */
object DigestAuth {

    /**
     * Parse a WWW-Authenticate header value into a [Challenge].
     * Example input:
     *   `Digest realm="3402000000",nonce="abc",algorithm=MD5,qop="auth",opaque="xyz"`
     */
    fun parseChallenge(headerValue: String): Challenge {
        val trimmed = headerValue.trim()
        require(trimmed.startsWith("Digest", ignoreCase = true)) {
            "Not a Digest challenge: $trimmed"
        }
        val params = parseParams(trimmed.substring(6).trim())
        return Challenge(
            realm = params["realm"] ?: error("realm missing"),
            nonce = params["nonce"] ?: error("nonce missing"),
            algorithm = params["algorithm"] ?: "MD5",
            qop = params["qop"],
            opaque = params["opaque"]
        )
    }

    /**
     * Compute the Digest response and assemble the Authorization header value.
     *
     * `username` is typically the GB28181 device ID (e.g. 34020000001110000001).
     * `uri` is the SIP request URI of the REGISTER (e.g. `sip:34020...@3402000000`).
     */
    fun buildResponse(
        challenge: Challenge,
        username: String,
        password: String,
        method: String,
        uri: String,
        cnonce: String? = null,
        nc: String = "00000001"
    ): String {
        require(challenge.algorithm.equals("MD5", ignoreCase = true)) {
            "Only MD5 algorithm is supported (got ${challenge.algorithm})"
        }
        val ha1 = Md5.hashHex("$username:${challenge.realm}:$password")
        val ha2 = Md5.hashHex("$method:$uri")
        val response: String
        val builder = StringBuilder()
        builder.append("Digest ")
        builder.append("username=\"").append(username).append("\",")
        builder.append("realm=\"").append(challenge.realm).append("\",")
        builder.append("nonce=\"").append(challenge.nonce).append("\",")
        builder.append("uri=\"").append(uri).append("\",")
        if (challenge.qop != null) {
            // qop=auth: response = MD5(HA1:nonce:nc:cnonce:qop:HA2)
            val qopValue = challenge.qop.split(",").first().trim().trim('"')
            val effectiveCnonce = cnonce ?: defaultCnonce()
            response = Md5.hashHex("$ha1:${challenge.nonce}:$nc:$effectiveCnonce:$qopValue:$ha2")
            builder.append("response=\"").append(response).append("\",")
            builder.append("algorithm=").append(challenge.algorithm).append(",")
            builder.append("cnonce=\"").append(effectiveCnonce).append("\",")
            builder.append("nc=").append(nc).append(",")
            builder.append("qop=").append(qopValue)
        } else {
            // No qop: response = MD5(HA1:nonce:HA2)
            response = Md5.hashHex("$ha1:${challenge.nonce}:$ha2")
            builder.append("response=\"").append(response).append("\",")
            builder.append("algorithm=").append(challenge.algorithm)
        }
        if (challenge.opaque != null) {
            builder.append(",opaque=\"").append(challenge.opaque).append("\"")
        }
        return builder.toString()
    }

    private fun defaultCnonce(): String {
        // 简单的伪随机(纯 Kotlin,跨平台)。生产里要走平台 secure random,M1 够用。
        var seed = 0x9E3779B97F4A7C15uL
        seed = seed xor (seed shl 13).toULong()
        seed = seed xor (seed shr 7).toULong()
        seed = seed xor (seed shl 17).toULong()
        return Md5.hashHex(seed.toString().encodeToByteArray()).take(16)
    }

    /**
     * Parse `key=value, key="value", ...` style attribute lists.
     * Tolerates spaces, optional quoting, comma separators.
     */
    internal fun parseParams(text: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        var i = 0
        while (i < text.length) {
            // skip whitespace + commas
            while (i < text.length && (text[i] == ' ' || text[i] == ',' || text[i] == '\t')) i++
            if (i >= text.length) break
            // read key
            val keyStart = i
            while (i < text.length && text[i] != '=' && text[i] != ',' && text[i] != ' ') i++
            val key = text.substring(keyStart, i).trim()
            if (key.isEmpty()) break
            // skip = and surrounding whitespace
            while (i < text.length && (text[i] == ' ' || text[i] == '=')) i++
            // read value (quoted or unquoted)
            val value: String
            if (i < text.length && text[i] == '"') {
                i++
                val valStart = i
                while (i < text.length && text[i] != '"') i++
                value = text.substring(valStart, i)
                if (i < text.length) i++
            } else {
                val valStart = i
                while (i < text.length && text[i] != ',') i++
                value = text.substring(valStart, i).trim()
            }
            result[key.lowercase()] = value
        }
        return result
    }

    data class Challenge(
        val realm: String,
        val nonce: String,
        val algorithm: String,
        val qop: String?,
        val opaque: String?
    )
}
