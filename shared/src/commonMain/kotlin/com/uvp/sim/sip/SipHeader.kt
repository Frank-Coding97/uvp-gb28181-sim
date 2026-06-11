package com.uvp.sim.sip

/**
 * Canonical names of the SIP headers used by the simulator.
 * Header parsing is case-insensitive; serialization uses the canonical name here.
 */
object SipHeader {
    const val VIA = "Via"
    const val FROM = "From"
    const val TO = "To"
    const val CALL_ID = "Call-ID"
    const val CSEQ = "CSeq"
    const val CONTACT = "Contact"
    const val MAX_FORWARDS = "Max-Forwards"
    const val USER_AGENT = "User-Agent"
    const val EXPIRES = "Expires"
    const val CONTENT_TYPE = "Content-Type"
    const val CONTENT_LENGTH = "Content-Length"
    const val AUTHORIZATION = "Authorization"
    const val WWW_AUTHENTICATE = "WWW-Authenticate"
    const val SUBSCRIPTION_STATE = "Subscription-State"
    const val EVENT = "Event"

    /** Convert any-case header name to canonical (matching one of the above). */
    fun canonicalize(name: String): String {
        val lower = name.lowercase()
        return when (lower) {
            "via", "v" -> VIA
            "from", "f" -> FROM
            "to", "t" -> TO
            "call-id", "i" -> CALL_ID
            "cseq" -> CSEQ
            "contact", "m" -> CONTACT
            "max-forwards" -> MAX_FORWARDS
            "user-agent" -> USER_AGENT
            "expires" -> EXPIRES
            "content-type", "c" -> CONTENT_TYPE
            "content-length", "l" -> CONTENT_LENGTH
            "authorization" -> AUTHORIZATION
            "www-authenticate" -> WWW_AUTHENTICATE
            "subscription-state" -> SUBSCRIPTION_STATE
            "event", "o" -> EVENT
            else -> name  // unknown header -> keep as-is
        }
    }
}
