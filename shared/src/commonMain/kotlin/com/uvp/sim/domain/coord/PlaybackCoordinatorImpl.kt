package com.uvp.sim.domain.coord

import com.uvp.sim.config.SimConfig
import com.uvp.sim.network.SipTransport
import com.uvp.sim.recording.RecordingService
import com.uvp.sim.sip.SipMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [PlaybackCoordinator] 的 PR5 T5.1 RED 空 stub。
 *
 * 真实现 T5.2 GREEN 落地(把 InviteCoordinatorImpl 上 handlePlaybackInvite /
 * handleInfo / sendBye / sendMediaStatusNotify / stopActivePlayback /
 * ActivePlayback / MediaMode 迁过来)。
 */
internal class PlaybackCoordinatorImpl(
    private val config: SimConfig,
    private val transport: SipTransport,
    private val scope: CoroutineScope,
    private val localIpProvider: () -> String = { "0.0.0.0" },
    private val localPortProvider: () -> Int = { 5060 },
    private val playbackBuilder: com.uvp.sim.domain.PlaybackBuilder? = null,
    private val recordingService: RecordingService = com.uvp.sim.recording.NoopRecordingService,
    private val simEventEmit: suspend (com.uvp.sim.domain.SimEvent) -> Unit = {},
    cseqProvider: (() -> Int)? = null,
    cseqIncrementer: (() -> Int)? = null,
    callIdProvider: (() -> String?)? = null,
    callIdSetter: ((String) -> Unit)? = null,
    fromTagProvider: (() -> String?)? = null,
    fromTagSetter: ((String) -> Unit)? = null,
) : PlaybackCoordinator {

    private val _state = MutableStateFlow(PlaybackState.Idle)
    override val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<PlaybackEvent>(extraBufferCapacity = 32)
    override val events: SharedFlow<PlaybackEvent> = _events.asSharedFlow()

    // SN 池 provider 适配(跟 PR2/3/4 同模式;T5.2 实装)
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
        throw NotImplementedError("PR5 T5.1 RED stub - implement in T5.2 GREEN")
    }

    override suspend fun shutdown() {}

    override suspend fun stop(reason: String) {
        throw NotImplementedError("PR5 T5.1 RED stub - implement in T5.2 GREEN")
    }
}
