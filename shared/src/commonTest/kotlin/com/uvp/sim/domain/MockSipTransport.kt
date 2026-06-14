package com.uvp.sim.domain

import com.uvp.sim.network.RemoteEndpoint
import com.uvp.sim.network.SipTransport
import com.uvp.sim.network.TransportType
import com.uvp.sim.sip.SipMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * In-memory SipTransport — captures sent messages, lets tests inject responses.
 */
class MockSipTransport(
    val remote: RemoteEndpoint = RemoteEndpoint("127.0.0.1", 5060, TransportType.UDP)
) : SipTransport {

    val sent = mutableListOf<SipMessage>()
    private var connected = false
    private val _incoming = MutableSharedFlow<SipMessage>(replay = 0, extraBufferCapacity = 64)
    override val incoming: Flow<SipMessage> = _incoming.asSharedFlow()
    override val localPort: Int = 5060

    override suspend fun connect() { connected = true }
    override suspend fun close() { connected = false }
    override suspend fun send(message: SipMessage) {
        check(connected) { "MockSipTransport not connected" }
        sent += message
    }

    /** Test helper: inject an incoming message. */
    suspend fun deliver(message: SipMessage) {
        _incoming.emit(message)
    }
}
