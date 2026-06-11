package com.uvp.sim.network

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.BoundDatagramSocket
import io.ktor.network.sockets.Datagram
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.aSocket
import io.ktor.utils.io.core.ByteReadPacket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * iOS implementation of [RtpSender] using Ktor's `aSocket().udp()`.
 *
 * Same shape as Android — Ktor's Native UDP socket implementation works on
 * iOS arm64 / x64 / simulator-arm64. The only platform-specific consideration
 * is that on iOS, sending UDP from background is restricted by the OS; the
 * simulator runs strictly in foreground per spec v1, so this is fine.
 */
actual class RtpSender actual constructor(
    actual val remoteHost: String,
    actual val remotePort: Int,
    private val parentScope: CoroutineScope?
) {
    private val mutex = Mutex()
    private var socket: BoundDatagramSocket? = null
    private var selector: SelectorManager? = null
    private val ownedScope: CoroutineScope = parentScope
        ?: CoroutineScope(SupervisorJob() + Dispatchers.Default)

    actual val localPort: Int
        get() = (socket?.localAddress as? InetSocketAddress)?.port ?: -1

    actual suspend fun bindLocalPort(): Int = mutex.withLock {
        if (socket != null) return localPort
        val sm = SelectorManager(Dispatchers.Default)
        val sk = aSocket(sm).udp().bind(InetSocketAddress("0.0.0.0", 0))
        selector = sm
        socket = sk
        return localPort
    }

    actual suspend fun send(packet: ByteArray) {
        val sk = socket ?: error("RtpSender not bound — call bindLocalPort() first")
        sk.send(
            Datagram(
                packet = ByteReadPacket(packet),
                address = InetSocketAddress(remoteHost, remotePort)
            )
        )
    }

    actual suspend fun close() = mutex.withLock {
        socket?.close()
        socket = null
        selector?.close()
        selector = null
        if (parentScope == null) {
            ownedScope.cancel()
        }
    }
}
