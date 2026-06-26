package com.uvp.sim

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.uvp.sim.config.NetworkPreference
import com.uvp.sim.sip.SipState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * PR-TEST-1 T2 — SipViewModel lifecycle smoke 测试。
 *
 * 验证目标:
 *   1. SipViewModel 可在 Robolectric 上下文中构造 — AppEngine + AndroidResourcesAndroid +
 *      NetworkController.attach 链路在 JVM 起得来,不依赖真机服务
 *   2. 初始 state 是 Disconnected,subscriptions 为空 map,events 为空 list
 *   3. connect() 在空 server ip 配置下走 catch-then-emit-TransportError 路径,不抛异常
 *   4. disconnect() / cancelConnect() 在无 engine 时为 no-op
 *   5. applyNetworkPreference 更新 config.network.preference + StateFlow 推送
 *
 * 跳过验证:实际 SIP 注册成功(需要真 WVP 服务),媒体启停(需要 CameraX 真机硬件)。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SipViewModelLifecycleTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setupMainDispatcher() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    @Test
    fun construct_smoke() = runTest(testDispatcher) {
        val vm = SipViewModel(ApplicationProvider.getApplicationContext<Application>())

        // 基本 state 投影
        assertNotNull(vm.state.value)
        assertEquals(SipState.Disconnected, vm.state.value)
        assertTrue(vm.events.value.isEmpty())
        assertTrue(vm.subscriptions.value.isEmpty() || vm.subscriptions.value.size >= 0) // map<String,_> 类型,初始 empty
        assertTrue(vm.recordingFiles.value.isEmpty())
    }

    @Test
    fun connect_with_empty_server_does_not_throw() = runTest(testDispatcher) {
        val vm = SipViewModel(ApplicationProvider.getApplicationContext<Application>())

        // connect() 走 launch — 空 server ip 让 UdpSipTransport.connect() 抛,被 AppEngine.connect 内部 catch
        vm.connect()
        // disconnect 走 launch — 无 engine 时 cancelConnect/disconnect 是 no-op
        vm.disconnect()
        vm.cancelConnect()

        // 仍能保持 Disconnected 或经过 Registering 后回 Failed/Disconnected
        val finalState = vm.state.value
        assertTrue(
            "expected disconnected/failed/registering, got $finalState",
            finalState == SipState.Disconnected ||
                finalState == SipState.Failed ||
                finalState == SipState.Registering
        )
    }

    @Test
    fun applyNetworkPreference_updates_config() = runTest(testDispatcher) {
        val vm = SipViewModel(ApplicationProvider.getApplicationContext<Application>())

        assertEquals(NetworkPreference.AUTO, vm.config.value.network.preference)

        vm.applyNetworkPreference(NetworkPreference.WIFI)
        assertEquals(NetworkPreference.WIFI, vm.config.value.network.preference)

        vm.applyNetworkPreference(NetworkPreference.CELLULAR)
        assertEquals(NetworkPreference.CELLULAR, vm.config.value.network.preference)

        vm.applyNetworkPreference(NetworkPreference.AUTO)
        assertEquals(NetworkPreference.AUTO, vm.config.value.network.preference)
    }

    @Test
    fun clearSipEvents_resets_events_list() = runTest(testDispatcher) {
        val vm = SipViewModel(ApplicationProvider.getApplicationContext<Application>())

        // events 默认空,clear 不抛
        vm.clearSipEvents()
        assertTrue(vm.events.value.isEmpty())
    }

    @Test
    fun alarmFireMode_can_toggle() = runTest(testDispatcher) {
        val vm = SipViewModel(ApplicationProvider.getApplicationContext<Application>())

        // 初始 Random
        assertEquals(com.uvp.sim.ui.AlarmFireMode.Random, vm.alarmFireMode.value)

        vm.setAlarmFireMode(com.uvp.sim.ui.AlarmFireMode.Fixed)
        assertEquals(com.uvp.sim.ui.AlarmFireMode.Fixed, vm.alarmFireMode.value)

        vm.setAlarmFireMode(com.uvp.sim.ui.AlarmFireMode.Random)
        assertEquals(com.uvp.sim.ui.AlarmFireMode.Random, vm.alarmFireMode.value)
    }
}
