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
 * Android implementation of [RtpSender] using Ktor's `aSocket().udp()`.
 *
 * The socket binds to 0.0.0.0:0 (let the OS pick an ephemeral port). The
 * caller (SimulatorEngine) reads `localPort` after bindLocalPort() to
 * advertise it back to the platform via SDP answer.
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
