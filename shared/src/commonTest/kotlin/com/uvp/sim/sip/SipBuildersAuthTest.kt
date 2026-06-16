package com.uvp.sim.sip

import com.uvp.sim.config.DeviceConfig
import com.uvp.sim.config.GbVersion
import com.uvp.sim.config.ServerConfig
import com.uvp.sim.config.SimConfig
import com.uvp.sim.network.TransportType
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Regression for the registration 401 loop bug:
 * the platform replies 401 to every (retransmitted) REGISTER, and each pass through
 * the 401 handler used to *append* a fresh Authorization header onto the already-authed
 * pendingRegister — producing a multi-Authorization message the platform can't parse,
 * so it keeps replying 401. addAuthorization must *replace*, keeping exactly one.
 */
class SipBuildersAuthTest {

    private fun config() = SimConfig(
        gbVersion = GbVersion.V2022,
        server = ServerConfig(
            ip = "192.168.10.222", port = 15060,
            serverId = "34020000002000000001", domain = "3402000000"
        ),
        device = DeviceConfig(
            deviceId = "35020000001310000001",
            videoChannelId = "35020000001320000001",
            alarmChannelId = "35020000001340000001",
            username = "35020000001310000001",
            password = "gbs12345"
        ),
        transport = TransportType.UDP,
        keepaliveIntervalSeconds = 60
    )

    private fun baseRegister() = SipBuilders.buildRegister(
        config = config(), cseq = 1, callId = "call-1",
        branch = "z9hG4bKinit", fromTag = "tag1",
        localIp = "192.168.10.112", localPort = 5060
    )

    @Test
    fun addAuthorization_keepsExactlyOneAuthorizationOnFirst401() {
        val authed = SipBuilders.addAuthorization(
            baseRegister(), "Digest username=\"x\",response=\"aaa\"", 2, "z9hG4bKnew"
        )
        assertEquals(1, authed.allHeaders(SipHeader.AUTHORIZATION).size)
    }

    @Test
    fun addAuthorization_replacesStaleAuthorizationOnSecond401() {
        // Simulate the loop: authed once, then the same pending gets re-authed on the next 401.
        val firstAuthed = SipBuilders.addAuthorization(
            baseRegister(), "Digest username=\"x\",response=\"first\"", 2, "z9hG4bKb1"
        )
        val secondAuthed = SipBuilders.addAuthorization(
            firstAuthed, "Digest username=\"x\",response=\"second\"", 3, "z9hG4bKb2"
        )
        val auths = secondAuthed.allHeaders(SipHeader.AUTHORIZATION)
        assertEquals(1, auths.size, "stale Authorization must be replaced, not stacked")
        assertEquals("Digest username=\"x\",response=\"second\"", auths.first())
    }

    @Test
    fun addAuthorization_stillUpdatesCseqAndBranch() {
        val authed = SipBuilders.addAuthorization(
            baseRegister(), "Digest response=\"r\"", 7, "z9hG4bKxyz"
        )
        assertEquals("7 REGISTER", authed.firstHeader(SipHeader.CSEQ))
        val via = authed.firstHeader(SipHeader.VIA)!!
        assertEquals(true, via.contains("branch=z9hG4bKxyz"))
    }
}
