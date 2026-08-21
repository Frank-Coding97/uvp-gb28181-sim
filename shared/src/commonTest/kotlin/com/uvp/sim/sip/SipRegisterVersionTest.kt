package com.uvp.sim.sip

import com.uvp.sim.config.DeviceConfig
import com.uvp.sim.config.GbVersion
import com.uvp.sim.config.ServerConfig
import com.uvp.sim.config.SimConfig
import kotlin.test.Test
import kotlin.test.assertEquals

class SipRegisterVersionTest {

    @Test
    fun registerMapsSelectedStandardToXGbVer() {
        val cases = listOf(
            GbVersion.V2016 to "2.0",
            GbVersion.V2022 to "3.0",
        )

        cases.forEach { (version, expected) ->
            val register = buildRegister(config(version))
            assertEquals(expected, register.firstHeader("X-GB-Ver"), version.name)
        }
    }

    @Test
    fun authenticatedRegisterAndUnregisterPreserveXGbVer() {
        val config = config(GbVersion.V2022)
        val register = buildRegister(config)
        val authenticated = SipBuilders.addAuthorization(
            register = register,
            authorizationHeader = "Digest response=\"test\"",
            newCseq = 2,
            newBranch = "z9hG4bK-auth",
        )
        val unregister = SipBuilders.buildUnregister(
            config = config,
            cseq = 3,
            callId = "register-version@test",
            branch = "z9hG4bK-unregister",
            fromTag = "version-test",
            localIp = "192.0.2.10",
            localPort = 5060,
        )

        assertEquals("3.0", authenticated.firstHeader("X-GB-Ver"))
        assertEquals("3.0", unregister.firstHeader("X-GB-Ver"))
    }

    private fun buildRegister(config: SimConfig): SipRequest = SipBuilders.buildRegister(
        config = config,
        cseq = 1,
        callId = "register-version@test",
        branch = "z9hG4bK-register",
        fromTag = "version-test",
        localIp = "192.0.2.10",
        localPort = 5060,
    )

    private fun config(version: GbVersion) = SimConfig(
        gbVersion = version,
        server = ServerConfig(
            ip = "192.0.2.20",
            serverId = "34020000002000000001",
            domain = "3402000000",
        ),
        device = DeviceConfig(
            deviceId = "34020000001110000001",
            videoChannelId = "34020000001320000001",
            alarmChannelId = "34020000001340000001",
            username = "34020000001110000001",
            password = "test-password",
        ),
    )
}
