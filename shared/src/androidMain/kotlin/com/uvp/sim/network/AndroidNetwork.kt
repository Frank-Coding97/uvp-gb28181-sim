package com.uvp.sim.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import java.net.Inet4Address
import java.net.InetAddress
import java.net.DatagramSocket
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Android-specific helpers for resolving the local IPv4 address used in SIP
 * Via and Contact headers.
 *
 * Why this matters: GB28181 platforms (WVP, EasyCVR) reflect the Via header's
 * `received` / `rport` parameters back to the device when sending the 200 OK
 * and subsequent in-dialog requests. If we send `Via: ...192.168.1.5:9876` then
 * SIP responses come back to that exact addr+port. If we leave it as
 * `0.0.0.0`, WVP shows the device but later INVITEs route to the wrong place.
 *
 * We use ConnectivityManager + LinkProperties because WifiManager's
 * `connectionInfo.ipAddress` is deprecated in Android 12+ and returns 0 on
 * VPN / cellular connections.
 */
object AndroidNetwork {

    /** UDP connect 只查询系统选路，不向对端发送数据。遵循 VPN 路由及进程网络绑定。 */
    suspend fun routeIpv4(remoteHost: String, remotePort: Int = 5060): String? = withContext(Dispatchers.IO) {
        try {
            val remote = InetAddress.getAllByName(remoteHost).filterIsInstance<Inet4Address>().firstOrNull()
                ?: return@withContext null
            DatagramSocket().use { socket ->
                socket.connect(remote, remotePort)
                (socket.localAddress as? Inet4Address)
                    ?.takeUnless { it.isAnyLocalAddress }
                    ?.hostAddress
            }
        } catch (_: IOException) {
            null
        }
    }

    /**
     * Returns the IPv4 address of the device on its currently active default
     * network. Returns null if not connected, no IPv4 link, or context is bad.
     *
     * This favors the active default network (the one Android routes packets
     * through). Works on WiFi, cellular, ethernet, and over Tailscale/VPN.
     */
    fun activeIpv4(context: Context): String? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return null
        val active = cm.activeNetwork ?: return null
        val link = cm.getLinkProperties(active) ?: return null
        return link.linkAddresses
            .map(LinkAddress::getAddress)
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress && !it.isAnyLocalAddress }
            ?.hostAddress
    }
}
