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
import kotlin.test.assertNull
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
            password = "test-password"
        )
    )

    private fun newState() = MutableStateFlow(DeviceControlModel())

    private class FakeEngineActions : DeviceControlActions {
        var rebootCalled = 0
        var snapshotCalled = 0
        var keyFrameCalled = 0
        val snapshotConfigsTriggered = mutableListOf<com.uvp.sim.gb28181.SnapShotConfig>()
        val upgradesStarted = mutableListOf<Triple<String, String, String>>()
        override suspend fun reboot() { rebootCalled++ }
        override suspend fun snapshot() { snapshotCalled++ }
        override fun requestKeyFrame() { keyFrameCalled++ }
        override suspend fun triggerSnapshotConfig(cfg: com.uvp.sim.gb28181.SnapShotConfig) {
            snapshotConfigsTriggered.add(cfg)
        }
        override fun startUpgrade(sessionId: String, firmware: String, fileUrl: String) {
            upgradesStarted.add(Triple(sessionId, firmware, fileUrl))
        }
    }

    /** ((B0+B1+B2+B3+B4+B5+B6) mod 256) hex string */
    private fun ptzHex(opCode: Int, pan: Int = 0, tilt: Int = 0, zoom: Int = 0): String {
        val b6 = (zoom and 0x0F) shl 4
        val sum = (0xA5 + 0x0F + 0x01 + opCode + pan + tilt + b6) and 0xFF
        return listOf(0xA5, 0x0F, 0x01, opCode, pan, tilt, b6, sum)
            .joinToString("") { it.toString(16).padStart(2, '0').uppercase() }
    }

    private fun newDispatcher(
        state: MutableStateFlow<DeviceControlModel> = newState(),
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
        val state = MutableStateFlow(DeviceControlModel(isRecording = true))
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
        val state = MutableStateFlow(DeviceControlModel(isGuarded = true))
        val d = newDispatcher(state)
        d.dispatch("<C><GuardCmd>ResetGuard</GuardCmd></C>")
        assertFalse(state.value.isGuarded)
    }

    @Test
    fun `case 9 — AlarmCmd ResetAlarm`() {
        val state = MutableStateFlow(DeviceControlModel(isAlarming = true))
        val d = newDispatcher(state)
        d.dispatch("<C><AlarmCmd>ResetAlarm</AlarmCmd></C>")
        assertFalse(state.value.isAlarming)
    }

    @Test
    fun `case 9a — AlarmCmd 0 复位 → alarmReset ack + isAlarming false`() {
        val state = MutableStateFlow(DeviceControlModel(isAlarming = true))
        val d = newDispatcher(state)
        val ack = d.dispatch("<C><AlarmCmd>0</AlarmCmd></C>", fromUri = "sip:plat@host")
        assertTrue(ack.alarmReset)
        assertTrue(ack.needSipResponse)
        assertEquals("sip:plat@host", ack.by)
        assertFalse(state.value.isAlarming)
    }

    @Test
    fun `case 9b — AlarmCmd 1 布防 → 不切 isAlarming 不 reset`() {
        val state = MutableStateFlow(DeviceControlModel(isAlarming = true))
        val d = newDispatcher(state)
        val ack = d.dispatch("<C><AlarmCmd>1</AlarmCmd></C>")
        assertFalse(ack.alarmReset)
        assertTrue(ack.needSipResponse)
        // isAlarming 不变(仍为 true)
        assertTrue(state.value.isAlarming)
    }

    @Test
    fun `case 9c — AlarmCmd 2 撤防 → alarmReset + isAlarming false`() {
        val state = MutableStateFlow(DeviceControlModel(isAlarming = true))
        val d = newDispatcher(state)
        val ack = d.dispatch("<C><AlarmCmd>2</AlarmCmd></C>")
        assertTrue(ack.alarmReset)
        assertFalse(state.value.isAlarming)
    }

    @Test
    fun `case 9d — AlarmCmd 99 未知值 → 回 200 不 reset 不切 isAlarming`() {
        val state = MutableStateFlow(DeviceControlModel(isAlarming = true))
        val d = newDispatcher(state)
        val ack = d.dispatch("<C><AlarmCmd>99</AlarmCmd></C>")
        assertTrue(ack.needSipResponse)
        assertFalse(ack.alarmReset)
        assertTrue(state.value.isAlarming)
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
    fun `case 11 — HomePosition 首次(预置位不存在) → 只落配置, 不凭空造预置位`() {
        // 标准语义:HomePosition 是**配置**命令(Enabled / ResetTime / PresetIndex),
        // 不是"现在就回位"。平台下发一个设备上还不存在的预置位号时,真实设备不会因此
        // 新建一个预置位 —— 所以这里只应落配置三件套,presets 保持为空。
        // 2026-09-16 修正:原实现会造预置位 + 立即归位(把配置命令当成了调用命令)。
        val state = MutableStateFlow(
            DeviceControlModel(panAngle = 30f, tiltAngle = -10f, zoomLevel = 2f)
        )
        val d = newDispatcher(state)
        d.dispatch(
            "<C><HomePosition><Enabled>1</Enabled><ResetTime>30</ResetTime>" +
                "<PresetIndex>1</PresetIndex></HomePosition></C>"
        )
        assertTrue(state.value.homePositionEnabled)
        assertEquals(1, state.value.homePositionPresetIndex)
        assertEquals(30, state.value.homePositionResetTime)
        assertEquals(emptyMap(), state.value.presets)     // 不凭空造
        assertNull(state.value.pendingEffect)             // 配置命令不产生"归位"动作
    }

    @Test
    fun `case 12 — HomePosition 已存在 → 落配置并指向该预置位(不立即归位)`() {
        val target = PtzPose(pan = 45f, tilt = 0f, zoom = 1f)
        val state = MutableStateFlow(DeviceControlModel(presets = mapOf(1 to target)))
        val d = newDispatcher(state)
        d.dispatch("<C><HomePosition><Enabled>1</Enabled><PresetIndex>1</PresetIndex></HomePosition></C>")
        assertEquals(1, state.value.homePositionPresetIndex)
        assertEquals(target, state.value.homePosition)    // 坐标快照指向该预置位
        assertNull(state.value.pendingEffect)             // 标准里"启用看守位" ≠ "立刻调用"
    }

    @Test
    fun `case 12b — HomePosition Enabled=0 → 关闭看守位, 配置项保留`() {
        val state = MutableStateFlow(
            DeviceControlModel(homePositionPresetIndex = 3, homePositionResetTime = 60)
        )
        val d = newDispatcher(state)
        d.dispatch("<C><HomePosition><Enabled>0</Enabled></HomePosition></C>")
        assertFalse(state.value.homePositionEnabled)
        // 只关开关:平台没下发的字段(指向/归位时间)应保留上一次的值
        assertEquals(3, state.value.homePositionPresetIndex)
        assertEquals(60, state.value.homePositionResetTime)
    }

    @Test
    fun `case 12c — HomePosition 越界字段在入口被钳到可表示区间`() {
        // PresetIndex / ResetTime 会被 HomePositionQuery 的应答原样回传,而平台侧解析器
        // 对 PresetIndex > 255 / ResetTime < 0 直接判协议非法。不钳的话,一条手造的越界
        // 报文会让这台设备**永久**只能回非法应答,且现象指向查询而不是当初那条控制命令。
        val state = MutableStateFlow(DeviceControlModel())
        val d = newDispatcher(state)
        d.dispatch(
            "<C><HomePosition><Enabled>1</Enabled><ResetTime>-5</ResetTime>" +
                "<PresetIndex>300</PresetIndex></HomePosition></C>"
        )
        assertEquals(0, state.value.homePositionResetTime)
        assertEquals(255, state.value.homePositionPresetIndex)
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

    // T9 — SnapShotConfig (GB-2022 §9.5 7.5 新路径)

    @Test
    fun `T9_1 — SnapShotConfig 完整解析 → triggerSnapshotConfig`() = runTest {
        val state = newState()
        val actions = FakeEngineActions()
        val d = newDispatcher(state, actions, this)
        val xml = "<Control><CmdType>DeviceControl</CmdType>" +
            "<SnapShotConfig>" +
            "<SessionID>S001</SessionID>" +
            "<UploadURL>http://192.168.1.10:8088/snap/</UploadURL>" +
            "<SnapNum>3</SnapNum>" +
            "<Interval>2</Interval>" +
            "</SnapShotConfig></Control>"
        val ack = d.dispatch(xml)
        testScheduler.advanceUntilIdle()
        assertTrue(ack.needSipResponse, "must respond 200 OK")
        assertEquals(1, actions.snapshotConfigsTriggered.size)
        val cfg = actions.snapshotConfigsTriggered.first()
        assertEquals("S001", cfg.sessionId)
        assertEquals(3, cfg.snapNum)
        assertEquals(2000L, cfg.intervalMs)
        // 7.4 旧路径不能被同时激活
        assertEquals(0, actions.snapshotCalled)
    }

    @Test
    fun `T9_2 — SnapShotConfig 缺字段 → 不调 actions 但仍回 200`() = runTest {
        val state = newState()
        val actions = FakeEngineActions()
        val d = newDispatcher(state, actions, this)
        val xml = "<C><SnapShotConfig>" +
            "<UploadURL>http://h:8088/snap/</UploadURL>" +
            "</SnapShotConfig></C>"
        val ack = d.dispatch(xml)
        testScheduler.advanceUntilIdle()
        assertTrue(ack.needSipResponse, "always 200 OK to avoid platform retry")
        assertEquals(0, actions.snapshotConfigsTriggered.size)
    }

    @Test
    fun `T9_3 — SnapShotCmd 旧路径仍走 7_4 reportSnapshot`() = runTest {
        val state = newState()
        val actions = FakeEngineActions()
        val d = newDispatcher(state, actions, this)
        d.dispatch("<C><SnapShotCmd>1</SnapShotCmd></C>")
        testScheduler.advanceUntilIdle()
        assertEquals(1, actions.snapshotCalled, "旧路径走 actions.snapshot")
        assertEquals(0, actions.snapshotConfigsTriggered.size, "新路径不触发")
    }

    @Test
    fun `T9_4 — SnapShotConfig 与 SnapShotCmd 同存优先 SnapShotConfig`() = runTest {
        val state = newState()
        val actions = FakeEngineActions()
        val d = newDispatcher(state, actions, this)
        val xml = "<C>" +
            "<SnapShotConfig>" +
            "<SessionID>S</SessionID>" +
            "<UploadURL>http://h:8088/snap/</UploadURL>" +
            "</SnapShotConfig>" +
            "<SnapShotCmd>1</SnapShotCmd>" +
            "</C>"
        d.dispatch(xml)
        testScheduler.advanceUntilIdle()
        assertEquals(1, actions.snapshotConfigsTriggered.size, "新路径优先")
        assertEquals(0, actions.snapshotCalled, "旧路径不应再被触发")
    }

    // ---------- T3 预置位 CRUD ----------

    /**
     * 预置位 hex helper — 字节4 = 0x81/0x82/0x83,**字节5 固定 0x00、字节6 = 编号**。
     *
     * ⛔ 编号**不在**字节5。预置位是整字节三族里唯一把参数放数据2的
     * (巡航/扫描族的组号才在字节5)。2026-09-16 真机联调时这里与解码器一起错了,
     * 导致互相印证不出问题 —— 所以另外在 `PtzCmdDecoderTest` 里加了一条**硬编码
     * 平台真实报文**的锚点用例,不再依赖这个 helper。
     */
    private fun presetHex(opCode: Int, presetIndex: Int): String {
        val sum = (0xA5 + 0x0F + 0x01 + opCode + 0 + presetIndex + 0) and 0xFF
        return listOf(0xA5, 0x0F, 0x01, opCode, 0, presetIndex, 0, sum)
            .joinToString("") { it.toString(16).padStart(2, '0').uppercase() }
    }

    @Test
    fun `T3_1 — SetPreset 把当前 pose 入库`() {
        val state = MutableStateFlow(
            DeviceControlModel(panAngle = 10f, tiltAngle = 20f, zoomLevel = 1.5f)
        )
        val d = newDispatcher(state)
        d.dispatch("<C><PTZCmd>${presetHex(0x81, 3)}</PTZCmd></C>")
        val s = state.value
        assertEquals(PtzPose(10f, 20f, 1.5f), s.presets[3])
        assertEquals(3, s.currentPresetIndex)
        assertEquals("PTZCmd", s.lastCommand?.type)
        assertEquals("SetPreset#3", s.lastCommand?.rawHex)
    }

    @Test
    fun `T3_2 — CallPreset 已存在 → emit PresetRecall effect`() {
        val target = PtzPose(45f, 0f, 2f)
        val state = MutableStateFlow(DeviceControlModel(presets = mapOf(2 to target)))
        val d = newDispatcher(state)
        d.dispatch("<C><PTZCmd>${presetHex(0x82, 2)}</PTZCmd></C>")
        val s = state.value
        assertEquals(DeviceEffect.PresetRecall(2, target), s.pendingEffect)
        assertEquals(2, s.currentPresetIndex)
    }

    @Test
    fun `T3_3 — CallPreset 不存在 → 不 emit effect`() {
        val state = newState()
        val d = newDispatcher(state)
        d.dispatch("<C><PTZCmd>${presetHex(0x82, 7)}</PTZCmd></C>")
        val s = state.value
        kotlin.test.assertNull(s.pendingEffect)
        assertEquals("CallPreset#7 (empty)", s.lastCommand?.rawHex)
    }

    @Test
    fun `T3_4 — DelPreset 移除 + 清当前索引`() {
        val state = MutableStateFlow(
            DeviceControlModel(
                presets = mapOf(1 to PtzPose(0f, 0f, 1f), 2 to PtzPose(10f, 0f, 1f)),
                currentPresetIndex = 1,
            )
        )
        val d = newDispatcher(state)
        d.dispatch("<C><PTZCmd>${presetHex(0x83, 1)}</PTZCmd></C>")
        val s = state.value
        assertEquals(mapOf(2 to PtzPose(10f, 0f, 1f)), s.presets)
        kotlin.test.assertNull(s.currentPresetIndex)
    }

    @Test
    fun `T3_5 — 越界 idx 99 不动 presets`() {
        val state = newState()
        val d = newDispatcher(state)
        d.dispatch("<C><PTZCmd>${presetHex(0x81, 99)}</PTZCmd></C>")
        val s = state.value
        assertTrue(s.presets.isEmpty())
        assertTrue(s.lastCommand?.rawHex?.contains("out-of-range") == true)
    }

    // ---------- T5b/T5d GB-2022 §9.3.4 新增项 ----------

    @Test
    fun `T5b — PTZPreciseCtrl emit PrecisePoseGoto + 写 lastPreciseCtrl`() {
        val state = newState()
        val d = newDispatcher(state)
        val xml = """
            <Control><CmdType>DeviceControl</CmdType>
              <PTZPreciseCtrl><Pan>123.45</Pan><Tilt>-15.0</Tilt><Zoom>3.5</Zoom></PTZPreciseCtrl>
            </Control>
        """.trimIndent()
        val ack = d.dispatch(xml)
        assertTrue(ack.needSipResponse)
        val s = state.value
        assertEquals(PtzPose(123.45f, -15.0f, 3.5f), s.lastPreciseCtrl)
        assertEquals(DeviceEffect.PrecisePoseGoto(PtzPose(123.45f, -15.0f, 3.5f)), s.pendingEffect)
        assertEquals("PTZPreciseCtrl", s.lastCommand?.type)
    }

    @Test
    fun `T5d_1 — DeviceUpgrade emit DeviceUpgradeRequested`() {
        val state = newState()
        val d = newDispatcher(state)
        val xml = "<C><DeviceUpgrade><Firmware>v1.2.3</Firmware></DeviceUpgrade></C>"
        d.dispatch(xml)
        val s = state.value
        assertEquals(DeviceEffect.DeviceUpgradeRequested("v1.2.3"), s.pendingEffect)
        assertEquals("DeviceUpgrade", s.lastCommand?.type)
    }

    @Test
    fun `T5d_2 — FormatSDCard emit FormatSDCardRequested`() {
        val state = newState()
        val d = newDispatcher(state)
        val xml = "<C><FormatSDCard>0</FormatSDCard></C>"
        d.dispatch(xml)
        val s = state.value
        assertEquals(DeviceEffect.FormatSDCardRequested(0), s.pendingEffect)
        assertEquals("FormatSDCard", s.lastCommand?.type)
    }

    // M5 batch3 §4.13 FormatSDCard DiskNum 字段补全(T3)

    @Test
    fun `batch3 T3-1 — FormatSDCard 优先取 DiskNum`() {
        // GB-2022 标准:<FormatSDCard>1</FormatSDCard><DiskNum>2</DiskNum>
        // 卡号应取 DiskNum=2,而不是 FormatSDCard 的 "1"(动作触发标志)
        val state = newState()
        val d = newDispatcher(state)
        d.dispatch("<C><FormatSDCard>1</FormatSDCard><DiskNum>2</DiskNum></C>")
        assertEquals(DeviceEffect.FormatSDCardRequested(2), state.value.pendingEffect)
    }

    @Test
    fun `batch3 T3-2 — FormatSDCard 缺 DiskNum fallback FormatSDCard 整数 老格式`() {
        val state = newState()
        val d = newDispatcher(state)
        d.dispatch("<C><FormatSDCard>3</FormatSDCard></C>")
        assertEquals(DeviceEffect.FormatSDCardRequested(3), state.value.pendingEffect)
    }

    @Test
    fun `batch3 T3-3 — FormatSDCard DiskNum 非数字降级 0`() {
        val state = newState()
        val d = newDispatcher(state)
        d.dispatch("<C><FormatSDCard>1</FormatSDCard><DiskNum>abc</DiskNum></C>")
        // DiskNum 解析失败 → fallback FormatSDCard=1
        assertEquals(DeviceEffect.FormatSDCardRequested(1), state.value.pendingEffect)
    }

    @Test
    fun `batch3 T3-4 — FormatSDCard 全部缺失 → cardIndex=0`() {
        val state = newState()
        val d = newDispatcher(state)
        d.dispatch("<C><FormatSDCard>x</FormatSDCard></C>")
        // 都解析不到数字 → 0
        assertEquals(DeviceEffect.FormatSDCardRequested(0), state.value.pendingEffect)
    }

    @Test
    fun `T5d_3 — TargetTrack 仅记 lastCommand 不 emit effect`() {
        val state = newState()
        val d = newDispatcher(state)
        val xml = "<C><TargetTrack>Manual</TargetTrack></C>"
        d.dispatch(xml)
        val s = state.value
        kotlin.test.assertNull(s.pendingEffect)
        assertEquals("TargetTrack", s.lastCommand?.type)
        // batch3 改动:detail 改为 mode=Manual 风格(从单一字符串改为多字段),老格式回退
        assertEquals("mode=Manual", s.lastCommand?.rawHex)
    }

    // M5 batch3 §4.14 TargetTrack 完整字段(T4)

    @Test
    fun `batch3 T4-1 — TargetTrack Mode + ObjectID + Speed 全字段`() {
        val state = newState()
        val d = newDispatcher(state)
        d.dispatch("<C><TargetTrack>1</TargetTrack><Mode>Auto</Mode><ObjectID>person-1</ObjectID><Speed>50</Speed></C>")
        val s = state.value
        assertEquals("TargetTrack", s.lastCommand?.type)
        assertEquals("mode=Auto obj=person-1 speed=50", s.lastCommand?.rawHex)
    }

    @Test
    fun `batch3 T4-2 — TargetTrack Manual 仅 ObjectID 无 Speed`() {
        val state = newState()
        val d = newDispatcher(state)
        d.dispatch("<C><TargetTrack>1</TargetTrack><Mode>Manual</Mode><ObjectID>car-2</ObjectID></C>")
        assertEquals("mode=Manual obj=car-2", state.value.lastCommand?.rawHex)
    }

    @Test
    fun `batch3 T4-3 — TargetTrack Stop 无附加`() {
        val state = newState()
        val d = newDispatcher(state)
        d.dispatch("<C><TargetTrack>1</TargetTrack><Mode>Stop</Mode></C>")
        assertEquals("mode=Stop", state.value.lastCommand?.rawHex)
    }

    @Test
    fun `batch3 T4-4 — TargetTrack 未知 mode 不写 lastCommand`() {
        val state = newState()
        val d = newDispatcher(state)
        d.dispatch("<C><TargetTrack>1</TargetTrack><Mode>Foo</Mode></C>")
        kotlin.test.assertNull(state.value.lastCommand)
    }

    @Test
    fun `batch3 T4-5 — TargetTrack 老格式 fallback`() {
        val state = newState()
        val d = newDispatcher(state)
        // 老格式:<TargetTrack>Auto</TargetTrack> 取代 <Mode>Auto</Mode>
        d.dispatch("<C><TargetTrack>Auto</TargetTrack><ObjectID>obj-9</ObjectID></C>")
        assertEquals("mode=Auto obj=obj-9", state.value.lastCommand?.rawHex)
    }

    // ---------- 辅助控制 (Aux On/Off, byte3=0x89/0x8A) ----------

    private fun auxHex(on: Boolean, auxIndex: Int): String {
        val opCode = if (on) 0x89 else 0x8A
        val sum = (0xA5 + 0x0F + 0x01 + opCode + auxIndex + 0 + 0) and 0xFF
        return listOf(0xA5, 0x0F, 0x01, opCode, auxIndex, 0, 0, sum)
            .joinToString("") { it.toString(16).padStart(2, '0').uppercase() }
    }

    @Test
    fun `Aux_1 — 雨刷 ON 写 auxStates 1=true`() {
        val state = newState()
        val d = newDispatcher(state)
        d.dispatch("<C><PTZCmd>${auxHex(true, 1)}</PTZCmd></C>")
        val s = state.value
        assertEquals(true, s.auxStates[1])
        assertEquals("PTZCmd", s.lastCommand?.type)
        assertEquals("雨刷 ON", s.lastCommand?.rawHex)
    }

    @Test
    fun `Aux_2 — 加热 OFF 写 auxStates 3=false`() {
        val state = MutableStateFlow(DeviceControlModel(auxStates = mapOf(3 to true)))
        val d = newDispatcher(state)
        d.dispatch("<C><PTZCmd>${auxHex(false, 3)}</PTZCmd></C>")
        val s = state.value
        assertEquals(false, s.auxStates[3])
        assertEquals("加热 OFF", s.lastCommand?.rawHex)
    }

    @Test
    fun `Aux_3 — 未知 index 不动 auxStates 仅记 unmapped lastCommand`() {
        val state = newState()
        val d = newDispatcher(state)
        d.dispatch("<C><PTZCmd>${auxHex(true, 99)}</PTZCmd></C>")
        val s = state.value
        assertTrue(s.auxStates.isEmpty())
        assertTrue(s.lastCommand?.rawHex?.contains("unmapped") == true)
    }

    // ---------- FI 族(表 A.6:聚焦 / 光圈)----------
    //
    // 历史:这一节原先叫「Focus 累计」,把 byte3 的 bit6/bit7 当 Focus Near/Far、
    // 把 byte6 低 4 位当聚焦速度,并断言"每条命令累加 focusLevel 0.005×速度"。
    // 三处都与标准不符(见 `gb28181/PtzCmdDecoder.kt` 表 A.6 的位定义),已重写为:
    //   · 字节4 走 0x40|光圈位|聚焦位,字节5 才是聚焦速度
    //   · 设备侧存的是**速率**不是位置 —— FI 命令和方向命令同形,是"带速度的开始动作"

    /**
     * FI 族 hex helper(表 A.6)。
     *
     * byte4 = 0x40 | bit3(光圈缩) | bit2(光圈放) | bit1(聚焦近) | bit0(聚焦远)
     * byte5 = 聚焦速度、byte6 = 光圈速度、byte7 高 4 位 = 0。
     */
    private fun fiHex(
        irisOpen: Boolean = false,
        irisClose: Boolean = false,
        focusNear: Boolean = false,
        focusFar: Boolean = false,
        focusSpeed: Int = 0,
        irisSpeed: Int = 0,
    ): String {
        var op = 0x40
        if (irisClose) op = op or 0x08
        if (irisOpen) op = op or 0x04
        if (focusNear) op = op or 0x02
        if (focusFar) op = op or 0x01
        val b4 = focusSpeed and 0xFF
        val b5 = irisSpeed and 0xFF
        val sum = (0xA5 + 0x0F + 0x01 + op + b4 + b5 + 0) and 0xFF
        return listOf(0xA5, 0x0F, 0x01, op, b4, b5, 0, sum)
            .joinToString("") { it.toString(16).padStart(2, '0').uppercase() }
    }

    /** FI 速度 → 归一化行程速率(与 `PtzHandler.LENS_RATE_PER_SPEED` 同一口径)。 */
    private fun lensRate(speed: Int): Float = speed * 0.5f / 255f

    @Test
    fun `FI_1 — 聚焦只写速率,不动位置`() {
        val state = MutableStateFlow(DeviceControlModel(focusLevel = 0.5f))
        val d = newDispatcher(state)
        d.dispatch("<C><PTZCmd>${fiHex(focusNear = true, focusSpeed = 102)}</PTZCmd></C>")
        val s = state.value
        // 近焦 → 负速率(位置往"近"端走)
        kotlin.test.assertEquals(-lensRate(102), s.focusSpeed, absoluteTolerance = 1e-5f)
        kotlin.test.assertEquals(0f, s.irisSpeed, absoluteTolerance = 1e-5f)
        // 位置不动:推进它的是 UI 的积分节拍(速率×时间),不是这一条命令
        kotlin.test.assertEquals(0.5f, s.focusLevel, absoluteTolerance = 1e-5f)
    }

    @Test
    fun `FI_2 — 0x40 停止把两轴速率归零`() {
        val state = MutableStateFlow(DeviceControlModel(focusSpeed = 0.3f, irisSpeed = -0.2f))
        val d = newDispatcher(state)
        // 0x40 = 表 A.6 该族停止(bit3~bit0 全清),两轴一起停
        d.dispatch("<C><PTZCmd>${fiHex()}</PTZCmd></C>")
        kotlin.test.assertEquals(0f, state.value.focusSpeed, absoluteTolerance = 1e-5f)
        kotlin.test.assertEquals(0f, state.value.irisSpeed, absoluteTolerance = 1e-5f)
    }

    @Test
    fun `FI_3 — 光圈放大不得带动云台(回归)`() {
        val state = MutableStateFlow(DeviceControlModel())
        val d = newDispatcher(state)
        // 0x44 = 表 A.6 光圈放大。原实现把它按 PTZ 族拆位,得到「聚焦远 + 俯仰下」,
        // 现象是**平台一调光圈、云台跟着仰俯**,且三轴速率被莫名写非 0。
        d.dispatch("<C><PTZCmd>${fiHex(irisOpen = true, irisSpeed = 255)}</PTZCmd></C>")
        val s = state.value
        kotlin.test.assertEquals(0f, s.panSpeed, absoluteTolerance = 1e-5f)
        kotlin.test.assertEquals(0f, s.tiltSpeed, absoluteTolerance = 1e-5f)
        kotlin.test.assertEquals(0f, s.zoomSpeed, absoluteTolerance = 1e-5f)
        kotlin.test.assertEquals(0f, s.focusSpeed, absoluteTolerance = 1e-5f)
        kotlin.test.assertEquals(lensRate(255), s.irisSpeed, absoluteTolerance = 1e-5f)
    }

    @Test
    fun `FI_4 — 光圈与聚焦可组合下发,两条速率各自独立(表A6 注1)`() {
        val state = MutableStateFlow(DeviceControlModel())
        val d = newDispatcher(state)
        d.dispatch(
            "<C><PTZCmd>${fiHex(irisOpen = true, focusFar = true, focusSpeed = 204, irisSpeed = 51)}</PTZCmd></C>"
        )
        val s = state.value
        kotlin.test.assertEquals(lensRate(204), s.focusSpeed, absoluteTolerance = 1e-5f)
        kotlin.test.assertEquals(lensRate(51), s.irisSpeed, absoluteTolerance = 1e-5f)
    }
}
