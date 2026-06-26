package com.uvp.sim.ui.actions

import com.uvp.sim.config.CatalogNode
import com.uvp.sim.config.NetworkPreference
import com.uvp.sim.config.SimConfig
import com.uvp.sim.gb28181.AlarmPayload
import com.uvp.sim.recording.RecordingFilter
import com.uvp.sim.ui.AlarmFireMode
import com.uvp.sim.ui.AppActions
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * PR-TEST-1 T2 — MainActivity 4 slice impl 绑定到 AppActions composite 的路由测试。
 *
 * 验证目标(对应 PR-B AppActions 拆切口):
 *   1. MainActivity.onCreate 里那段
 *        object : AppActions, HomeActions by homeActions, CapabilityActions by capabilityActions,
 *                 RecordingActions by recordingActions, NetworkActions by networkActions {}
 *      composite 在 Android JVM 类路径下能正确编译 + 实例化
 *   2. 调用任何 slice 的方法都路由到对应 fake impl(不串路由)
 *   3. composite 作为 AppActions 类型可调用所有方法(slice 越界编译期立刻挂掉)
 *
 * 不启 MainActivity / ViewModel —— Camera/Recording 硬件 mock 成本高于收益。
 * 这层测试纯接口路由验证,跑在 JVM + Robolectric SDK shadow 下,~50ms。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MainActivityActionsBindingTest {

    @Test
    fun composite_routes_home_slice_calls() {
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
            listOf(
                "home.onConnect", "home.onCancelConnect", "home.onDisconnect",
                "home.onClearSipLogs", "home.onClearSystemLogs", "home.onConsumeDeviceEffect"
            ),
            sink.calls
        )
    }

    @Test
    fun composite_routes_capability_slice_calls() {
        val sink = RoutingSink()
        val composite = makeComposite(capability = CapabilitySliceFake(sink))

        composite.onSnapshot()
        composite.onAlarmReset()
        composite.onAlarmFireDefault()
        composite.onBroadcastStop()
        composite.onSimulateMediaStatusAbnormal(122)

        assertEquals(
            listOf(
                "cap.onSnapshot", "cap.onAlarmReset", "cap.onAlarmFireDefault",
                "cap.onBroadcastStop", "cap.onSimulateMediaStatusAbnormal:122"
            ),
            sink.calls
        )
    }

    @Test
    fun composite_routes_recording_slice_calls() {
        val sink = RoutingSink()
        val composite = makeComposite(recording = RecordingSliceFake(sink))

        composite.onRecordingStart()
        composite.onRecordingStop()
        composite.onRecordingDelete("rec-1")

        assertEquals(
            listOf("rec.onRecordingStart", "rec.onRecordingStop", "rec.onRecordingDelete:rec-1"),
            sink.calls
        )
    }

    @Test
    fun composite_routes_network_slice_calls() {
        val sink = RoutingSink()
        val composite = makeComposite(network = NetworkSliceFake(sink))

        composite.onNetworkPreferenceChange(NetworkPreference.WIFI)
        composite.onNetworkPreferenceChange(NetworkPreference.CELLULAR)
        composite.onNetworkPreferenceChange(NetworkPreference.AUTO)

        assertEquals(
            listOf(
                "net.onNetworkPreferenceChange:WIFI",
                "net.onNetworkPreferenceChange:CELLULAR",
                "net.onNetworkPreferenceChange:AUTO"
            ),
            sink.calls
        )
    }

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

        assertEquals(4, sink.calls.size)
        assertTrue(sink.calls.any { it.startsWith("home.") })
        assertTrue(sink.calls.any { it.startsWith("cap.") })
        assertTrue(sink.calls.any { it.startsWith("rec.") })
        assertTrue(sink.calls.any { it.startsWith("net.") })
    }
}

// ---- helpers (与 composeApp 的 AppActionsCompositeTest 同结构,本类独立装配验证) ----

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
    override fun onRecordingFilterApply(filter: RecordingFilter) = sink.record("rec.onRecordingFilterApply")
}

private class NetworkSliceFake(private val sink: RoutingSink) : NetworkActions {
    override fun onNetworkPreferenceChange(preference: NetworkPreference) =
        sink.record("net.onNetworkPreferenceChange:${preference.name}")
}
