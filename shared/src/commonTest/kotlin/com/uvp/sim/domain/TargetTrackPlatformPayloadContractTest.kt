package com.uvp.sim.domain

import com.uvp.sim.config.DeviceConfig
import com.uvp.sim.config.ServerConfig
import com.uvp.sim.config.SimConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **跨仓契约锚点**：平台侧（`UVP-GB28181`）真实构造的 `TargetTrack` 报文 → 模拟器设备侧状态。
 *
 * ## 下面三段报文从哪来
 *
 * 不是手写的，是从平台仓库里**跑出来的**（`manscdp.BuildTargetTrackControlWithProfile`，
 * 2022 profile，`deviceID=34020000001320000001`、`SN=88`）。抓取方式：
 *
 * ```go
 * // 在平台仓 server/ 下临时加一个用例，跑完即删：
 * body, _ := manscdp.BuildTargetTrackControlWithProfile(
 *     protocol.ProfileFor(protocol.Version2022),
 *     "34020000001320000001", 88, command)
 * t.Logf("%s", string(body))
 * ```
 *
 * 来源提交：`7168c8c2 feat(gb28181): 目标跟踪 TargetTrack 全链（GB/T 28181-2022 A.2.3.1.14）`。
 *
 * ## 为什么值得把报文**照抄**进来
 *
 * 两个仓库各自都有一堆绿测试，但"平台发的东西模拟器认不认"这条缝**没人管**：
 *  - 平台侧测试只断言自己拼出来的 XML 长什么样；
 *  - 模拟器侧测试只喂自己手写的 XML。
 *
 * 于是**元素名漂移**（比如平台把 `<TargetArea>` 改成嵌套、或模拟器改成只认 `<Mode>` 而不认
 * 裸 `<TargetTrack>`）在两仓都能全绿，而现场表现是"平台点了手动跟踪，设备屏幕毫无反应"——
 * 又因为这是**无应答命令**（9.3.1 d)，平台连一条错误回应都收不到。
 *
 * ⛔ 平台改报文时这三段**必须跟着重抓**。它不是"漂亮的端到端测试"，而是这条缝上唯一的绳子。
 */
class TargetTrackPlatformPayloadContractTest {

    private val config = SimConfig(
        server = ServerConfig(ip = "127.0.0.1", serverId = "34020000002000000001", domain = "3402000000"),
        device = DeviceConfig(
            deviceId = "34020000001320000001",
            videoChannelId = "34020000001310000001",
            alarmChannelId = "34020000001340000001",
            username = "34020000001320000001",
            password = "test-password",
        ),
    )

    private object NoopActions : DeviceControlActions {
        override suspend fun reboot() {}
        override suspend fun snapshot() {}
        override fun requestKeyFrame() {}
        override suspend fun triggerSnapshotConfig(cfg: com.uvp.sim.gb28181.SnapShotConfig) {}
        override fun startUpgrade(sessionId: String, firmware: String, fileUrl: String) {}
        override fun formatStorageCard(cardIndex: Int) {}
    }

    /** 平台 `TargetTrackMode=Manual` + `DeviceID2` + 完整 `TargetArea` 的真实产物。 */
    private val manualPayload = "<?xml version=\"1.0\" encoding=\"GB18030\"?>\r\n" +
        "<Control><CmdType>DeviceControl</CmdType><SN>88</SN>" +
        "<DeviceID>34020000001320000001</DeviceID>" +
        "<TargetTrack>Manual</TargetTrack>" +
        "<DeviceID2>34020000001320000009</DeviceID2>" +
        "<TargetArea><Length>1920</Length><Width>1080</Width>" +
        "<MidPointX>960</MidPointX><MidPointY>540</MidPointY>" +
        "<LengthX>200</LengthX><LengthY>100</LengthY></TargetArea></Control>"

    private val autoPayload = "<?xml version=\"1.0\" encoding=\"GB18030\"?>\r\n" +
        "<Control><CmdType>DeviceControl</CmdType><SN>88</SN>" +
        "<DeviceID>34020000001320000001</DeviceID><TargetTrack>Auto</TargetTrack></Control>"

    private val stopPayload = "<?xml version=\"1.0\" encoding=\"GB18030\"?>\r\n" +
        "<Control><CmdType>DeviceControl</CmdType><SN>88</SN>" +
        "<DeviceID>34020000001320000001</DeviceID><TargetTrack>Stop</TargetTrack></Control>"

