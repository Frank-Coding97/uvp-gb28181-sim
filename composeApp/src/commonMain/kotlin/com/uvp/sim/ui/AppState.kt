package com.uvp.sim.ui

import com.uvp.sim.config.SimConfig
import com.uvp.sim.domain.SimEvent
import com.uvp.sim.sip.SipState

/**
 * Read-only view of the simulator state for the UI layer.
 *
 * The platform-specific shell (Android MainActivity, iOS UIViewController)
 * collects [SimulatorEngine.state] / [SimulatorEngine.events] / its config
 * and bundles them into this snapshot for Compose to render.
 */
data class AppUiState(
    val sip: SipState,
    val config: SimConfig,
    val events: List<SimEvent>
)

/**
 * Actions the UI can request. The platform shell binds these to the engine
 * + ViewModel; commonMain stays platform-free.
 */
interface AppActions {
    fun onConnect()
    fun onDisconnect()
    fun onSnapshot()
    fun onConfigSave(updated: SimConfig)
}

enum class AppTab(val label: String) {
    Home("主页"),
    Channel("通道"),
    Log("日志");
}
