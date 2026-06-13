package com.uvp.sim.sip

/**
 * SIP request method as defined by RFC 3261 + GB28181 subset used by Sim.
 * See plan v1 §4.2.
 */
enum class SipMethod {
    REGISTER, MESSAGE, INVITE, ACK, BYE, SUBSCRIBE, NOTIFY, OPTIONS, CANCEL, INFO;

    companion object {
        fun fromString(name: String): SipMethod? =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }
}
