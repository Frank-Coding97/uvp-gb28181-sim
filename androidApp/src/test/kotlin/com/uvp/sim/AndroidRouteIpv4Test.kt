package com.uvp.sim

import com.uvp.sim.network.AndroidNetwork
import com.uvp.sim.app.PlatformResourcesAndroid
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AndroidRouteIpv4Test {
    @Test fun resolves_kernel_route_without_sending_a_packet() = runBlocking {
        DatagramSocket(0, InetAddress.getByName("127.0.0.1")).use { receiver ->
            receiver.soTimeout = 150
            assertEquals("127.0.0.1", AndroidNetwork.routeIpv4("127.0.0.1", receiver.localPort))
            try {
                receiver.receive(DatagramPacket(ByteArray(64), 64))
                throw AssertionError("route lookup must not send a probe")
            } catch (_: SocketTimeoutException) { }
        }
    }

    @Test fun invalid_address_has_no_route() = runBlocking {
        assertNull(AndroidNetwork.routeIpv4("256.256.256.256"))
    }

    @Test fun explicit_network_binding_takes_priority_over_auto_route() = runBlocking {
        val resources = PlatformResourcesAndroid(
            ApplicationProvider.getApplicationContext(), networkLocalIp = { "192.168.124.4" },
        )
        assertEquals("192.168.124.4", resources.localIpFor("127.0.0.1"))
    }
}
