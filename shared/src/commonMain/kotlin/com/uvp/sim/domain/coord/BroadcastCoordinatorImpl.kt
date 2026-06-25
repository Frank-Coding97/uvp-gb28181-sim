package com.uvp.sim.domain.coord

import com.uvp.sim.config.SimConfig
import com.uvp.sim.domain.BroadcastDialog
import com.uvp.sim.domain.BroadcastDialogState
import com.uvp.sim.domain.BroadcastEndReason
import com.uvp.sim.network.RtpMode
import com.uvp.sim.network.SipTransport
import com.uvp.sim.sip.SipMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [BroadcastCoordinator] 的 PR5 T5.1 RED 空 stub。
 *
 * 真实现 T5.3 GREEN 落地(把 Engine 上 RX 链 + InviteCoordinatorImpl 上 broadcast
 * handshake 整套迁过来,实现 BroadcastInvoker)。
 */
internal class BroadcastCoordinatorImpl(
    private val config: SimConfig,
    private val transport: SipTransport,
    private val scope: CoroutineScope,
    private val localIpProvider: () -> String = { "0.0.0.0" },
    private val localPortProvider: () -> Int = { 5060 },
    private val rtpReceiverFactory: ((CoroutineScope) -> com.uvp.sim.network.BroadcastRxSource)? = null,
    private val audioSinkFactory: ((Int, Int) -> com.uvp.sim.media.AudioSink)? = null,
    private val simEventEmit: suspend (com.uvp.sim.domain.SimEvent) -> Unit = {},
    cseqProvider: (() -> Int)? = null,
    cseqIncrementer: (() -> Int)? = null,
    callIdProvider: (() -> String?)? = null,
    callIdSetter: ((String) -> Unit)? = null,
    fromTagProvider: (() -> String?)? = null,
    fromTagSetter: ((String) -> Unit)? = null,
) : BroadcastCoordinator {

    private val _state = MutableStateFlow(BroadcastDialogState.Inviting)
    override val state: StateFlow<BroadcastDialogState> = _state.asStateFlow()

    private val _current = MutableStateFlow<BroadcastDialog?>(null)
    override val current: StateFlow<BroadcastDialog?> = _current.asStateFlow()

    private val _speakerOn = MutableStateFlow(true)
    override val speakerOn: StateFlow<Boolean> = _speakerOn.asStateFlow()

    private val _events = MutableSharedFlow<BroadcastCoordEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<BroadcastCoordEvent> = _events.asSharedFlow()

    // SN 池 provider 适配
    private var internalCseq: Int = 0
    private var internalCallId: String? = null
    private var internalFromTag: String? = null
    private val cseqRead: () -> Int = cseqProvider ?: { internalCseq }
    private val cseqIncAndRead: () -> Int = cseqIncrementer ?: { internalCseq += 1; internalCseq }
    private val callIdRead: () -> String? = callIdProvider ?: { internalCallId }
    private val callIdWrite: (String) -> Unit = callIdSetter ?: { internalCallId = it }
    private val fromTagRead: () -> String? = fromTagProvider ?: { internalFromTag }
    private val fromTagWrite: (String) -> Unit = fromTagSetter ?: { internalFromTag = it }

    override suspend fun onIncoming(msg: SipMessage): RoutingResult {
        throw NotImplementedError("PR5 T5.1 RED stub - implement in T5.3 GREEN")
    }

    override suspend fun shutdown() {}

    override suspend fun fireBroadcastInvite(sourceId: String, platformUri: String, targetId: String) {
        throw NotImplementedError("PR5 T5.1 RED stub - implement in T5.3 GREEN")
    }

    override fun setSpeaker(on: Boolean) {
        _speakerOn.value = on
    }

    override suspend fun stop(reason: BroadcastEndReason) {
        throw NotImplementedError("PR5 T5.1 RED stub - implement in T5.3 GREEN")
    }

    override fun debugSnapshot(): BroadcastDebugSnapshot =
        BroadcastDebugSnapshot(rxPacketCount = 0L, decodeErrorCount = 0L, rxActive = false)
}
