package com.uvp.sim.domain

import com.uvp.sim.config.DeviceConfig
import com.uvp.sim.config.ServerConfig
import com.uvp.sim.config.SimConfig
import com.uvp.sim.gb28181.PanDirection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertSame

/**
 * 14 个 case 覆盖 GB28181 §F.3 DeviceControl 全部 10 项子命令(plan §2.1.2).
 */
class DeviceControlDispatcherTest {

    private val config = SimConfig(
        server = ServerConfig(ip = "127.0.0.1", serverId = "34020000002000000001", domain = "3402000000"),
        device = DeviceConfig(
            deviceId = "34020000001320000001",
            videoChannelId = "34020000001310000001",
            alarmChannelId = "34020000001340000001",
            username = "34020000001320000001",
            password = "12345678"
        )
    )

    private fun newState() = MutableStateFlow(DeviceControlState())

    private class FakeEngineActions : DeviceControlActions {
        var rebootCalled = 0
        var snapshotCalled = 0
        var keyFrameCalled = 0
        override suspend fun reboot() { rebootCalled++ }
        override suspend fun snapshot() { snapshotCalled++ }
        override fun requestKeyFrame() { keyFrameCalled++ }
    }

    /** ((B0+B1+B2+B3+B4+B5+B6) mod 256) hex string */
    private fun ptzHex(opCode: Int, pan: Int = 0, tilt: Int = 0, zoom: Int = 0): String {
        val b6 = (zoom and 0x0F) shl 4
        val sum = (0xA5 + 0x0F + 0x01 + opCode + pan + tilt + b6) and 0xFF
        return listOf(0xA5, 0x0F, 0x01, opCode, pan, tilt, b6, sum)
            .joinToString("") { it.toString(16).padStart(2, '0').uppercase() }
    }

    private fun newDispatcher(
        state: MutableStateFlow<DeviceControlState> = newState(),
        actions: DeviceControlActions = FakeEngineActions(),
        scope: CoroutineScope? = null
    ) = DeviceControlDispatcher(state, config, actions, scope)

    @Test
    fun `case 1 — PTZCmd 左转 panSpeed 为负`() {
        val state = newState()
        val d = newDispatcher(state)
        d.dispatch("<Control><CmdType>DeviceControl</CmdType><PTZCmd>${ptzHex(0x02, pan = 50)}</PTZCmd></Control>")
        assertTrue(state.value.panSpeed < 0f)
        assertEquals(0f, state.value.tiltSpeed)
        assertEquals("PTZCmd", state.value.lastCommand?.type)
        assertEquals(PanDirection.LEFT, state.value.lastCommand?.ptz?.panDirection)
    }

    @Test
    fun `case 2 — PTZCmd 全零 → 停止`() {
        val state = newState()
        // 先打个左转
        val d = newDispatcher(state)
        d.dispatch("<C><PTZCmd>${ptzHex(0x02, pan = 50)}</PTZCmd></C>")
        assertTrue(state.value.panSpeed < 0f)
        // 再发停止
        d.dispatch("<C><PTZCmd>${ptzHex(0x00)}</PTZCmd></C>")
        assertEquals(0f, state.value.panSpeed)
        assertEquals(0f, state.value.tiltSpeed)
        assertEquals(0f, state.value.zoomSpeed)
    }

    @Test
    fun `case 3 — IFameCmd 触发 IFrameFlash + camera-requestKeyFrame`() {
        val state = newState()
        val actions = FakeEngineActions()
        val d = newDispatcher(state, actions)
        d.dispatch("<C><IFameCmd>Send</IFameCmd></C>")
        assertEquals(DeviceEffect.IFrameFlash, state.value.pendingEffect)
        assertEquals(1, actions.keyFrameCalled)
    }

    @Test
    fun `case 4 — TeleBoot 触发 Reboot effect + engine-reboot`() = runTest {
        val state = newState()
        val actions = FakeEngineActions()
        val d = newDispatcher(state, actions, this)
        d.dispatch("<C><TeleBoot>Boot</TeleBoot></C>")
        testScheduler.advanceUntilIdle()
        assertEquals(DeviceEffect.Reboot, state.value.pendingEffect)
        assertTrue(state.value.isRebooting)
        assertEquals(1, actions.rebootCalled)
    }

    @Test
    fun `case 5 — RecordCmd Record 切到录像`() {
        val state = newState()
        val d = newDispatcher(state)
        d.dispatch("<C><RecordCmd>Record</RecordCmd></C>")
        assertTrue(state.value.isRecording)
    }