    /**
     * ⛔ 平台发的是**裸 `<TargetTrack>Manual</TargetTrack>`**，元素名就叫 `TargetTrack`，
     * **没有** `<Mode>` 子元素。
     *
     * 模拟器侧的 `handleTargetTrack` 是「先找 `Mode`、找不到回落到 `TargetTrack`」——
     * 这条回落是**必需**的（标准 A.2.3.1.14 的 schema 就是 `TargetTrack` 元素本身带枚举值）。
     * 谁把回落删掉（比如照某个私有的 `<Mode>` 写法"统一"一下），这条用例必红。
     */
    @Test
    fun `平台 manual 报文 → 设备侧 manual 跟踪态 + 换算后的框`() {
        val state = MutableStateFlow(DeviceControlModel())
        DeviceControlDispatcher(state, config, NoopActions, null).dispatch(manualPayload)

        val track = assertNotNull(state.value.targetTrack, "平台真实报文必须能在设备侧落出跟踪态")
        assertEquals(TargetTrackMode.Manual, track.mode)
        assertEquals("34020000001320000009", track.deviceId2, "DeviceID2 必须被认出来(全景通道)")
        val box = assertNotNull(track.box)
        // 1920×1080 窗口、正中 200×100 ⇒ 中心 (0.5,0.5)、宽 200/1920、高 100/1080。
        assertTrue(kotlin.math.abs(box.centerX - 0.5f) < 1e-4f, "centerX=${box.centerX}")
        assertTrue(kotlin.math.abs(box.centerY - 0.5f) < 1e-4f, "centerY=${box.centerY}")
        assertTrue(kotlin.math.abs(box.width - 200f / 1920f) < 1e-4f, "width=${box.width}")
        assertTrue(kotlin.math.abs(box.height - 100f / 1080f) < 1e-4f, "height=${box.height}")
    }

    /** 平台 `Auto` 的真实产物：无 `DeviceID2`、无 `TargetArea`（标准里两个元素都是 `minOccurs=0`）。 */
    @Test
    fun `平台 auto 报文 → 设备侧 auto 跟踪态(用声明过的模拟目标框)`() {
        val state = MutableStateFlow(DeviceControlModel())
        DeviceControlDispatcher(state, config, NoopActions, null).dispatch(autoPayload)

        val track = assertNotNull(state.value.targetTrack)
        assertEquals(TargetTrackMode.Auto, track.mode)
        assertNull(track.deviceId2, "平台没带 DeviceID2,设备不得自己编一个")
        assertEquals(TargetTrackState.SIMULATED_AUTO_BOX, track.box)
    }

    /**
     * 平台 `Stop` 的真实产物。
     *
     * ⛔ 三份报文里**只有这一份**要能把跟踪态清成 `null`：`Stop` 之后设备屏幕上那个框必须消失，
     * 否则"停止跟踪"在设备侧不可见（而平台同样没有回执可看）。
     */
    @Test
    fun `平台 stop 报文 → 跟踪态清空`() {
        val state = MutableStateFlow(DeviceControlModel())
        val dispatcher = DeviceControlDispatcher(state, config, NoopActions, null)

        dispatcher.dispatch(manualPayload)
        assertNotNull(state.value.targetTrack)
        dispatcher.dispatch(stopPayload)
        assertNull(state.value.targetTrack, "Stop 必须清掉跟踪态")

        // 再发一次 Auto：设备要能重新跟起来（Stop 不许留下"以后再也不能跟踪"的副作用）。
        dispatcher.dispatch(autoPayload)
        assertEquals(TargetTrackMode.Auto, state.value.targetTrack?.mode)
    }

    /**
     * ⛔ 三份报文都必须过 `dispatch` 的**首个分支**（`xml.contains("<TargetTrack>")`），
     * 而不是掉到 A.2.3.2 配置族那个兜底分支去。
     *
     * 判据：兜底分支会返回 `needSipResponse=false`（一块配置都没认识），而 `TargetTrack`
     * 走的是 `DeviceControlAck()` 默认值 `true`。掉错分支的表现是"设备侧照样落了状态，
     * 但外层回给平台的是 `DeviceConfig` 那一套"—— 而平台在 9.3.1 d) 下根本不看应答，
     * 所以**两边都不报错**，只有报文形态悄悄不对。
     */
    @Test
    fun `三份报文都走 DeviceControl 单命令分支，不掉进配置族兜底`() {
        val d1 = DeviceControlDispatcher(MutableStateFlow(DeviceControlModel()), config, NoopActions, null)
        assertTrue(d1.dispatch(manualPayload).needSipResponse)
        assertTrue(d1.dispatch(autoPayload).needSipResponse)
        assertTrue(d1.dispatch(stopPayload).needSipResponse)
    }
}
