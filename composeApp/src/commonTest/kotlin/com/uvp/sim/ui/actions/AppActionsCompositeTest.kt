package com.uvp.sim.ui.actions

import com.uvp.sim.config.CatalogNode
import com.uvp.sim.config.NetworkPreference
import com.uvp.sim.config.SimConfig
import com.uvp.sim.gb28181.AlarmPayload
import com.uvp.sim.recording.RecordingFilter
import com.uvp.sim.ui.AlarmFireMode
import com.uvp.sim.ui.AppActions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * PR-B T3 — AppActions composite marker + 4 slice 委派路由单测。
 *
 * 验证目标:
 *   1. `AppActions` 实际是 composite marker —— 实现 4 slice 即可 satisfy AppActions
 *   2. Kotlin `by` 接口委派把方法路由到对应 slice impl
 *
 * 测试不依赖 Android / ViewModel,纯接口路由验证。
 */
class AppActionsCompositeTest {

    @Test
    fun composite_routes_to_home_slice() {
        val sink = RoutingSink()
        val home = HomeSliceFake(sink)
        val composite = makeComposite(home = home)

        composite.onConnect()
        composite.onCancelConnect()
        composite.onDisconnect()
        composite.onClearSipLogs()
        composite.onClearSystemLogs()
        composite.onConsumeDeviceEffect()

        assertEquals(
            listOf("home.onConnect", "home.onCancelConnect", "home.onDisconnect",
                   "home.onClearSipLogs", "home.onClearSystemLogs", "home.onConsumeDeviceEffect"),
            sink.calls
        )
    }

    @Test
    fun composite_routes_to_capability_slice() {
        val sink = RoutingSink()
        val capability = CapabilitySliceFake(sink)
        val composite = makeComposite(capability = capability)

        composite.onSnapshot()
        composite.onAlarmReset()
        composite.onAlarmFireDefault()
        composite.onBroadcastStop()
        composite.onBroadcastToggleSpeaker(true)
        composite.onToggleChannelStatus("ch-1", false)
        composite.onSimulateMediaStatusAbnormal(122)
        composite.onPoseTick(1f, 2f, 3f)

        assertEquals(
            listOf("cap.onSnapshot", "cap.onAlarmReset", "cap.onAlarmFireDefault",
                   "cap.onBroadcastStop", "cap.onBroadcastToggleSpeaker:true",
                   "cap.onToggleChannelStatus:ch-1:false",
                   "cap.onSimulateMediaStatusAbnormal:122",
                   "cap.onPoseTick:1.0:2.0:3.0"),
            sink.calls
        )
    }

    @Test
    fun composite_routes_to_recording_slice() {
        val sink = RoutingSink()
        val recording = RecordingSliceFake(sink)
        val composite = makeComposite(recording = recording)

        composite.onRecordingStart()
        composite.onRecordingStop()
        composite.onRecordingDelete("rec-1")

        assertEquals(
            listOf("rec.onRecordingStart", "rec.onRecordingStop", "rec.onRecordingDelete:rec-1"),
            sink.calls
        )
    }

    @Test
    fun composite_routes_to_network_slice() {
        val sink = RoutingSink()
        val network = NetworkSliceFake(sink)
        val composite = makeComposite(network = network)

        composite.onNetworkPreferenceChange(NetworkPreference.WIFI)
        composite.onNetworkPreferenceChange(NetworkPreference.CELLULAR)

        assertEquals(
            listOf("net.onNetworkPreferenceChange:WIFI", "net.onNetworkPreferenceChange:CELLULAR"),
            sink.calls
        )
    }

