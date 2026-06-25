package com.uvp.sim.app

import com.uvp.sim.config.SimConfig
import com.uvp.sim.domain.SimEvent
import com.uvp.sim.sip.SipState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * AppEngine — 装配根(PR6 T6.1 RED stub)。
 *
 * commonMain 持有 SimulatorEngine + 5 Coordinator + transport 装配,
 * Android/iOS ViewModel 退化成薄转发。
 *
 * T6.3 GREEN 真实现替换全部 throw NotImplementedError。
 */
class AppEngine(
    private val resources: AndroidResources,
    initialConfig: SimConfig,
    private val parentScope: CoroutineScope,
) {
    private val _config = MutableStateFlow(initialConfig)
    val config: StateFlow<SimConfig> = _config.asStateFlow()

    // 9 个 StateFlow + 1 个 events SharedFlow(暴露给 ViewModel)
    private val _state = MutableStateFlow(SipState.Disconnected)
    val state: StateFlow<SipState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<SimEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<SimEvent> = _events.asSharedFlow()

    private val _subscriptions = MutableStateFlow<Map<String, com.uvp.sim.domain.SubscriptionSnapshot>>(emptyMap())
    val subscriptions: StateFlow<Map<String, com.uvp.sim.domain.SubscriptionSnapshot>> = _subscriptions.asStateFlow()

    private val _deviceControlState = MutableStateFlow(com.uvp.sim.domain.DeviceControlState())
    val deviceControlState: StateFlow<com.uvp.sim.domain.DeviceControlState> = _deviceControlState.asStateFlow()

    private val _catalogTree = MutableStateFlow<List<com.uvp.sim.config.CatalogNode>>(emptyList())
    val catalogTree: StateFlow<List<com.uvp.sim.config.CatalogNode>> = _catalogTree.asStateFlow()

    private val _alarmHistory = MutableStateFlow<List<com.uvp.sim.domain.AlarmRecord>>(emptyList())
    val alarmHistory: StateFlow<List<com.uvp.sim.domain.AlarmRecord>> = _alarmHistory.asStateFlow()

    private val _currentChannelName = MutableStateFlow(initialConfig.device.videoChannelName)
    val currentChannelName: StateFlow<String> = _currentChannelName.asStateFlow()

    private val _clockOffset = MutableStateFlow(com.uvp.sim.domain.ClockOffset.Empty)
    val clockOffset: StateFlow<com.uvp.sim.domain.ClockOffset> = _clockOffset.asStateFlow()

    private val _currentBroadcast = MutableStateFlow<com.uvp.sim.domain.BroadcastDialog?>(null)
    val currentBroadcast: StateFlow<com.uvp.sim.domain.BroadcastDialog?> = _currentBroadcast.asStateFlow()

    private val _broadcastSpeakerOn = MutableStateFlow(true)
    val broadcastSpeakerOn: StateFlow<Boolean> = _broadcastSpeakerOn.asStateFlow()

    suspend fun connect() {
        throw NotImplementedError("PR6 T6.1 RED stub - implement in T6.3 GREEN")
    }

    suspend fun cancelConnect() {
        throw NotImplementedError("PR6 T6.1 RED stub - implement in T6.3 GREEN")
    }

    suspend fun disconnect() {
        throw NotImplementedError("PR6 T6.1 RED stub - implement in T6.3 GREEN")
    }

    suspend fun updateConfig(new: SimConfig) {
        throw NotImplementedError("PR6 T6.1 RED stub - implement in T6.3 GREEN")
    }

    suspend fun handleNetworkChange(state: com.uvp.sim.network.NetworkState) {
        throw NotImplementedError("PR6 T6.1 RED stub - implement in T6.3 GREEN")
    }

    suspend fun reportSnapshot() {
        throw NotImplementedError("PR6 T6.1 RED stub - implement in T6.3 GREEN")
    }

    suspend fun reportAlarm(payload: com.uvp.sim.gb28181.AlarmPayload) {
        throw NotImplementedError("PR6 T6.1 RED stub - implement in T6.3 GREEN")
    }

    suspend fun localResetAlarm() {
        throw NotImplementedError("PR6 T6.1 RED stub - implement in T6.3 GREEN")
    }

    suspend fun triggerMediaStatusAbnormal(notifyType: Int) {
        throw NotImplementedError("PR6 T6.1 RED stub - implement in T6.3 GREEN")
    }

    suspend fun stopStream(reason: String = "user stop") {
        throw NotImplementedError("PR6 T6.1 RED stub - implement in T6.3 GREEN")
    }

    suspend fun stopBroadcast(reason: com.uvp.sim.domain.BroadcastEndReason = com.uvp.sim.domain.BroadcastEndReason.Local) {
        throw NotImplementedError("PR6 T6.1 RED stub - implement in T6.3 GREEN")
    }

    fun setBroadcastSpeaker(on: Boolean) {
        _broadcastSpeakerOn.value = on
    }

    suspend fun updateCatalogTree(tree: List<com.uvp.sim.config.CatalogNode>) {
        throw NotImplementedError("PR6 T6.1 RED stub - implement in T6.3 GREEN")
    }

    suspend fun pushCatalogNotify() {
        throw NotImplementedError("PR6 T6.1 RED stub - implement in T6.3 GREEN")
    }

    suspend fun pushCatalogIncremental(events: List<com.uvp.sim.config.CatalogChangeEvent>) {
        throw NotImplementedError("PR6 T6.1 RED stub - implement in T6.3 GREEN")
    }

    suspend fun toggleChannelStatus(channelId: String, online: Boolean) {
        throw NotImplementedError("PR6 T6.1 RED stub - implement in T6.3 GREEN")
    }

    fun consumeEffect() {
        // no-op stub
    }

    fun updatePoseFromRender(pan: Float, tilt: Float, zoom: Float) {
        // no-op stub
    }
}