    @Test
    fun `case 6 — RecordCmd StopRecord 关录像`() {
        val state = MutableStateFlow(DeviceControlState(isRecording = true))
        val d = newDispatcher(state)
        d.dispatch("<C><RecordCmd>StopRecord</RecordCmd></C>")
        assertFalse(state.value.isRecording)
    }

    @Test
    fun `case 7 — GuardCmd SetGuard`() {
        val state = newState()
        val d = newDispatcher(state)
        d.dispatch("<C><GuardCmd>SetGuard</GuardCmd></C>")
        assertTrue(state.value.isGuarded)
    }

    @Test
    fun `case 8 — GuardCmd ResetGuard`() {
        val state = MutableStateFlow(DeviceControlState(isGuarded = true))
        val d = newDispatcher(state)
        d.dispatch("<C><GuardCmd>ResetGuard</GuardCmd></C>")
        assertFalse(state.value.isGuarded)
    }

    @Test
    fun `case 9 — AlarmCmd ResetAlarm`() {
        val state = MutableStateFlow(DeviceControlState(isAlarming = true))
        val d = newDispatcher(state)
        d.dispatch("<C><AlarmCmd>ResetAlarm</AlarmCmd></C>")
        assertFalse(state.value.isAlarming)
    }

    @Test
    fun `case 10 — DragZoomIn 解析 4 元组矩形`() {
        val state = newState()
        val d = newDispatcher(state)
        val xml = "<C><DragZoomIn><Length>100</Length><Width>80</Width>" +
            "<MidPointX>320</MidPointX><MidPointY>240</MidPointY>" +
            "<LengthX>120</LengthX><LengthY>90</LengthY></DragZoomIn></C>"
        d.dispatch(xml)
        val r = state.value.dragZoomRect
        assertNotNull(r)
        assertEquals(320, r!!.midX)
        assertEquals(240, r.midY)
        assertEquals(120, r.lengthX)
        assertEquals(90, r.lengthY)
    }

    @Test
    fun `case 11 — HomePosition 首次 → 存入当前 pose`() {
        val state = MutableStateFlow(
            DeviceControlState(panAngle = 30f, tiltAngle = -10f, zoomLevel = 2f)
        )
        val d = newDispatcher(state)
        d.dispatch("<C><HomePosition><Enabled>1</Enabled><PresetIndex>1</PresetIndex></HomePosition></C>")
        val saved = state.value.presets[1]
        assertNotNull(saved)
        assertEquals(30f, saved!!.pan)
        assertEquals(-10f, saved.tilt)
        assertEquals(2f, saved.zoom)
    }

    @Test
    fun `case 12 — HomePosition 已存在 → 触发回归 effect`() {
        val target = PtzPose(pan = 45f, tilt = 0f, zoom = 1f)
        val state = MutableStateFlow(DeviceControlState(presets = mapOf(1 to target)))
        val d = newDispatcher(state)
        d.dispatch("<C><HomePosition><Enabled>1</Enabled><PresetIndex>1</PresetIndex></HomePosition></C>")
        val eff = state.value.pendingEffect
        assertTrue(eff is DeviceEffect.HomePositionReturn)
        assertEquals(target, (eff as DeviceEffect.HomePositionReturn).targetPose)
    }

    @Test
    fun `case 13 — DeviceConfig BasicParam → ConfigChanged effect`() {
        val state = newState()
        val d = newDispatcher(state)
        val xml = "<C><BasicParam><Name>NewCam</Name>" +
            "<HeartBeatInterval>30</HeartBeatInterval></BasicParam></C>"
        d.dispatch(xml)
        val eff = state.value.pendingEffect
        assertTrue(eff is DeviceEffect.ConfigChanged)
        val ch = (eff as DeviceEffect.ConfigChanged).changedFields
        assertTrue("Name" in ch)
        assertTrue("HeartBeatInterval" in ch)
    }

    @Test
    fun `case 14 — 空 XML 不崩 + state 不变`() {
        val state = newState()
        val before = state.value
        val d = newDispatcher(state)
        d.dispatch("")
        d.dispatch("<C></C>")
        d.dispatch("<C><Unknown>X</Unknown></C>")
        // 只要没异常 + state 没变就算过
        assertSame(before, state.value)
    }

    @Test
    fun `case 15 — SnapshotCmd 触发 engine-snapshot + SnapshotFlash effect`() = runTest {
        val state = newState()
        val actions = FakeEngineActions()
        val d = newDispatcher(state, actions, this)
        d.dispatch("<C><SnapShotCmd>1</SnapShotCmd></C>")
        testScheduler.advanceUntilIdle()
        assertEquals(1, actions.snapshotCalled)
        assertEquals(DeviceEffect.SnapshotFlash, state.value.pendingEffect)
    }
}
