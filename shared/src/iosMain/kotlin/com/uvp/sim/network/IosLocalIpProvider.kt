package com.uvp.sim.network

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import platform.posix.AF_INET
import platform.posix.NI_MAXHOST
import platform.posix.NI_NUMERICHOST
import platform.posix.getnameinfo
import platform.posix.sockaddr_in
import platform.posix.socklen_t
import platform.darwin.freeifaddrs
import platform.darwin.getifaddrs
import platform.darwin.ifaddrs

internal data class IosNetworkInterface(
    val name: String,
    val ipv4: String,
    val isLoopback: Boolean,
)

internal object IosLocalIpProvider {
    @kotlin.concurrent.Volatile
    private var lastActiveIp: String? = null

    fun currentActiveIp(): String? = lastActiveIp ?: resolveActiveIpv4(readInterfaces(), null)

    fun refresh(interfaceType: String): String? {
        val resolved = resolveActiveIpv4(readInterfaces(), interfaceType)
        lastActiveIp = resolved
        return resolved
    }

    fun resolveActiveIpv4(
        interfaces: List<IosNetworkInterface>,
        interfaceType: String?,
    ): String? {
        val usable = interfaces.filter { it.ipv4.isNotBlank() && !it.isLoopback }
        if (usable.isEmpty()) return null
        val preferredNames = when (interfaceType) {
            "wifi" -> listOf("en0")
            "cellular" -> listOf("pdp_ip0", "pdp_ip1", "pdp_ip2", "pdp_ip3")
            else -> emptyList()
        }
        preferredNames.forEach { name ->
            usable.firstOrNull { it.name == name }?.let { return it.ipv4 }
        }
        return usable.first().ipv4
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun readInterfaces(): List<IosNetworkInterface> = memScoped {
        val head = alloc<CPointerVar<ifaddrs>>()
        if (getifaddrs(head.ptr) != 0) return@memScoped emptyList()
        val result = mutableListOf<IosNetworkInterface>()
        try {
            var cursor: CPointer<ifaddrs>? = head.value
            while (cursor != null) {
                val item = cursor.pointed
                val address = item.ifa_addr
                if (address != null && address.pointed.sa_family.toInt() == AF_INET) {
                    val name = item.ifa_name?.toKString().orEmpty()
                    val host = allocArray<kotlinx.cinterop.ByteVar>(NI_MAXHOST)
                    val status = getnameinfo(
                        address,
                        sockaddrInLength(),
                        host,
                        NI_MAXHOST.convert(),
                        null,
                        0u,
                        NI_NUMERICHOST,
                    )
                    if (status == 0) {
                        val ip = host.toKString()
                        result += IosNetworkInterface(
                            name = name,
                            ipv4 = ip,
                            isLoopback = name == "lo0" || ip.startsWith("127."),
                        )
                    }
                }
                cursor = item.ifa_next
            }
        } finally {
            freeifaddrs(head.value)
        }
        result
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun sockaddrInLength(): socklen_t =
        kotlinx.cinterop.sizeOf<sockaddr_in>().convert()
}
