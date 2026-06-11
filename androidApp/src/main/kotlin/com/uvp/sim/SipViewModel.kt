package com.uvp.sim

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.uvp.sim.config.DeviceConfig
import com.uvp.sim.config.GbVersion
import com.uvp.sim.config.ServerConfig
import com.uvp.sim.config.SimConfig
import com.uvp.sim.domain.SimEvent
import com.uvp.sim.domain.SimulatorEngine
import com.uvp.sim.network.AndroidNetwork
import com.uvp.sim.network.RemoteEndpoint
import com.uvp.sim.network.TransportType
import com.uvp.sim.network.UdpSipTransport
import com.uvp.sim.sip.SipState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Glues the cross-platform [SimulatorEngine] to Android lifecycle.
 *
 * **M1 hard-codes the WVP target** in [Companion]; T11 will replace these
 * constants with a settings screen + persisted store.
 *
 * Lifecycle:
 *   - [connect] is idempotent. Creates transport on first call, reuses on retry.
 *   - [disconnect] sends Unregister, closes transport, but keeps the VM alive
 *     so the user can press Connect again.
 *   - [onCleared] tears everything down (Activity destroyed).
 */
class SipViewModel(application: Application) : AndroidViewModel(application) {

    private val engineScope = CoroutineScope(viewModelScope.coroutineContext + SupervisorJob())

    private var transport: UdpSipTransport? = null
    private var engine: SimulatorEngine? = null

    private val _state = MutableStateFlow(SipState.Disconnected)
    val state: StateFlow<SipState> = _state.asStateFlow()

    private val _events = MutableStateFlow<List<SimEvent>>(emptyList())
    val events: StateFlow<List<SimEvent>> = _events.asStateFlow()

    val serverLabel: String = "${WVP_IP}:${WVP_PORT}  (${WVP_SERVER_ID})"
    val deviceLabel: String = DEVICE_ID

    fun connect() {
        if (engine != null) return  // already wired up

        val ctx = getApplication<Application>()
        val localIp = AndroidNetwork.activeIpv4(ctx) ?: "0.0.0.0"

        val config = SimConfig(
            gbVersion = GbVersion.V2022,
            server = ServerConfig(
                ip = WVP_IP,
                port = WVP_PORT,
                serverId = WVP_SERVER_ID,
                domain = WVP_DOMAIN
            ),
            device = DeviceConfig(
                deviceId = DEVICE_ID,
                videoChannelId = VIDEO_CHANNEL_ID,
                alarmChannelId = ALARM_CHANNEL_ID,
                username = DEVICE_ID,
                password = WVP_PASSWORD
            ),
            transport = TransportType.UDP,
            keepaliveIntervalSeconds = 60
        )

        val tx = UdpSipTransport(
            remote = RemoteEndpoint(WVP_IP, WVP_PORT, TransportType.UDP),
            parentScope = engineScope
        )
        transport = tx

        val eng = SimulatorEngine(
            config = config,
            transport = tx,
            scope = engineScope,
            localIp = localIp,
            localPortProvider = { tx.localPort.takeIf { it > 0 } ?: 5060 }
        )
        engine = eng

        // pipe state + events into UI flows
        engineScope.launch {
            eng.state.collect { _state.value = it }
        }
        engineScope.launch {
            eng.events.collect { ev ->
                _events.update { current ->
                    (listOf(ev) + current).take(MAX_EVENT_LOG)
                }
            }
        }

        engineScope.launch {
            try {
                tx.connect()
                eng.register()
            } catch (e: Throwable) {
                _events.update { current ->
                    (listOf(SimEvent.TransportError("connect: ${e::class.simpleName}: ${e.message}")) + current)
                        .take(MAX_EVENT_LOG)
                }
            }
        }
    }

    fun disconnect() {
        val eng = engine ?: return
        engineScope.launch {
            try {
                eng.unregister()
            } catch (_: Throwable) { /* ignore */ }
            try {
                eng.shutdown()
            } catch (_: Throwable) { /* ignore */ }
            try {
                transport?.close()
            } catch (_: Throwable) { /* ignore */ }
            engine = null
            transport = null
        }
    }

    override fun onCleared() {
        super.onCleared()
        // Best-effort teardown; viewModelScope handles its own cancellation.
        try {
            kotlinx.coroutines.runBlocking {
                engine?.shutdown()
                transport?.close()
            }
        } catch (_: Throwable) { /* ignore */ }
    }

    companion object {
        // ===== WVP target (M1 hard-coded; T11 will move to settings) =====
        const val WVP_IP = "192.168.10.222"
        const val WVP_PORT = 8160
        const val WVP_SERVER_ID = "35020000002000000001"
        const val WVP_DOMAIN = "3502000000"
        const val WVP_PASSWORD = "wvp_sip_password"

        const val DEVICE_ID = "35020000001310000001"
        const val VIDEO_CHANNEL_ID = "35020000001320000001"
        const val ALARM_CHANNEL_ID = "35020000001340000001"

        const val MAX_EVENT_LOG = 100
    }
}

/** Convenience extension for atomic updates on MutableStateFlow<List>. */
private inline fun <T> MutableStateFlow<T>.update(transform: (T) -> T) {
    while (true) {
        val prev = value
        val next = transform(prev)
        if (compareAndSet(prev, next)) return
    }
}
