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
    fun `case 3a — IFrameCmd 2022 拼写同样触发关键帧`() {
        val state = newState()
        val actions = FakeEngineActions()
        val d = newDispatcher(state, actions)

        d.dispatch("<C><IFrameCmd>Send</IFrameCmd></C>")

        assertEquals(DeviceEffect.IFrameFlash, state.value.pendingEffect)
        assertEquals(1, actions.keyFrameCalled)
        assertEquals("IFrameCmd", state.value.lastCommand?.type)
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

    // ---- 2026-09-19 G-9：A.2.3.1.6 `AlarmCmd` 的复位范围（`AlarmMethod` / `AlarmType`）----

    /**
     * A.2.3.1.6 的 `AlarmMethod` / `AlarmType` 限定**复位范围**。
     * 本机只有一路报警状态，所以按"全部复位"执行 —— 但**必须解析并留痕**，
     * 否则"平台指定了范围、设备却按全部复位"这件事在设备侧完全不可观测。
     */
    @Test
    fun `G9_1 — AlarmCmd 带复位范围时记进 lastCommand`() {
        val state = MutableStateFlow(DeviceControlModel(isAlarming = true))
        val d = newDispatcher(state)
        d.dispatch("<C><AlarmCmd>0</AlarmCmd><AlarmMethod>5</AlarmMethod><AlarmType>2</AlarmType></C>")
        val s = state.value
        assertFalse(s.isAlarming, "复位语义不变（本机只有一路，按全部复位执行）")
        assertEquals("0 method=5 type=2", s.lastCommand?.rawHex)
    }

    /**
     * ⛔ 两个字段按**字符串**存，不归一成 Int：`AlarmType` 的取值域随 `AlarmMethod` 变
     * （方式=2 与方式=5 是两张完全不同的表），归一后就分不清是哪一张了。
     */
    @Test
    fun `G9_2 — AlarmMethod 与 AlarmType 不混淆，各自保留原值`() {
        val state = newState()
        val d = newDispatcher(state)
        d.dispatch("<C><AlarmCmd>1</AlarmCmd><AlarmMethod>2</AlarmMethod><AlarmType>5</AlarmType></C>")
        assertEquals("1 method=2 type=5", state.value.lastCommand?.rawHex)
    }

    @Test
    fun `G9_3 — 只带 AlarmMethod 时也留痕`() {
        val state = newState()
        val d = newDispatcher(state)
        d.dispatch("<C><AlarmCmd>1</AlarmCmd><AlarmMethod>5</AlarmMethod></C>")
        assertEquals("1 method=5", state.value.lastCommand?.rawHex)
    }

    /** 不带范围时 detail 保持旧形态（原有调用方/UI 不受影响）。 */
    @Test
    fun `G9_4 — 不带范围时 detail 不追加后缀`() {
        val state = newState()
        val d = newDispatcher(state)
        d.dispatch("<C><AlarmCmd>1</AlarmCmd></C>")
        assertEquals("1", state.value.lastCommand?.rawHex)
    }

    /** 空白取值的范围字段视为未下发，不产生 `method=  type=` 这种半截后缀。 */
    @Test
    fun `G9_5 — 空白范围字段视为未下发`() {
        val state = newState()
        val d = newDispatcher(state)
        d.dispatch("<C><AlarmCmd>1</AlarmCmd><AlarmMethod>   </AlarmMethod><AlarmType></AlarmType></C>")
        assertEquals("1", state.value.lastCommand?.rawHex)
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
        // ⭐ 一次 DeviceConfig 只下发**一个块**，所以这里报的是**块名**（= 协议里的 `ConfigType`），
        //    不是块内字段名。块名就是操作员在日志行、SIP trace、平台面板上看到的同一个词
        //    （`平台下发 DeviceConfig BasicParam → 已记 …`），比"Name/HeartBeatInterval"
        //    更贴近他实际要找的东西；块内的真值由日志的 `describe` 打。
        assertTrue("BasicParam" in ch, "actual: $ch")
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

    // ---------- T3b 巡航（表 A.8，字节5 = 巡航组号）----------

    /**
     * 巡航 hex helper — 字节4 = `0x84`~`0x88`、**字节5 = 巡航组号**、字节6 = 预置位号或 12 位参数的
     * 低 8 位、字节7 高 4 位 = 12 位参数的高 4 位。
     *
     * ⛔ 组号在**字节5**，与预置位族相反（那边字节5 固定 0、编号在字节6）。跟 `presetHex` 一样，
     * helper 和解码器一起错会互相印证，所以"停止"那条用例用的是**硬编码的平台真实帧**。
     */
    private fun cruiseHex(opCode: Int, trackNum: Int, param: Int = 0): String {
        val lo = param and 0xFF
        val hi = ((param shr 8) and 0x0F) shl 4
        val sum = (0xA5 + 0x0F + 0x01 + opCode + trackNum + lo + hi) and 0xFF
        return listOf(0xA5, 0x0F, 0x01, opCode, trackNum, lo, hi, sum)
            .joinToString("") { it.toString(16).padStart(2, '0').uppercase() }
    }

    @Test
    fun `T3b_1 — 0x88 启动巡航记下组号`() {
        val state = newState()
        val d = newDispatcher(state)
        d.dispatch("<C><PTZCmd>${cruiseHex(0x88, 2)}</PTZCmd></C>")
        val s = state.value
        assertEquals(2, s.activeCruiseTrack)
        assertEquals("PTZCmd", s.lastCommand?.type)
        assertEquals("巡航 #2 启动", s.lastCommand?.rawHex)
    }

    /**
     * ⛔ 全零停止指令**同时是巡航的停止码** —— 标准里没有第二条"停巡航"的指令
     * （表 A.8 只有 0x84~0x88，其中 0x88 是开始巡航），平台点「停止巡航」发出来的就是这一帧。
     *
     * 原来这里只把三轴速率清零，`activeCruiseTrack` 保留 → 云台当场停住、但设备仍标着"运行中"，
     * 而且巡航执行协程下一拍会接着往下一个点转过去。现场表现是**"停不掉"**。
     *
     * 用**硬编码的平台真实帧**（`PTZActionCruiseStop`：第 4~7 字节全零）而不是 helper 生成 ——
     * helper 与解码器一起错时会互相印证。校验和 B5 = A5+0F+01。
     */
    @Test
    fun `T3b_2 — 全零停止指令同时把巡航停掉`() {
        val state = MutableStateFlow(DeviceControlModel(activeCruiseTrack = 1, panSpeed = 30f))
        val d = newDispatcher(state)
        d.dispatch("<C><PTZCmd>A50F0100000000B5</PTZCmd></C>")
        val s = state.value
        assertNull(s.activeCruiseTrack, "全零帧必须停巡航,否则平台点「停止巡航」后设备仍标着运行中")
        assertEquals(0f, s.panSpeed)
        assertEquals(0f, s.tiltSpeed)
        assertEquals(0f, s.zoomSpeed)
    }

    @Test
    fun `T3b_3 — 方向指令不能误停巡航`() {
        // 只有全零帧是停止指令。方向命令（哪怕速度很小）都不算 —— 判据放宽会把"巡航途中
        // 平台补一条云台微调"变成"巡航被悄悄停掉"。
        val state = MutableStateFlow(DeviceControlModel(activeCruiseTrack = 1))
        val d = newDispatcher(state)
        d.dispatch("<C><PTZCmd>${ptzHex(0x02, pan = 100)}</PTZCmd></C>")
        assertEquals(1, state.value.activeCruiseTrack, "只有全零帧才是停止指令")
    }

    @Test
    fun `T3b_4 — 0x88 组号 0 也算停巡航`() {
        // 平台侧 `allowZero` 允许组号 0 进来（前端不会这么发，但报文是合法的）。
        val state = MutableStateFlow(DeviceControlModel(activeCruiseTrack = 1))
        val d = newDispatcher(state)
        d.dispatch("<C><PTZCmd>${cruiseHex(0x88, 0)}</PTZCmd></C>")
        assertNull(state.value.activeCruiseTrack)
        assertEquals("巡航停止", state.value.lastCommand?.rawHex)
    }

    /**
     * ⛔ 本组唯一的**跨层**用例：平台真实帧序列 → 设备侧真的能算出该往哪转。
     *
     * 其余用例要么由 `cruiseHex` 生成帧（helper 与解码器可能一起错、互相印证），要么直接构造
     * `DeviceControlModel`（跳过了 `0x84`/`0x86`/`0x87` 的落库）。而"点开始巡航设备纹丝不动"
     * 的根因可以藏在**这条链的任意一环**：帧字节位置错 → `cruiseTracks` 空 → `cruiseStepAt`
     * 返回 null → 什么都不发生，且现象与"没实现执行"完全一样。
     *
     * 下面 9 条 hex 是平台 `manscdp.BuildExtendedPTZControlWithProfile` 实际吐出来的（组号 1、
     * 预置位 2/3/4、速度 128 与 300、停留 5 秒），对应 `CreateCruiseTrack` 的
     * `clear(0x85) → add_stop(0x84)×3 → set_speed(0x86) → set_dwell(0x87)` 再加 `start(0x88)`；
     * 其中 `set_speed` 走了两次，第二次特意用 **>255** 的值把 12 位的高 4 位通路也压上。
     */
    @Test
    fun `T3b_5 — 平台真实帧建轨加启动后,引擎能算出该走的点位`() {
        val p2 = PtzPose(pan = 10f, tilt = 0f, zoom = 1f)
        val p3 = PtzPose(pan = 20f, tilt = 5f, zoom = 1.5f)
        val p4 = PtzPose(pan = 30f, tilt = -5f, zoom = 2f)
        val state = MutableStateFlow(
            DeviceControlModel(presets = mapOf(2 to p2, 3 to p3, 4 to p4)),
        )
        val d = newDispatcher(state)

        // 平台 ReplaceExisting 时先清轨（0x85 组号 1、参数 0 = 删整条）。
        d.dispatch("<C><PTZCmd>A50F01850100003B</PTZCmd></C>")
        assertTrue(
            state.value.cruiseTracks[1]?.points.isNullOrEmpty(),
            "0x85 参数 0 必须清空整条轨迹,否则重新编辑时会跟旧点位叠加",
        )

        // 逐个加点。
        d.dispatch("<C><PTZCmd>A50F01840102003C</PTZCmd></C>")
        d.dispatch("<C><PTZCmd>A50F01840103003D</PTZCmd></C>")
        d.dispatch("<C><PTZCmd>A50F01840104003E</PTZCmd></C>")
        assertEquals(listOf(2, 3, 4), state.value.cruiseTracks[1]?.points, "字节5 是组号、字节6 是预置位号，别跟预置位族搞反")

        // 速度 128 = 0x80 → 字节6 低 8 位 = 0x80、字节7 高 4 位 = 0;停留 5 秒同理。
        d.dispatch("<C><PTZCmd>A50F0186018000BC</PTZCmd></C>")
        d.dispatch("<C><PTZCmd>A50F018701050042</PTZCmd></C>")
        assertEquals(128, state.value.cruiseTracks[1]?.speed)
        assertEquals(5, state.value.cruiseTracks[1]?.dwellTime)

        // ⛔ **上面 128 那条守不住高 4 位**（128 ≤ 255，截断前后同值）——这里原本写着
        //    "只取低 8 位会在 >255 时错"，但取的值根本没跨过 256，是**假守卫**：把解码器改坏
        //    它照旧全绿。速度/停留是 **12 位（01H-FFFH，1-4095）**，不是 0-255；
        //    下面用平台真实帧跨过 256：300 = 0x12C → 字节6 = 0x2C（低 8 位）、
        //    字节7 高半字节 = 0x1（高 4 位）。只取字节6 会读成 **44**。
        d.dispatch("<C><PTZCmd>A50F0186012C1078</PTZCmd></C>")
        assertEquals(
            300, state.value.cruiseTracks[1]?.speed,
            ">255 的速度必须把字节7 高 4 位拼回来;只取字节6 会得到 44",
        )

        // 启动。
        d.dispatch("<C><PTZCmd>A50F01880100003E</PTZCmd></C>")
        val model = state.value
        assertEquals(1, model.activeCruiseTrack)

        // ★ 关键:平台配好的轨迹,设备侧真的算得出第一个点该去哪。
        assertEquals(2, cruiseStepAt(model, 0)?.presetIndex)
        assertEquals(p2, cruiseStepAt(model, 0)?.target)
        assertEquals(3, cruiseStepAt(model, 1)?.presetIndex)
        assertEquals(4, cruiseStepAt(model, 2)?.presetIndex)
        assertEquals(2, cruiseStepAt(model, 3)?.presetIndex, "走完 3 个点要绕回第一个,巡航是循环的")
        assertEquals(5, cruiseStepAt(model, 0)?.dwellSeconds, "停留时间取 0x87 下发的那 5 秒,不是出厂默认 30")

        // 停止后整条链归零。
        d.dispatch("<C><PTZCmd>A50F0100000000B5</PTZCmd></C>")
        assertNull(state.value.activeCruiseTrack)
        assertNull(cruiseStepAt(state.value, 0), "停巡航后不得再算下一步")
        assertEquals(listOf(2, 3, 4), state.value.cruiseTracks[1]?.points, "停巡航不该把配好的点位链清掉")
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
    //
    // ⭐ 2026-09-19 整体改正：原先这一组钉的是 `<ObjectID>` + `<Speed>`，
    //    这两个元素名在 2022 全书与 2016 附录 A **都是 0 命中**（`ObjectID` 实为把
    //    `TargetTrack` 的取值 `Auto/Manual/Stop` 误读成"对象标识"）。
    //    标准 A.2.3.1.14 的框选参数是 `<TargetArea>` 六个整数字段，见 TargetAreaTest。

    private val targetAreaXml = """
        <TargetArea>
        <Length>1920</Length><Width>1080</Width>
        <MidPointX>960</MidPointX><MidPointY>540</MidPointY>
        <LengthX>200</LengthX><LengthY>100</LengthY>
        </TargetArea>
    """.trimIndent()

    @Test
    fun `batch3 T4-1 — TargetTrack Mode + DeviceID2 + TargetArea`() {
        val state = newState()
        val d = newDispatcher(state)
        d.dispatch(
            "<C><TargetTrack>1</TargetTrack><Mode>Auto</Mode>" +
                "<DeviceID2>34020000001320000009</DeviceID2>$targetAreaXml</C>"
        )
        val s = state.value
        assertEquals("TargetTrack", s.lastCommand?.type)
        assertEquals(
            "mode=Auto pano=34020000001320000009 area=1920x1080 @(960,540) 200x100",
            s.lastCommand?.rawHex
        )
    }

    @Test
    fun `batch3 T4-2 — TargetTrack Manual 带框选坐标`() {
        val state = newState()
        val d = newDispatcher(state)
        d.dispatch("<C><TargetTrack>1</TargetTrack><Mode>Manual</Mode>$targetAreaXml</C>")
        assertEquals("mode=Manual area=1920x1080 @(960,540) 200x100", state.value.lastCommand?.rawHex)
    }

    /**
     * `mode=Manual` 而**没有** `TargetArea`：指令本身合法（元素是 `minOccurs=0`），
     * 设备仍记下收到过，但日志里要留痕 —— 否则"平台框选没生效"在设备侧完全不可观测。
     */
    @Test
    fun `batch3 T4-2b — TargetTrack Manual 无 TargetArea 仍记 lastCommand 但不带 area`() {
        val state = newState()
        val d = newDispatcher(state)
        d.dispatch("<C><TargetTrack>1</TargetTrack><Mode>Manual</Mode></C>")
        assertEquals("TargetTrack", state.value.lastCommand?.type)
        assertEquals("mode=Manual", state.value.lastCommand?.rawHex)
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
        d.dispatch("<C><TargetTrack>Auto</TargetTrack>$targetAreaXml</C>")
        assertEquals("mode=Auto area=1920x1080 @(960,540) 200x100", state.value.lastCommand?.rawHex)
    }

    /**
     * ⛔ `ObjectID` / `Speed` 是自造名，**不得**再出现在 detail 里。
     * 保留它们进去等于继续上报一份标准里没有的目标描述，平台按标准对账时对不上。
     */
    @Test
    fun `batch3 T4-6 — 自造 ObjectID-Speed 不再进入 detail`() {
        val state = newState()
        val d = newDispatcher(state)
        d.dispatch("<C><TargetTrack>1</TargetTrack><Mode>Auto</Mode><ObjectID>person-1</ObjectID><Speed>50</Speed></C>")
        val detail = state.value.lastCommand?.rawHex
        assertEquals("mode=Auto", detail)
        kotlin.test.assertFalse(detail!!.contains("person-1"), "ObjectID 不得出现在 detail: $detail")
        kotlin.test.assertFalse(detail.contains("speed"), "Speed 不得出现在 detail: $detail")
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

    // ===== A.2.1.13 VideoParamAttribute(2022 新增的配置类型)=====

    private fun videoParamWrite(num: Int, items: String) =
        "<Control><CmdType>DeviceConfig</CmdType><SN>9</SN>" +
            "<DeviceID>34020000001320000001</DeviceID>" +
            "<VideoParamAttribute Num=\"$num\">$items</VideoParamAttribute></Control>"

    private val oneVideoParamItem =
        "<Item><StreamNumber>0</StreamNumber><VideoFormat>5</VideoFormat>" +
            "<Resolution>5</Resolution><FrameRate>30</FrameRate>" +
            "<BitRateType>1</BitRateType><VideoBitRate>8192</VideoBitRate></Item>"

    /**
     * ⭐ 本组守的是**接线**,不是逻辑。
     *
     * `<VideoParamAttribute>` 与 `<BasicParam>` 同属 `DeviceConfig` 这个 CmdType,
     * 分发只能按**块名**判。而块名带 `Num` 属性 —— 写成 `contains("<VideoParamAttribute>")`
     * 会**永远不命中**,现象是「平台下发成功、设备侧毫无反应」,一个报错都没有:
     * 正是本仓反复吃过的"协议改动编译过 ≠ 对了"那一类。
     */
    @Test
    fun `DeviceConfig — VideoParamAttribute 按块名分流并落状态`() {
        val state = MutableStateFlow(DeviceControlModel())
        val d = newDispatcher(state)
        val ack = d.dispatch(videoParamWrite(1, oneVideoParamItem))

        assertTrue(ack.needSipResponse, "必须回 200 OK,否则平台会重试")
        val s = state.value
        assertEquals(1, s.videoParams.size)
        val written = s.videoParams.getValue(0)
        assertEquals("5", written.videoFormat)
        assertEquals("5", written.resolution)
        assertEquals("30", written.frameRate)
        assertEquals("1", written.bitRateType)
        assertEquals("8192", written.videoBitRate)
        // 设备屏幕上的可见性全靠这两项(HUD 的「平台在做什么」通道)
        assertEquals("DeviceConfig", s.lastCommand?.type)
        assertTrue(s.lastCommand?.rawHex?.contains("VideoParamAttribute") == true)
        assertTrue(s.pendingEffect is DeviceEffect.ConfigChanged)
    }

    @Test
    fun `DeviceConfig — BasicParam 不会串进 videoParams`() {
        val state = MutableStateFlow(DeviceControlModel())
        val d = newDispatcher(state)
        d.dispatch(
            "<Control><CmdType>DeviceConfig</CmdType><SN>9</SN>" +
                "<DeviceID>34020000001320000001</DeviceID>" +
                "<BasicParam><Name>我的设备</Name><Expiration>3600</Expiration></BasicParam></Control>"
        )
        val s = state.value
        assertTrue(s.videoParams.isEmpty(), "BasicParam 不该写视频参数")
        assertEquals("DeviceConfig", s.lastCommand?.type)
        // ⛔ 详情里必须带**真值**（设备屏幕上要能回答"平台刚才改成了什么"）。
        //    只写"收到了"等于没记 —— 本仓为这类日志踩过一次。
        assertTrue(s.lastCommand?.rawHex?.contains("我的设备") == true, "actual: ${s.lastCommand?.rawHex}")
        assertTrue(s.lastCommand?.rawHex?.contains("3600") == true, "actual: ${s.lastCommand?.rawHex}")
    }

    @Test
    fun `DeviceConfig — 空 VideoParamAttribute 只记命令、不改已配好的码流`() {
        val state = MutableStateFlow(DeviceControlModel())
        val d = newDispatcher(state)
        d.dispatch(videoParamWrite(1, oneVideoParamItem))
        assertEquals(1, state.value.videoParams.size)

        // 空配置(Item minOccurs=0,标准允许)的语义在设备侧分不清"清空"与"平台没写这段",
        // 所以选择不动 —— 抹掉已配好的码流在平台面板上表现为「刚配好的值自己变回去了」。
        d.dispatch(videoParamWrite(0, ""))
        assertEquals(1, state.value.videoParams.size, "空配置不该清掉已有配置")
        assertEquals("5", state.value.videoParams.getValue(0).videoFormat)
    }

    @Test
    fun `DeviceConfig — 重下发同一路码流是覆盖,不是追加`() {
        val state = MutableStateFlow(DeviceControlModel())
        val d = newDispatcher(state)
        d.dispatch(videoParamWrite(1, oneVideoParamItem))
        d.dispatch(
            videoParamWrite(
                1,
                "<Item><StreamNumber>0</StreamNumber><VideoFormat>2</VideoFormat>" +
                    "<Resolution>6</Resolution><FrameRate>25</FrameRate>" +
                    "<BitRateType>2</BitRateType></Item>"
            )
        )
        val s = state.value
        assertEquals(1, s.videoParams.size, "同一路码流只应有一行")
        assertEquals("2", s.videoParams.getValue(0).videoFormat)
        assertNull(s.videoParams.getValue(0).videoBitRate, "VBR 时码率应回到缺席")
    }

    // ================= 2026-09-19 G-5：A.2.3.1.2 `PTZCmdParams` 名字下发 ---------------

    /**
     * ⭐ A.2.3.1.2 `PTZCmdParams`（**2022 新增**）：平台给预置位 / 巡航轨迹起的名字。
     *
     * 标准原文：「预置位名称（PTZCmd 设置预置位命令时可选）」「巡航轨迹名称
     * （最长 32 字节，PTZCmd 巡航指令命令时可选）」。
     * ⛔ 原先完全不解析 ⇒ 平台配的名字被静默丢弃，回读时只能回设备自编的 `Preset N`，
     * 平台看到的是「自己配的名字没保存」——会反复重配，操作员看着像功能坏了。
     */
    private fun ptzParams(body: String) = "<PTZCmdParams>$body</PTZCmdParams>"

    @Test
    fun `G5_1 — SetPreset 带 PresetName 时记下平台名`() {
        val state = newState()
        val d = newDispatcher(state)
        d.dispatch(
            "<C><PTZCmd>${presetHex(0x81, 3)}</PTZCmd>" +
                ptzParams("<PresetName>大门</PresetName>") + "</C>"
        )
        val s = state.value
        assertEquals("大门", s.presetNames[3])
        // 位姿照旧要入库 —— 名字是附加信息，不能因为带了名字就不存位姿
        assertTrue(s.presets.containsKey(3), "带名字的设置预置位仍要存 pose: ${s.presets}")
    }

    /**
     * ⛔ 这次没给名字**不得抹掉**已有名字。
     * 平台改预置位位置时未必重发名字，抹掉就表现为"名字莫名丢了"。
     */
    @Test
    fun `G5_2 — SetPreset 无 PresetName 时保留原名字`() {
        val state = MutableStateFlow(DeviceControlModel(presetNames = mapOf(3 to "大门")))
        val d = newDispatcher(state)
        d.dispatch("<C><PTZCmd>${presetHex(0x81, 3)}</PTZCmd></C>")
        assertEquals("大门", state.value.presetNames[3], "没给名字不等于要清空名字")
    }

    @Test
    fun `G5_3 — DelPreset 同时删掉名字`() {
        val state = MutableStateFlow(
            DeviceControlModel(
                presets = mapOf(1 to PtzPose(0f, 0f, 1f), 2 to PtzPose(10f, 0f, 1f)),
                currentPresetIndex = 1,
                presetNames = mapOf(1 to "大门", 2 to "后门"),
            )
        )
        val d = newDispatcher(state)
        d.dispatch("<C><PTZCmd>${presetHex(0x83, 1)}</PTZCmd></C>")
        val s = state.value
        assertFalse(s.presetNames.containsKey(1), "预置位都删了，名字不该留在表里: ${s.presetNames}")
        assertEquals("后门", s.presetNames[2], "别的预置位名字不受影响")
    }

    /**
     * ⛔ **块作用域**：`PresetName` 只有在 `PTZCmdParams` 块里才算数。
     * 报文别处出现的同名元素（例如畸形报文在前文塞了一个）不得被采信。
     */
    @Test
    fun `G5_4 — PTZCmdParams 块外的 PresetName 不被采信`() {
        val state = newState()
        val d = newDispatcher(state)
        d.dispatch(
            "<C><PresetName>野的</PresetName><PTZCmd>${presetHex(0x81, 3)}</PTZCmd></C>"
        )
        assertTrue(
            state.value.presetNames.isEmpty(),
            "块外的同名元素不得被采信: ${state.value.presetNames}"
        )
    }

    @Test
    fun `G5_5 — 名字为空或纯空白视为未下发`() {
        val state = newState()
        val d = newDispatcher(state)
        d.dispatch(
            "<C><PTZCmd>${presetHex(0x81, 3)}</PTZCmd>" +
                ptzParams("<PresetName>   </PresetName>") + "</C>"
        )
        assertTrue(state.value.presetNames.isEmpty(), "空白名字不落库: ${state.value.presetNames}")
    }

    @Test
    fun `G5_6 — 巡航 SET_POINT 带 CruiseTrackName 时记下轨迹名`() {
        val state = newState()
        val d = newDispatcher(state)
        // 0x84 = SET_POINT（把预置位 2 加进轨迹 1）
        d.dispatch(
            "<C><PTZCmd>${cruiseHex(0x84, 1, 2)}</PTZCmd>" +
                ptzParams("<CruiseTrackName>厂区巡检</CruiseTrackName>") + "</C>"
        )
        val track = state.value.cruiseTracks[1]
        assertEquals("厂区巡检", track?.name)
        assertEquals(listOf(2), track?.points, "点位照旧要加进去")
    }

    /** 重复加点（常态）不带名字时，轨迹名要保持原值。 */
    @Test
    fun `G5_7 — 巡航重复加点不带名字时保留轨迹名`() {
        val state = newState()
        val d = newDispatcher(state)
        d.dispatch(
            "<C><PTZCmd>${cruiseHex(0x84, 1, 2)}</PTZCmd>" +
                ptzParams("<CruiseTrackName>厂区巡检</CruiseTrackName>") + "</C>"
        )
        d.dispatch("<C><PTZCmd>${cruiseHex(0x84, 1, 3)}</PTZCmd></C>")
        val track = state.value.cruiseTracks[1]
        assertEquals("厂区巡检", track?.name, "第二次加不带名字，名字不该丢")
        assertEquals(listOf(2, 3), track?.points)
    }

    /** `PresetName` 与 `CruiseTrackName` 同时出现（畸形/通用报文）时各归各路，不互相污染。 */
    @Test
    fun `G5_8 — 两个名字同块出现时各归各路`() {
        val state = newState()
        val d = newDispatcher(state)
        d.dispatch(
            "<C><PTZCmd>${presetHex(0x81, 3)}</PTZCmd>" +
                ptzParams("<PresetName>大门</PresetName><CruiseTrackName>厂区巡检</CruiseTrackName>") + "</C>"
        )
        val s = state.value
        assertEquals("大门", s.presetNames[3], "预置位命令只该吃 PresetName")
        assertTrue(s.cruiseTracks.isEmpty(), "预置位命令不该顺手建一条巡航轨迹: ${s.cruiseTracks}")
    }

    /** 调预置位（0x82）不改名字 —— 名字是"设置"这条命令的附带信息。 */
    @Test
    fun `G5_9 — CallPreset 不改名字`() {
        val state = MutableStateFlow(
            DeviceControlModel(
                presets = mapOf(2 to PtzPose(45f, 0f, 2f)),
                presetNames = mapOf(2 to "大门"),
            )
        )
        val d = newDispatcher(state)
        d.dispatch(
            "<C><PTZCmd>${presetHex(0x82, 2)}</PTZCmd>" +
                ptzParams("<PresetName>瞎写的</PresetName>") + "</C>"
        )
        assertEquals("大门", state.value.presetNames[2], "调用不该覆盖名字")
    }
}
