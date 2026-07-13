package com.uvp.sim.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class IosLocalIpProviderTest {

    @Test
    fun resolver_prefers_en0_for_wifi() {
        val interfaces = listOf(
            IosNetworkInterface("pdp_ip0", "10.10.0.2", isLoopback = false),
            IosNetworkInterface("en0", "192.168.1.23", isLoopback = false),
        )

        assertEquals("192.168.1.23", IosLocalIpProvider.resolveActiveIpv4(interfaces, "wifi"))
    }

    @Test
    fun resolver_prefers_pdp_ip0_for_cellular() {
        val interfaces = listOf(
            IosNetworkInterface("en0", "192.168.1.23", isLoopback = false),
            IosNetworkInterface("pdp_ip0", "10.10.0.2", isLoopback = false),
        )

        assertEquals("10.10.0.2", IosLocalIpProvider.resolveActiveIpv4(interfaces, "cellular"))
    }

    @Test
    fun resolver_falls_back_to_first_non_loopback_ipv4() {
        val interfaces = listOf(
            IosNetworkInterface("lo0", "127.0.0.1", isLoopback = true),
            IosNetworkInterface("bridge100", "172.16.0.1", isLoopback = false),
            IosNetworkInterface("en0", "192.168.1.23", isLoopback = false),
        )

        assertEquals("172.16.0.1", IosLocalIpProvider.resolveActiveIpv4(interfaces, "other"))
    }

    @Test
    fun resolver_returns_null_when_only_loopback_exists() {
        val interfaces = listOf(
            IosNetworkInterface("lo0", "127.0.0.1", isLoopback = true),
        )

        assertNull(IosLocalIpProvider.resolveActiveIpv4(interfaces, "wifi"))
    }
}
