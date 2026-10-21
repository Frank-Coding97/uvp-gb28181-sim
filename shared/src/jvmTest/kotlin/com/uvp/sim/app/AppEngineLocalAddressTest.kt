package com.uvp.sim.app

import com.uvp.sim.config.SimConfig
import com.uvp.sim.config.ServerConfig
import com.uvp.sim.config.DeviceConfig
import com.uvp.sim.sip.SipHeader
import com.uvp.sim.sip.SipParser
import com.uvp.sim.sip.SipRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AppEngineLocalAddressTest {
    @Test fun register_uses_platform_route_and_refreshes_on_reconnect() = runBlocking {
        DatagramSocket(0, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 5000
            var routeIp = "192.168.124.4"
            val resources = object : PlatformResources by FakePlatformResources(localIpProvider = { "10.8.0.4" }) {
                override suspend fun localIpFor(remoteHost: String): String {
                    assertEquals("127.0.0.1", remoteHost)
                    return routeIp
                }
            }
            val cfg = SimConfig(
                server = ServerConfig("127.0.0.1", server.localPort, "34020000002000000002", "3402000000"),
                device = DeviceConfig(deviceId = "37010301022000000007", videoChannelId = "34020000001320000020",
                    alarmChannelId = "34020000001340000020", username = "37010301022000000007", password = "test"),
            )
            val scope = CoroutineScope(Dispatchers.Default)
            val app = AppEngine(resources, FakePlatformRuntime(), cfg, scope)
            try {
                repeat(2) {
                    app.connect()
                    val packet = DatagramPacket(ByteArray(65535), 65535)
                    server.receive(packet)
                    val register = SipParser.parse(packet.data.copyOf(packet.length)) as SipRequest
                    assertTrue(register.firstHeader(SipHeader.VIA)!!.contains(" $routeIp:"))
                    assertTrue(register.firstHeader(SipHeader.CONTACT)!!.contains("@$routeIp:"))
                    app.cancelConnect()
                    routeIp = "10.8.0.4"
                }
            } finally {
                app.cancelConnect()
                scope.cancel()
            }
        }
    }
}