    /** AppActions 类型上可调用所有 4 slice 的方法(编译期验证 composite marker 正确)。 */
    @Test
    fun app_actions_type_can_invoke_all_slice_methods() {
        val sink = RoutingSink()
        val composite: AppActions = makeComposite(
            home = HomeSliceFake(sink),
            capability = CapabilitySliceFake(sink),
            recording = RecordingSliceFake(sink),
            network = NetworkSliceFake(sink),
        )

        composite.onConnect()
        composite.onSnapshot()
        composite.onRecordingStart()
        composite.onNetworkPreferenceChange(NetworkPreference.AUTO)

        // 4 slice 各贡献 1 条
        assertEquals(4, sink.calls.size)
        assertTrue(sink.calls.any { it.startsWith("home.") })
        assertTrue(sink.calls.any { it.startsWith("cap.") })
        assertTrue(sink.calls.any { it.startsWith("rec.") })
        assertTrue(sink.calls.any { it.startsWith("net.") })
    }
}

// ---- helpers ----

private class RoutingSink {
    val calls = mutableListOf<String>()
    fun record(name: String) { calls += name }
}

private fun makeComposite(
    home: HomeActions = HomeSliceFake(RoutingSink()),
    capability: CapabilityActions = CapabilitySliceFake(RoutingSink()),
    recording: RecordingActions = RecordingSliceFake(RoutingSink()),
    network: NetworkActions = NetworkSliceFake(RoutingSink()),
): AppActions = object : AppActions,
    HomeActions by home,
    CapabilityActions by capability,
    RecordingActions by recording,
    NetworkActions by network {}

private class HomeSliceFake(private val sink: RoutingSink) : HomeActions {
    override fun onConnect() = sink.record("home.onConnect")
    override fun onCancelConnect() = sink.record("home.onCancelConnect")
    override fun onDisconnect() = sink.record("home.onDisconnect")
    override fun onConfigSave(updated: SimConfig) = sink.record("home.onConfigSave")
    override fun onClearSipLogs() = sink.record("home.onClearSipLogs")
    override fun onClearSystemLogs() = sink.record("home.onClearSystemLogs")
    override fun onConsumeDeviceEffect() = sink.record("home.onConsumeDeviceEffect")
}

private class CapabilitySliceFake(private val sink: RoutingSink) : CapabilityActions {
    override fun onSnapshot() = sink.record("cap.onSnapshot")
    override fun onAlarmFire(payload: AlarmPayload) = sink.record("cap.onAlarmFire")
    override fun onAlarmReset() = sink.record("cap.onAlarmReset")
    override fun onAlarmFireDefault() = sink.record("cap.onAlarmFireDefault")
    override fun onSetAlarmFireMode(mode: AlarmFireMode) = sink.record("cap.onSetAlarmFireMode:$mode")
    override fun onSaveFixedAlarm(payload: AlarmPayload) = sink.record("cap.onSaveFixedAlarm")
    override fun onSimulateMediaStatusAbnormal(notifyType: Int) =
        sink.record("cap.onSimulateMediaStatusAbnormal:$notifyType")
    override fun onBroadcastStop() = sink.record("cap.onBroadcastStop")
    override fun onBroadcastToggleSpeaker(on: Boolean) = sink.record("cap.onBroadcastToggleSpeaker:$on")
    override fun onCatalogTreeSave(tree: List<CatalogNode>): String? {
        sink.record("cap.onCatalogTreeSave:${tree.size}")
        return null
    }
    override fun onToggleChannelStatus(channelId: String, online: Boolean) =
        sink.record("cap.onToggleChannelStatus:$channelId:$online")
    override fun onPoseTick(pan: Float, tilt: Float, zoom: Float) =
        sink.record("cap.onPoseTick:$pan:$tilt:$zoom")
}

private class RecordingSliceFake(private val sink: RoutingSink) : RecordingActions {
    override fun onRecordingStart() = sink.record("rec.onRecordingStart")
    override fun onRecordingStop() = sink.record("rec.onRecordingStop")
    override fun onRecordingDelete(id: String) = sink.record("rec.onRecordingDelete:$id")
    override fun onRecordingFilterApply(filter: RecordingFilter) =
        sink.record("rec.onRecordingFilterApply")
}

private class NetworkSliceFake(private val sink: RoutingSink) : NetworkActions {
    override fun onNetworkPreferenceChange(preference: NetworkPreference) =
        sink.record("net.onNetworkPreferenceChange:${preference.name}")
}
