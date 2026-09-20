package com.uvp.sim.gb28181

import com.uvp.sim.config.ChannelProfile
import com.uvp.sim.config.DeviceConfig
import com.uvp.sim.config.GbVersion
import com.uvp.sim.config.OsdConfig
import com.uvp.sim.config.OsdLayer
import com.uvp.sim.config.OsdPosition
import com.uvp.sim.config.OsdSize
import com.uvp.sim.config.ServerConfig
import com.uvp.sim.config.SimConfig
import com.uvp.sim.config.VideoProfile
import com.uvp.sim.config.VideoResolution
import com.uvp.sim.domain.DeviceControlActions
import com.uvp.sim.domain.DeviceControlDispatcher
import com.uvp.sim.domain.DeviceControlModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * GB/T 28181-2022 附录 A **设备配置族**（A.2.3.2 命令 / A.2.6.9 回读应答）的协议侧用例。
 *
 * 这一族共 12 个 `ConfigType`（A.2.4.7 清单）。本文件覆盖 2026-09-19 补齐的 7 个
 * （PictureMask / OSDConfig / FrameMirror / AlarmReport / VideoRecordPlan / VideoAlarmRecord /
 * SnapShot）+ 回读侧被改正的 `BasicParam`。`VideoParamAttribute` 另有
 * [VideoParamAttributeTest]，`VideoParamOpt` 在 [ConfigDownloadResponseTest]。
 *
 * 用例的价值几乎全在**否定式断言**上：它们守的是"报文里不该出现的形态"——
 * 元素名写错、属性写成子元素、缺席的可选元素被补成 0、越界值被落库。这些都是
 * **两侧都不报错**的缺陷（对端解出来是零值/空块，看起来只是"设备没数据"）。
 *
 * ⚠️ 期望值一律照 `appA.txt` / `appA2016.txt`（标准原文 OCR）逐字段抄，不照记忆写。
 */
class DeviceConfigFamilyTest {

    private val state = MutableStateFlow(DeviceControlModel())

    private fun config(
        streamNumberList: String = "0/1",
        resolution: VideoResolution = VideoResolution.FHD_1080P,
    ) = SimConfig(
        server = ServerConfig(ip = "127.0.0.1", serverId = "34020000002000000001", domain = "3402000000"),
        device = DeviceConfig(
            deviceId = "34020000001110000001",
            name = "我的设备",
            videoChannelId = "34020000001320000001",
            alarmChannelId = "34020000001340000001",
            username = "admin",
            password = "test-password",
            channel = ChannelProfile(streamNumberList = streamNumberList),
        ),
        expiresSeconds = 7200,
        keepaliveIntervalSeconds = 30,
        maxKeepaliveTimeouts = 5,
        video = VideoProfile(resolution = resolution),
    )

    // ---- 三态取值小工具：`assertIs<Accepted<X>>` 会撞上泛型擦除，改成显式取值 ----

    private fun <S> accepted(result: ConfigParse<S>): S {
        assertTrue(result is ConfigParse.Accepted<*>, "期望 Accepted，实际 $result")
        @Suppress("UNCHECKED_CAST")
        return (result as ConfigParse.Accepted<S>).state
    }

    private fun <S> rejected(result: ConfigParse<S>): String {
        assertTrue(result is ConfigParse.Rejected, "期望 Rejected，实际 $result")
        return result.reason
    }

    private fun <S> assertAbsent(result: ConfigParse<S>) {
        assertTrue(result is ConfigParse.Absent, "期望 Absent，实际 $result")
    }

    private fun dispatcher() = DeviceControlDispatcher(state, config(), object : DeviceControlActions {
        override suspend fun reboot() = Unit
        override suspend fun snapshot() = Unit
        override fun requestKeyFrame() = Unit
        override suspend fun triggerSnapshotConfig(cfg: SnapShotConfig) = Unit
        override fun startUpgrade(sessionId: String, firmware: String, fileUrl: String) = Unit
    })

    // ===================== A. 块表（唯一真源） =====================

    /**
     * ⛔⛔ 本族最贵的一条：**抓拍配置的下发块名与回读块名不一样**。
     *
     * 下发命令（A.2.3.2.12）与查询请求的 `ConfigType` 取值是 `SnapShotConfig`，
     * 而回读应答（A.2.6.9）的元素名是 **`SnapShot`**（`type="tg:snapshotCfgType"`）。
     * 回读时照下发块名写，对端**解不出来且不报错** —— 现象只是"有应答无数据"，
     * 而排障方向会被引到"平台解析有问题"上去。
     */
    @Test fun blockTable_snapShotHasDifferentResponseElementName() {
        assertEquals("SnapShotConfig", DeviceConfigBlock.SnapShot.configType, "查询请求 ConfigType / 下发块名")
        assertEquals("SnapShot", DeviceConfigBlock.SnapShot.responseElement, "A.2.6.9 回读应答的元素名")
        assertEquals(
            listOf("SnapShot"),
            DeviceConfigBlock.entries.filter { it.responseElement != it.configType }.map { it.responseElement },
            "只有抓拍配置这一处两者不同名；别处再分家等于又开一个同类雷",
        )
    }

    @Test fun blockTable_coversAllTenEmittableTypes() {
        // A.2.6.9 消息体里模拟器能回的 10 个块（12 种 ConfigType 减去两个未实现的 SVAC*）。
        assertEquals(10, DeviceConfigBlock.entries.size)
        assertEquals(setOf("SVACEncodeConfig", "SVACDecodeConfig"), DeviceConfigBlock.NOT_IMPLEMENTED)
    }

    /** 枚举顺序 = A.2.6.9 消息体里的元素出现顺序，`build()` 直接按它拼。 */
    @Test fun blockTable_orderFollowsA269() {
        assertEquals(
            listOf(
                "BasicParam", "VideoParamOpt", "VideoParamAttribute", "VideoRecordPlan",
                "VideoAlarmRecord", "PictureMask", "FrameMirror", "AlarmReport",
                "OSDConfig", "SnapShot",
            ),
            DeviceConfigBlock.entries.map { it.responseElement },
        )
    }

    @Test fun blockTable_byConfigTypeIsTrimmedAndCaseInsensitive() {
        assertEquals(DeviceConfigBlock.PictureMask, DeviceConfigBlock.byConfigType("picturemask"))
        assertEquals(DeviceConfigBlock.OsdConfig, DeviceConfigBlock.byConfigType("  OSDConfig "))
        assertEquals(DeviceConfigBlock.SnapShot, DeviceConfigBlock.byConfigType("SnapShotConfig"))
        assertNull(DeviceConfigBlock.byConfigType("SVACEncodeConfig"), "未实现的不在表里")
        assertNull(DeviceConfigBlock.byConfigType("Nope"))
    }

    @Test fun blockTable_onlyTwoTypesPredate2022() {
        assertEquals(
            listOf("BasicParam", "VideoParamOpt"),
            DeviceConfigBlock.entries.filter { it.isGb2016 }.map { it.configType },
            "2016 版可查询类型只有 4 种，其中本仓实现的是这两个",
        )
    }

    // ===================== B. 块定位（属性容忍 / 自闭区分） =====================

    /**
     * ⛔ 这一族里带属性的元素不止一个。写成 `indexOf("<Tag>")` 会**永不命中**，
     * 而现象是"设备回了 OK 但没数据"。
     */
    @Test fun configBlockBody_toleratesAttributes() {
        assertEquals(
            "X",
            configBlockBody("<VideoParamAttribute Num=\"1\">X</VideoParamAttribute>", "VideoParamAttribute"),
        )
        assertEquals("X", configBlockBody("<RegionList Num=\"2\">X</RegionList>", "RegionList"))
        assertEquals("X", configBlockBody("<PictureMask >X</PictureMask>", "PictureMask"))
    }

    /**
     * ⛔ 同前缀标签不能互相误命中 —— 本族里真的存在 `SnapShot` / `SnapShotConfig` 这一对。
     *
     * 不做边界检查时，找 `<SnapShot` 会**先命中** `<SnapShotConfig`，再一路去找 `</SnapShot>`
     * （它落在后面那个真 `SnapShot` 块上）—— 返回的是**两份块夹在一起的垃圾串**，
     * 而且任何一侧都不会报错。这是本条用例存在的唯一理由。
     */
    @Test fun configBlockBody_doesNotMatchLongerPrefixSibling() {
        val xml = "<SnapShotConfig>x</SnapShotConfig><SnapShot>y</SnapShot>"
        assertEquals("y", configBlockBody(xml, "SnapShot"))
        assertEquals("x", configBlockBody(xml, "SnapShotConfig"))
    }

    @Test fun isSelfClosedBlock_distinguishesAbsentFromEmpty() {
        assertTrue(isSelfClosedBlock("<PictureMask/>", "PictureMask"))
        assertTrue(isSelfClosedBlock("<PictureMask X=\"1\" />", "PictureMask"))
        assertFalse(isSelfClosedBlock("<PictureMask><On>1</On></PictureMask>", "PictureMask"))
        assertFalse(isSelfClosedBlock("<Control/>", "PictureMask"), "别的元素自闭不算")
        // ⛔ 同前缀：`SnapShotConfig` 自闭**不等于** `SnapShot` 自闭。不做边界检查就会认成 true，
        //    于是抓拍配置一被点就判"空下发"，而真因其实是找错了元素。
        assertFalse(isSelfClosedBlock("<SnapShotConfig/>", "SnapShot"))
    }

    /**
     * ⛔ 三态必须分开。合并之后「平台没配这一项」（正常）与「平台发了一条结构不成立的报文」
     * 会落进同一个分支 —— 现场就分不清是设备不支持、平台没发，还是平台发错了。
     */
    @Test fun absentOrEmptyBlock_separatesAbsentFromSelfClosed() {
        assertAbsent(absentOrEmptyBlock("<Control/>", "PictureMask"))
        assertTrue(rejected(absentOrEmptyBlock("<PictureMask/>", "PictureMask")).contains("PictureMask"))
    }

    /**
     * 自闭块要能走到 `Rejected` 的**前提是它被路由到** —— 否则 warn 永远不发生，
     * 而 `Rejected` 那条分支只是"单元测试里跑得通"的假覆盖。
     */
    @Test fun dispatcher_routesSelfClosedBlockWithoutChangingState() {
        dispatcher().dispatch("<Control><CmdType>DeviceControl</CmdType><PictureMask/></Control>")
        assertNull(state.value.lastCommand, "拒收 = 不动状态，也不写 lastCommand")
        assertNull(state.value.deviceConfigs.pictureMask)
    }

    // ===================== C. A.2.1.17 画面遮挡 =====================

    private fun maskCommand(body: String) =
        "<Control><CmdType>DeviceConfig</CmdType><PictureMask>$body</PictureMask></Control>"

    private fun maskReason(body: String) = rejected(PictureMaskConfig.parse(maskCommand(body)))

    /**
     * 零面积条目 = **删除这一槽**，不是"一条看不见的区域"。
     *
     * ⭐ 真机实证（2026-09-20，海康 DS-2DC2C040MY-DE）：平台为"被删掉的槽位"显式发
     * `<Point>0,0,0,0</Point>`，设备执行后**该条消失、且不回显**（发 3 真 + 1 零 ⇒ 回读
     * `SumNum=3`）。本仓跟着真机口径收 —— 收下就会把平台的一次"删除"变成"多出一条 0×0
     * 的幽灵区域"（回读 `Num` 变大、UI 列出一行 0×0），而平台侧会据此判对账不一致。
     *
     * ⛔ 反向锚点：有面积的条目一条都不能少（别写成"整块 regions 一律清空"）。
     */
    @Test fun pictureMask_treatsBlankRegionAsDeletion() {
        val state = accepted(
            PictureMaskConfig.parse(
                maskCommand(
                    "<On>1</On><SumNum>4</SumNum><RegionList Num=\"4\">" +
                        "<Item><Seq>1</Seq><Point>10,20,30,40</Point></Item>" +
                        "<Item><Seq>2</Seq><Point>0,0,0,0</Point></Item>" +
                        "<Item><Seq>3</Seq><Point>50,60,70,80</Point></Item>" +
                        "<Item><Seq>4</Seq><Point>100,100,200,200</Point></Item>" +
                        "</RegionList>"
                )
            )
        )
        assertEquals(listOf(1, 3, 4), state.regions.map { it.seq }, "零面积的槽位要被当成删除，不能留下来")
        assertEquals(3, state.sumNum, "sumNum 恒等于实际条数")
        // 抹平（右下角与左上角重合）与全零同义，都算删除。
        val flattened = accepted(
            PictureMaskConfig.parse(
                maskCommand(
                    "<On>1</On><SumNum>1</SumNum><RegionList Num=\"1\">" +
                        "<Item><Seq>2</Seq><Point>100,100,100,100</Point></Item>" +
                        "</RegionList>"
                )
            )
        )
        assertEquals(emptyList<Int>(), flattened.regions.map { it.seq }, "抹平的条目同样算删除")
        assertEquals(0, flattened.sumNum)
    }

    @Test fun pictureMask_acceptsTwoCornerPointNotXYWH() {
        // ⛔ Point 是「左上角 + 右下角」（lx,ly,rx,ry），不是 [x,y,w,h]。
        //    照海康 ISP 口径按 w/h 解，会画出**偏移一整个宽高**的假遮挡。
        val state = accepted(
            PictureMaskConfig.parse(
                maskCommand(
                    "<On>1</On><SumNum>2</SumNum><RegionList Num=\"2\">" +
                        "<Item><Seq>1</Seq><Point>20,30,50,60</Point></Item>" +
                        "<Item><Seq>2</Seq><Point>100,100,200,200</Point></Item>" +
                        "</RegionList>"
                )
            )
        )
        assertEquals(1, state.on)
        assertEquals(
            listOf(PictureMaskRegion(1, 20, 30, 50, 60), PictureMaskRegion(2, 100, 100, 200, 200)),
            state.regions,
        )
    }

    @Test fun pictureMask_rejectsInvertedRectangle() {
        // 倒置矩形在标准里没有定义的含义；收下来只会让遮挡层画出不可预期的形状，
        // 或被渲染层静默丢掉（"配了没效果"）。
        assertTrue(
            maskReason(
                "<On>1</On><SumNum>1</SumNum><RegionList Num=\"1\">" +
                    "<Item><Seq>1</Seq><Point>50,60,20,30</Point></Item></RegionList>"
            ).contains("Point")
        )
    }

    @Test fun pictureMask_rejectsEveryStructuralViolation() {
        assertTrue(maskReason("<SumNum>0</SumNum>").contains("On"), "① 缺 On")
        assertTrue(maskReason("<On>2</On><SumNum>0</SumNum>").contains("On=2"), "② On 越界")
        assertTrue(maskReason("<On>1</On>").contains("SumNum"), "③ 缺 SumNum（结构不完整要早暴露）")

        val fiveRegions = (1..5).joinToString("") { "<Item><Seq>$it</Seq><Point>0,0,1,1</Point></Item>" }
        assertTrue(
            maskReason("<On>1</On><SumNum>5</SumNum><RegionList>$fiveRegions</RegionList>")
                .contains("超过标准上限 4"),
            "④ 区域数 > 4",
        )
        assertTrue(
            maskReason(
                "<On>1</On><SumNum>1</SumNum><RegionList><Item><Seq>5</Seq><Point>0,0,1,1</Point></Item></RegionList>"
            ).contains("Seq=5 越界"),
            "⑤ Seq 越界（1~4）",
        )
        assertTrue(
            maskReason(
                "<On>1</On><SumNum>2</SumNum><RegionList>" +
                    "<Item><Seq>1</Seq><Point>0,0,1,1</Point></Item>" +
                    "<Item><Seq>1</Seq><Point>0,0,2,2</Point></Item></RegionList>"
            ).contains("重复"),
            "⑥ Seq 重复",
        )
        assertTrue(
            maskReason(
                "<On>1</On><SumNum>1</SumNum><RegionList><Item><Seq>1</Seq><Point>1,2,3</Point></Item></RegionList>"
            ).contains("Point"),
            "⑦ Point 段数不足",
        )
        assertTrue(
            maskReason(
                "<On>1</On><SumNum>1</SumNum><RegionList><Item><Seq>1</Seq><Point>-1,0,1,1</Point></Item></RegionList>"
            ).contains("Point"),
            "⑧ Point 有负数",
        )
    }

    /** `RegionList` 是可选元素：缺席 = 一个区域都没有，**合法**（不是"结构坏了"）。 */
    @Test fun pictureMask_regionListIsOptional() {
        val state = accepted(PictureMaskConfig.parse(maskCommand("<On>0</On><SumNum>0</SumNum>")))
        assertEquals(0, state.on)
        assertEquals(emptyList(), state.regions)
    }

    /** 块不在 → Absent（正常路径，不打日志）；自闭 → Rejected（要 warn）。 */
    @Test fun pictureMask_absentVsSelfClosed() {
        assertAbsent(PictureMaskConfig.parse("<Control><CmdType>DeviceConfig</CmdType></Control>"))
        assertTrue(rejected(PictureMaskConfig.parse(maskCommand(""))).contains("On"), "膨胀的空元素落在字段校验上")
        assertTrue(
            rejected(PictureMaskConfig.parse("<Control><CmdType>DeviceConfig</CmdType><PictureMask/></Control>"))
                .contains("元素在场但无内容"),
            "自闭形态要在更早一步就被判出来，而不是被当成 Absent 静默丢掉",
        )
    }

    /**
     * ⛔ `Num` 是 `RegionList` 的**属性**、`SumNum` 是子元素，两个计数都必须**取实到条数**，
     * 否则会出现"计数说 3 个、实际 2 个"的自相矛盾状态。
     */
    @Test fun pictureMask_renderCountsDeriveFromActualRegions() {
        val block = PictureMaskConfig.render(
            PictureMaskState(
                on = 1,
                regions = listOf(
                    PictureMaskRegion(1, 20, 30, 50, 60),
                    PictureMaskRegion(2, 100, 100, 200, 200),
                ),
            )
        )
        assertEquals(
            "<PictureMask>\n" +
                "<On>1</On>\n" +
                "<SumNum>2</SumNum>\n" +
                "<RegionList Num=\"2\">\n" +
                "<Item><Seq>1</Seq><Point>20,30,50,60</Point></Item>\n" +
                "<Item><Seq>2</Seq><Point>100,100,200,200</Point></Item>\n" +
                "</RegionList>\n" +
                "</PictureMask>\n",
            block,
        )
        assertFalse(block.contains("<Num>"), "Num 必须是属性: $block")
    }

    /** 自洽性守卫：自己渲染出来的块，自己必须一字不差地解回来。 */
    @Test fun pictureMask_renderThenParseRoundTrips() {
        val expected = PictureMaskState(
            on = 1,
            regions = listOf(PictureMaskRegion(1, 0, 0, 640, 480), PictureMaskRegion(3, 1, 2, 3, 4)),
        )
        assertEquals(expected, accepted(PictureMaskConfig.parse(maskCommand(PictureMaskConfig.render(expected)))))
    }

    // ===================== D. A.2.1.15 录像计划 =====================

    private fun planCommand(body: String) =
        "<Control><CmdType>DeviceConfig</CmdType><VideoRecordPlan>$body</VideoRecordPlan></Control>"

    private fun planReason(body: String) = rejected(VideoRecordPlanConfig.parse(planCommand(body)))

    private fun day(weekDay: Int, segments: String) =
        "<RecordSchedule><WeekDayNum>$weekDay</WeekDayNum>" +
            "<TimeSegmentSumNum>${segments.split("<TimeSegment>").size - 1}</TimeSegmentSumNum>" +
            segments + "</RecordSchedule>"

    private fun seg(sh: Int, sm: Int, ss: Int, eh: Int, em: Int, es: Int) =
        "<TimeSegment><StartHour>$sh</StartHour><StartMin>$sm</StartMin><StartSec>$ss</StartSec>" +
            "<StopHour>$eh</StopHour><StopMin>$em</StopMin><StopSec>$es</StopSec></TimeSegment>"

    @Test fun videoRecordPlan_parsesAndSortsDays() {
        val state = accepted(
            VideoRecordPlanConfig.parse(
                planCommand(
                    "<RecordEnable>1</RecordEnable><RecordScheduleSumNum>2</RecordScheduleSumNum>" +
                        day(3, seg(13, 0, 0, 18, 0, 0)) +
                        day(1, seg(8, 0, 0, 12, 0, 0)) +
                        "<StreamNumber>0</StreamNumber>"
                )
            )
        )
        assertEquals(1, state.recordEnable)
        assertEquals(0, state.streamNumber)
        assertEquals(listOf(1, 3), state.schedules.map { it.weekDayNum }, "按 WeekDayNum 升序落库")
        assertEquals(RecordTimeSegment(8, 0, 0, 12, 0, 0), state.schedules.first().segments.single())
        assertEquals(2, state.scheduleSumNum)
    }

    /** ⛔ XSD 顺序：`StreamNumber` 在 `RecordSchedule*` **之后**，不在里面。 */
    @Test fun videoRecordPlan_renderFollowsXsdOrderWithStreamNumberLast() {
        val block = VideoRecordPlanConfig.render(
            VideoRecordPlanState(
                recordEnable = 1,
                schedules = listOf(RecordSchedule(1, listOf(RecordTimeSegment(8, 0, 0, 12, 0, 0)))),
                streamNumber = 0,
            )
        )
        val atEnable = block.indexOf("<RecordEnable>")
        val atSum = block.indexOf("<RecordScheduleSumNum>")
        val atSchedule = block.indexOf("<RecordSchedule>")
        val atStream = block.indexOf("<StreamNumber>")
        assertTrue(atEnable in 0 until atSum, block)
        assertTrue(atSum < atSchedule, block)
        assertTrue(atSchedule < atStream, "StreamNumber 必须在最后: $block")
        assertTrue(block.contains("<TimeSegmentSumNum>1</TimeSegmentSumNum>"), block)
        assertTrue(block.endsWith("</VideoRecordPlan>\n"), block)
    }

    @Test fun videoRecordPlan_rejectsEveryStructuralViolation() {
        assertTrue(
            planReason("<RecordScheduleSumNum>0</RecordScheduleSumNum><StreamNumber>0</StreamNumber>")
                .contains("RecordEnable"),
            "① 缺 RecordEnable",
        )
        assertTrue(
            planReason(
                "<RecordEnable>2</RecordEnable><RecordScheduleSumNum>0</RecordScheduleSumNum><StreamNumber>0</StreamNumber>"
            ).contains("RecordEnable=2"),
            "② RecordEnable 只能 0/1",
        )
        assertTrue(
            planReason("<RecordEnable>1</RecordEnable><StreamNumber>0</StreamNumber>")
                .contains("RecordScheduleSumNum"),
            "③ 缺 RecordScheduleSumNum",
        )
        assertTrue(
            planReason(
                "<RecordEnable>1</RecordEnable><RecordScheduleSumNum>8</RecordScheduleSumNum>" +
                    (1..8).joinToString("") { day(it, seg(0, 0, 0, 1, 0, 0)) } + "<StreamNumber>0</StreamNumber>"
            ).contains("超过标准上限 7"),
            "④ 天数 > 7",
        )
        assertTrue(
            planReason(
                "<RecordEnable>1</RecordEnable><RecordScheduleSumNum>1</RecordScheduleSumNum>" +
                    day(8, seg(0, 0, 0, 1, 0, 0)) + "<StreamNumber>0</StreamNumber>"
            ).contains("WeekDayNum=8 越界"),
            "⑤ WeekDayNum 越界（1~7）",
        )
        assertTrue(
            planReason(
                "<RecordEnable>1</RecordEnable><RecordScheduleSumNum>2</RecordScheduleSumNum>" +
                    day(1, seg(0, 0, 0, 1, 0, 0)) + day(1, seg(2, 0, 0, 3, 0, 0)) +
                    "<StreamNumber>0</StreamNumber>"
            ).contains("重复出现"),
            "⑥ 同一天重复",
        )
        assertTrue(
            planReason(
                "<RecordEnable>1</RecordEnable><RecordScheduleSumNum>1</RecordScheduleSumNum>" +
                    day(1, seg(24, 0, 0, 25, 0, 0)) + "<StreamNumber>0</StreamNumber>"
            ).contains("StartHour=24 越界"),
            "⑦ 时越界（0~23）",
        )
        assertTrue(
            planReason(
                "<RecordEnable>1</RecordEnable><RecordScheduleSumNum>1</RecordScheduleSumNum>" +
                    day(1, (1..9).joinToString("") { seg(it, 0, 0, it, 30, 0) }) + "<StreamNumber>0</StreamNumber>"
            ).contains("超过标准上限 8"),
            "⑧ 单日时段 > 8",
        )
        assertTrue(
            planReason(
                "<RecordEnable>1</RecordEnable><RecordScheduleSumNum>1</RecordScheduleSumNum>" +
                    "<RecordSchedule><WeekDayNum>1</WeekDayNum>" + seg(8, 0, 0, 9, 0, 0) +
                    "</RecordSchedule><StreamNumber>0</StreamNumber>"
            ).contains("TimeSegmentSumNum"),
            "⑨ 缺 TimeSegmentSumNum",
        )
        assertTrue(
            planReason(
                "<RecordEnable>1</RecordEnable><RecordScheduleSumNum>1</RecordScheduleSumNum>" +
                    "<RecordSchedule><WeekDayNum>1</WeekDayNum><TimeSegmentSumNum>1</TimeSegmentSumNum>" +
                    "<TimeSegment><StartHour>8</StartHour><StartSec>0</StartSec>" +
                    "<StopHour>9</StopHour><StopMin>0</StopMin><StopSec>0</StopSec></TimeSegment>" +
                    "</RecordSchedule><StreamNumber>0</StreamNumber>"
            ).contains("StartMin"),
            "⑩ 时段缺必选字段",
        )
        assertTrue(
            planReason(
                "<RecordEnable>1</RecordEnable><RecordScheduleSumNum>1</RecordScheduleSumNum>" +
                    day(1, seg(8, 0, 0, 12, 0, 0))
            ).contains("StreamNumber"),
            "⑪ 缺 StreamNumber",
        )
    }

    /** `Stop < Start` 标准未定义语义 → **不解释、如实记账**，渲染原样回传。 */
    @Test fun videoRecordPlan_doesNotInterpretOvernightSegments() {
        val state = accepted(
            VideoRecordPlanConfig.parse(
                planCommand(
                    "<RecordEnable>1</RecordEnable><RecordScheduleSumNum>1</RecordScheduleSumNum>" +
                        day(1, seg(22, 0, 0, 6, 0, 0)) + "<StreamNumber>0</StreamNumber>"
                )
            )
        )
        assertEquals(RecordTimeSegment(22, 0, 0, 6, 0, 0), state.schedules.single().segments.single())
        assertTrue(
            VideoRecordPlanConfig.render(state).contains("<StopHour>6</StopHour>"),
            "设备侧不替平台解释跨零点",
        )
    }

    /**
     * ⛔ 用 `padStart` 而不是 `String.format` —— 后者是 **JVM 专属**，`commonMain` 里只有 iOS
     * 那条编译门禁抓得到（Android 目标编得过）。
     */
    @Test fun recordTimeSegment_displayLabelIsZeroPadded() {
        assertEquals("08:00:00-12:05:00", RecordTimeSegment(8, 0, 0, 12, 5, 0).displayLabel)
    }

    @Test fun videoRecordPlan_defaultIsNotEnabled() {
        val d = VideoRecordPlanConfig.defaultFor(config())
        assertEquals(0, d.recordEnable)
        assertEquals(emptyList(), d.schedules)
        assertEquals(0, d.streamNumber)
    }

    // ===================== E. A.2.1.23 画面翻转（simpleType） =====================

    private fun mirror(xml: String) = FrameMirrorConfig.parse(xml)

    @Test fun frameMirror_parsesPlainIntegerBody() {
        val state = accepted(mirror("<Control><CmdType>DeviceControl</CmdType><FrameMirror>2</FrameMirror></Control>"))
        assertEquals(2, state.value)
        // ⛔ 1 是水平、2 是上下（照标准 enumeration，不是海康口径）。
        assertEquals("上下镜像（上下翻转）", state.displayLabel)
    }

    /** ⛔ simpleType：元素体就是整数，**不产生任何子元素**。 */
    @Test fun frameMirror_renderIsBareIntegerElement() {
        assertEquals("<FrameMirror>3</FrameMirror>\n", FrameMirrorConfig.render(FrameMirrorState(3)))
        assertEquals("<FrameMirror>0</FrameMirror>\n", FrameMirrorConfig.render(FrameMirrorConfig.DEFAULT))
    }

    /**
     * ⛔ 越界值**一律拒绝落库**：值域是封闭 enumeration，落库一个 7 之后这台设备回读会
     * 永久吐非法应答，而现象指向"查询"而不是当初那条越界报文。
     */
    @Test fun frameMirror_rejectsOutOfRangeAndNonInteger() {
        assertTrue(rejected(mirror("<FrameMirror>4</FrameMirror>")).contains("越界"))
        assertTrue(rejected(mirror("<FrameMirror>-1</FrameMirror>")).contains("越界"))
        assertTrue(rejected(mirror("<FrameMirror>abc</FrameMirror>")).contains("不是整数"))
        assertTrue(rejected(mirror("<FrameMirror></FrameMirror>")).contains("元素为空"))
        // ⛔ 自闭形态按 Rejected（元素在场就说明平台确实点了这一项，只是没给值），**不是** Absent。
        assertTrue(rejected(mirror("<FrameMirror/>")).contains("元素在场但无内容"))
        assertAbsent(mirror("<Control><CmdType>DeviceControl</CmdType></Control>"))
    }

    @Test fun frameMirror_boundsAreTheEnumerationRange() {
        assertEquals(0, FrameMirrorConfig.MIN_VALUE)
        assertEquals(3, FrameMirrorConfig.MAX_VALUE)
        assertTrue(FrameMirrorConfig.isValid(2))
        assertFalse(FrameMirrorConfig.isValid(4))
    }

    // ===================== F. A.2.1.18 报警上报开关 =====================

    private fun alarmReportReason(body: String) =
        rejected(AlarmReportConfig.parse("<Control><AlarmReport>$body</AlarmReport></Control>"))

    @Test fun alarmReport_parsesAndRendersBothSwitches() {
        val state = accepted(
            AlarmReportConfig.parse(
                "<Control><CmdType>DeviceControl</CmdType><AlarmReport>" +
                    "<MotionDetection>0</MotionDetection><FieldDetection>1</FieldDetection>" +
                    "</AlarmReport></Control>"
            )
        )
        assertEquals(AlarmReportState(motionDetection = 0, fieldDetection = 1), state)
        // ⛔ 值为 0 的必选元素**不能省** —— 省掉之后对端解出零值，与"元素真的不在"分不开。
        val block = AlarmReportConfig.render(state)
        assertTrue(block.contains("<MotionDetection>0</MotionDetection>"), block)
        assertTrue(block.contains("<FieldDetection>1</FieldDetection>"), block)
    }

    /**
     * ⭐ 出厂默认照**设备真实行为**填：本模拟器确实会上报移动侦测报警，但没有区域入侵检测。
     * 两个都回 0 就是设备替平台编答案 —— 面板显示"报警上报已关闭"，而设备正在上报报警。
     */
    @Test fun alarmReport_defaultDerivesFromRealDeviceBehaviour() {
        assertEquals(1, AlarmReportConfig.DEFAULT.motionDetection)
        assertEquals(0, AlarmReportConfig.DEFAULT.fieldDetection)
    }

    @Test fun alarmReport_rejectsMissingOrOutOfRange() {
        assertTrue(alarmReportReason("<FieldDetection>1</FieldDetection>").contains("MotionDetection"))
        assertTrue(alarmReportReason("<MotionDetection>1</MotionDetection>").contains("FieldDetection"))
        assertTrue(
            alarmReportReason("<MotionDetection>3</MotionDetection><FieldDetection>0</FieldDetection>")
                .contains("MotionDetection=3")
        )
        assertTrue(
            alarmReportReason("<MotionDetection>1</MotionDetection><FieldDetection>-1</FieldDetection>")
                .contains("FieldDetection=-1")
        )
    }

    // ===================== G. A.2.1.16 报警录像 =====================

    private fun alarmRecord(body: String) =
        VideoAlarmRecordConfig.parse("<Control><VideoAlarmRecord>$body</VideoAlarmRecord></Control>")

    @Test fun videoAlarmRecord_optionalTimesDistinguishAbsentFromZero() {
        assertEquals(
            VideoAlarmRecordState(recordEnable = 1, recordTime = 30, preRecordTime = 5, streamNumber = 1),
            accepted(
                alarmRecord(
                    "<RecordEnable>1</RecordEnable><RecordTime>30</RecordTime>" +
                        "<PreRecordTime>5</PreRecordTime><StreamNumber>1</StreamNumber>"
                )
            ),
        )
        val withoutTimes = accepted(
            alarmRecord("<RecordEnable>1</RecordEnable><StreamNumber>0</StreamNumber>")
        )
        // ⛔ null（元素缺席）与 0（真的配了 0 秒 = 报警即停录）是两件事，不能合并。
        assertNull(withoutTimes.recordTime)
        assertNull(withoutTimes.preRecordTime)
    }

    @Test fun videoAlarmRecord_renderOmitsAbsentOptionalElements() {
        val block = VideoAlarmRecordConfig.render(VideoAlarmRecordState(recordEnable = 1, streamNumber = 0))
        assertFalse(block.contains("<RecordTime>"), "缺席 = 整个元素不出现: $block")
        assertFalse(block.contains("<PreRecordTime>"), block)
        assertFalse(block.contains("<RecordTime></RecordTime>"), "不能输出空元素")
        assertTrue(block.contains("<StreamNumber>0</StreamNumber>"), block)
    }

    @Test fun videoAlarmRecord_rejectsIllegalOptionalSeconds() {
        // 在场但不是整数 → 整块拒收（**不猜成 0**）。
        assertTrue(rejected(alarmRecord("<RecordEnable>1</RecordEnable><StreamNumber>0</StreamNumber><RecordTime>abc</RecordTime>")).contains("RecordTime"))
        // 超过本仓上限 → 同样拒收。
        assertTrue(rejected(alarmRecord("<RecordEnable>1</RecordEnable><StreamNumber>0</StreamNumber><PreRecordTime>99999</PreRecordTime>")).contains("PreRecordTime"))
        // 但**缺席**是正常路径。
        assertNull(accepted(alarmRecord("<RecordEnable>0</RecordEnable><StreamNumber>0</StreamNumber>")).recordTime)
    }

    @Test fun videoAlarmRecord_defaultStreamComesFromDeclaredList() {
        // 默认走主码流，但取值落在设备真的声明了的那几路里（不硬编码 0）。
        assertEquals(0, VideoAlarmRecordConfig.defaultFor(config(streamNumberList = "0/1")).streamNumber)
        assertEquals(2, VideoAlarmRecordConfig.defaultFor(config(streamNumberList = "2/3")).streamNumber)
    }

    // ===================== H. A.2.1.12 前端 OSD =====================

    private fun osdCommand(body: String) =
        "<Control><CmdType>DeviceControl</CmdType><OSDConfig>$body</OSDConfig></Control>"

    private fun osdReason(body: String) = rejected(FrontOsdConfig.parse(osdCommand(body)))

    /** 四个必选字段 + `SumNum` 的完整前缀，用于拼各类越界用例。 */
    private val osdHead = "<Length>1920</Length><Width>1080</Width><TimeX>10</TimeX><TimeY>10</TimeY>"

    @Test fun osdConfig_parsesPixelModelAndKeepsTimeTypeAbsent() {
        val state = accepted(
            FrontOsdConfig.parse(
                osdCommand(
                    "$osdHead<TimeEnable>1</TimeEnable><TextEnable>1</TextEnable><SumNum>1</SumNum>" +
                        "<Item><Text>通道1</Text><X>10</X><Y>34</Y></Item>"
                )
            )
        )
        assertEquals(1920, state.length)
        assertEquals(1080, state.width)
        assertEquals(listOf(OsdTextItem("通道1", 10, 34)), state.items)
        assertEquals(1, state.sumNum)
        // ⛔ TimeType 没有 XSD default ⇒ 缺席保持 null（"设备不指定格式"是真实状态，不是 0）。
        assertNull(state.timeType)
    }

    /** ⛔ `TimeEnable` / `TextEnable` 缺席要取 XSD 的 `default="1"`，不是 0。 */
    @Test fun osdConfig_switchesDefaultToOneWhenAbsent() {
        val state = accepted(FrontOsdConfig.parse(osdCommand("$osdHead<SumNum>0</SumNum>")))
        assertEquals(1, state.timeEnable)
        assertEquals(1, state.textEnable)
    }

    @Test fun osdConfig_renderOmitsTimeTypeWhenUnspecified() {
        val unspecified = FrontOsdConfig.render(
            FrontOsdState(length = 1920, width = 1080, timeX = 10, timeY = 10, timeType = null)
        )
        assertFalse(unspecified.contains("<TimeType>"), "缺席 = 整个元素不出现: $unspecified")
        assertTrue(
            FrontOsdConfig.render(
                FrontOsdState(length = 1920, width = 1080, timeX = 10, timeY = 10, timeType = 1)
            ).contains("<TimeType>1</TimeType>")
        )
    }

    @Test fun osdConfig_rejectsEveryStructuralViolation() {
        assertTrue(osdReason("<Width>1080</Width><TimeX>10</TimeX><TimeY>10</TimeY><SumNum>0</SumNum>").contains("Length"))
        assertTrue(osdReason("<Length>1920</Length><TimeX>10</TimeX><TimeY>10</TimeY><SumNum>0</SumNum>").contains("Width"))
        assertTrue(osdReason("<Length>1920</Length><Width>1080</Width><TimeY>10</TimeY><SumNum>0</SumNum>").contains("TimeX"))
        assertTrue(osdReason("<Length>1920</Length><Width>1080</Width><TimeX>10</TimeX><SumNum>0</SumNum>").contains("TimeY"))
        assertTrue(
            osdReason("<Length>0</Length><Width>1080</Width><TimeX>10</TimeX><TimeY>10</TimeY><SumNum>0</SumNum>")
                .contains("必须为正")
        )
        assertTrue(osdReason("$osdHead<SumNum>0</SumNum><TimeEnable>5</TimeEnable>").contains("TimeEnable"))
        assertTrue(osdReason("$osdHead<SumNum>0</SumNum><TextEnable>2</TextEnable>").contains("TextEnable"))
        assertTrue(osdReason("$osdHead<SumNum>0</SumNum><TimeType>9</TimeType>").contains("TimeType"))
        assertTrue(osdReason(osdHead).contains("SumNum"), "缺 SumNum")
        assertTrue(
            osdReason(
                "$osdHead<SumNum>9</SumNum>" +
                    (1..9).joinToString("") { "<Item><Text>t$it</Text><X>0</X><Y>0</Y></Item>" }
            ).contains("超过标准上限 8"),
            "文本行数 > 8",
        )
        assertTrue(
            osdReason("$osdHead<SumNum>1</SumNum><Item><Text>${"x".repeat(33)}</Text><X>0</X><Y>0</Y></Item>")
                .contains("超过标准上限 32"),
            "Text 长度 > 32",
        )
        assertTrue(osdReason("$osdHead<SumNum>1</SumNum><Item><X>0</X><Y>0</Y></Item>").contains("Text"))
        assertTrue(osdReason("$osdHead<SumNum>1</SumNum><Item><Text>t</Text><Y>0</Y></Item>").contains("X"))
        assertTrue(osdReason("$osdHead<SumNum>1</SumNum><Item><Text>t</Text><X>0</X></Item>").contains("Y"))
    }

    /**
     * 出厂默认 = 从本机三层 OSD **单向换算**：时间戳/通道名开关直通、通道名文本进 `Item[0]`、
     * `watermark` 丢弃（标准里没有"平铺水印"这个概念）、`Length`/`Width` 取当前生效分辨率。
     */
    @Test fun osdConfig_defaultDerivesFromLocalThreeLayerOsd() {
        val state = FrontOsdConfig.defaultFor(config())
        assertEquals(1920, state.length, "本仓口径：Length = 水平像素数")
        assertEquals(1080, state.width, "Width = 垂直像素数")
        assertEquals(1, state.timeEnable, "画布上真的在显示时间戳，回读报 0 就是编答案")
        assertEquals(1, state.textEnable)
        assertNull(state.timeType)
        assertEquals(1, state.sumNum)
        assertEquals("后置摄像头", state.items.single().text, "通道名层没填文字时退回设备名")
        assertEquals(FrontOsdConfig.DEFAULT_TEXT_X, state.items.single().x)
        assertEquals(FrontOsdConfig.DEFAULT_TEXT_Y, state.items.single().y)
    }

    @Test fun osdConfig_defaultDropsWatermarkAndEmptyChannelName() {
        val cfg = config().let {
            it.copy(
                osd = it.osd.copy(
                    channelName = it.osd.channelName.copy(text = "  ", enabled = true),
                    watermark = it.osd.watermark.copy(enabled = true, text = "水印"),
                ),
                device = it.device.copy(videoChannelName = ""),
            )
        }
        val state = FrontOsdConfig.defaultFor(cfg)
        // 通道名层开着但既没填文字、设备名也空 ⇒ 不发这条 Item（SumNum=0），但 TextEnable 保持 1。
        assertEquals(0, state.sumNum)
        assertEquals(1, state.textEnable)
        assertTrue(state.items.none { it.text == "水印" }, "标准里没有平铺水印这个概念，硬塞会造出语义不明的文本")
    }

    // ============ H-2. A.2.1.12 前端 OSD：回显 / 反投影 / 最近锚点 ============

    /**
     * ⭐ `effective` 是**界面回显的唯一入口**：平台下发过就用平台的，否则出厂派生。
     *
     * ⛔ 两件事都要成立：`null` 时能拿到出厂默认（不能显示空白），
     * 平台值在场时**原样返回**（不能"再派生一遍"—— 那会把平台填的坐标换掉）。
     */
    @Test fun frontOsdEffective_fallsBackToFactoryAndOtherwisePassesValueThrough() {
        val cfg = config()
        assertEquals(FrontOsdConfig.defaultFor(cfg), FrontOsdConfig.effective(cfg, null))

        val pushed = FrontOsdState(length = 640, width = 480, timeX = 1, timeY = 2)
        assertEquals(pushed, FrontOsdConfig.effective(cfg, pushed), "平台值在场时必须原样透出")
    }

    /**
     * ⭐ 绝对像素 → 5 个锚点里最近的一个（四角 + 居中）。
     *
     * ⛔ 5 个锚点**不是 3×3 网格**（没有"上中/下中/中左/中右"），所以这里比的是
     * 归一化后的欧氏距离，而不是"按行列阈值各切三档"。
     */
    @Test fun frontOsdNearestAnchor_picksTheNearestOfFive() {
        fun at(x: Int, y: Int) = FrontOsdConfig.nearestAnchor(x, y, frameWidth = 1920, frameHeight = 1080)
        assertEquals(OsdPosition.TOP_LEFT, at(0, 0))
        assertEquals(OsdPosition.TOP_LEFT, at(10, 10), "画布内边距处仍是左上")
        assertEquals(OsdPosition.TOP_RIGHT, at(1900, 10))
        assertEquals(OsdPosition.BOTTOM_LEFT, at(10, 1070))
        assertEquals(OsdPosition.BOTTOM_RIGHT, at(1900, 1070))
        assertEquals(OsdPosition.CENTER, at(960, 540))
        // "偏左上但不够靠边"—— 归一化比距离会给出确定且可解释的答案。
        assertEquals(OsdPosition.TOP_LEFT, at(300, 200))
    }

    /**
     * 距离并列时取枚举里靠前的那个 —— 结果**稳定**（[kotlin.collections.minBy] 的既定语义）。
     * 上边中点离左上/右上/居中三个锚点**等距**（0.25），必须有一个确定答案，不能靠实现偶然性。
     */
    @Test fun frontOsdNearestAnchor_resolvesTiesDeterministically() {
        assertEquals(
            OsdPosition.TOP_LEFT,
            FrontOsdConfig.nearestAnchor(960, 0, frameWidth = 1920, frameHeight = 1080),
        )
    }

    /** 窗口尺寸非法（0 / 负数）时不能除出 NaN/Infinity —— 兜底成 1 像素再归一化。 */
    @Test fun frontOsdNearestAnchor_neverDividesByZero() {
        assertEquals(OsdPosition.TOP_LEFT, FrontOsdConfig.nearestAnchor(0, 0, frameWidth = 0, frameHeight = 0))
        assertEquals(OsdPosition.TOP_LEFT, FrontOsdConfig.nearestAnchor(0, 0, frameWidth = -5, frameHeight = -5))
    }

    /**
     * ⭐ 反向投影：国标 `OSDConfig` → 本机三层 OSD，让平台配完 OSD 后**本机预览跟着变**
     * （真机上这就是"一份配置、两个入口"：摄像头自己的界面 + 平台 `OSDConfig`）。
     *
     * ⛔⛔ 同一个用例同时钉住**不该被覆盖**的那一维：字号与颜色。
     * 标准里没有这两维，编一个"默认值"就会盖掉用户在设置页里的选择，且没有任何协议依据。
     */
    @Test fun frontOsdToLocalOsd_mapsSlotsButPreservesSizeAndColors() {
        val base = OsdConfig(
            timestamp = OsdLayer(
                enabled = false, text = "", position = OsdPosition.CENTER,
                size = OsdSize.LARGE, fillColor = "#FF0000", outlineColor = "#00FF00",
            ),
            channelName = OsdLayer(
                enabled = true, text = "旧通道名", position = OsdPosition.BOTTOM_LEFT,
                size = OsdSize.SMALL, fillColor = "#111111", outlineColor = "#222222",
            ),
            watermark = OsdLayer(
                enabled = true, text = "旧水印", position = OsdPosition.BOTTOM_RIGHT,
                size = OsdSize.LARGE, fillColor = "#333333", outlineColor = "#444444",
            ),
        )
        val state = FrontOsdState(
            length = 1920, width = 1080,
            timeX = 1900, timeY = 1070, timeEnable = 1,
            textEnable = 1,
            items = listOf(OsdTextItem("通道A", 10, 10), OsdTextItem("水印B", 960, 540)),
        )

        val local = FrontOsdConfig.toLocalOsd(state, base)

        // 槽位 1：TimeX/Y → 时间戳层位置；TimeEnable → 开关
        assertTrue(local.timestamp.enabled)
        assertEquals(OsdPosition.BOTTOM_RIGHT, local.timestamp.position, "1900,1070 最近的是右下")
        // 槽位 2：Item[0] → 通道名层
        assertTrue(local.channelName.enabled)
        assertEquals("通道A", local.channelName.text)
        assertEquals(OsdPosition.TOP_LEFT, local.channelName.position)
        // 槽位 3：Item[1] → 水印层
        assertTrue(local.watermark.enabled)
        assertEquals("水印B", local.watermark.text)
        assertEquals(OsdPosition.CENTER, local.watermark.position)

        // ⛔ 本机有而标准没有的维度：一律取 base 原值
        assertEquals(OsdSize.LARGE, local.timestamp.size)
        assertEquals(OsdSize.SMALL, local.channelName.size)
        assertEquals(OsdSize.LARGE, local.watermark.size)
        assertEquals("#FF0000", local.timestamp.fillColor)
        assertEquals("#00FF00", local.timestamp.outlineColor)
        assertEquals("#111111", local.channelName.fillColor)
        assertEquals("#222222", local.channelName.outlineColor)
        assertEquals("#333333", local.watermark.fillColor)
        assertEquals("#444444", local.watermark.outlineColor)
    }

    /**
     * `TextEnable=1` 却**一条 Item 都没有** = "开着但没内容" ⇒ 两个文字层都置 false。
     * ⛔ 不能保持原 `enabled`：那会让预览继续显示上一轮的旧文字，让人以为平台配的就是它。
     */
    @Test fun frontOsdToLocalOsd_disablesTextLayersWhenEnabledButItemless() {
        val base = OsdConfig(
            channelName = OsdLayer(
                enabled = true, text = "旧通道名", position = OsdPosition.TOP_RIGHT,
                size = OsdSize.MEDIUM, fillColor = "#FFFFFF", outlineColor = "#000000",
            ),
        )
        val local = FrontOsdConfig.toLocalOsd(
            FrontOsdState(length = 1920, width = 1080, timeEnable = 0, textEnable = 1, items = emptyList()),
            base,
        )
        assertFalse(local.timestamp.enabled)
        assertFalse(local.channelName.enabled, "开着但没内容 ⇒ 不显示（免得继续显示旧文字）")
        assertFalse(local.watermark.enabled)
    }

    /**
     * ⛔ 第 3~8 条文字在本机预览里**没有对应层**，但**数据不能丢** ——
     * 回读照发全 8 条（见类注释的"已知有损点 2"）。
     *
     * 这条用例守的是"别用投影后的本机三层反推回读值"：一旦反推，
     * 第 3 条起的文字就会从回读里消失，而平台会读到一份"自己没发过"的配置。
     */
    @Test fun frontOsdToLocalOsd_keepsExtraItemsIntactForReadBack() {
        val state = FrontOsdState(
            length = 1920, width = 1080,
            timeEnable = 0, textEnable = 1,
            items = listOf(
                OsdTextItem("第一行", 10, 10),
                OsdTextItem("第二行", 10, 34),
                OsdTextItem("第三行", 10, 58),
            ),
        )
        val local = FrontOsdConfig.toLocalOsd(state, OsdConfig())

        // 本机只显示 2 行 —— 这是投影的损失，已知且被接受。
        assertEquals("第一行", local.channelName.text)
        assertEquals("第二行", local.watermark.text)
        // 回读侧仍按原始 state 发全 3 条。
        val readBack = FrontOsdConfig.render(state)
        assertEquals(3, state.sumNum)
        assertEquals(3, Regex("<Item>").findAll(readBack).count(), "回读照发全部条目：$readBack")
        assertTrue(readBack.contains("第三行"), "第 3 条只在协议侧存在，绝不因投影而丢：$readBack")
    }

    /** 平台把两个条目配到**同一锚点**时，本机两行字会落在同一处 —— 已知有损点 3，行为要确定。 */
    @Test fun frontOsdToLocalOsd_allowsBothTextLayersToLandOnSameAnchor() {
        val state = FrontOsdState(
            length = 1920, width = 1080,
            timeEnable = 0, textEnable = 1,
            items = listOf(OsdTextItem("A", 10, 10), OsdTextItem("B", 12, 14)),
        )
        val local = FrontOsdConfig.toLocalOsd(state, OsdConfig())
        assertEquals(local.channelName.position, local.watermark.position)
    }

    // ===================== I. A.2.1.19 基本参数（回读侧改正） =====================

    /**
     * ⛔⛔ `basicParamCfgType` **没有 `DeviceID`**（2016 与 2022 两版都是四个字段）。
     * 原实现把 `<DeviceID>` 塞在 `<BasicParam>` 里 —— 多了一个标准未定义的元素。
     * 目标设备编码的合法位置只有应答**顶层**那一个。
     */
    @Test fun basicParam_renderNeverEmitsDeviceIdInsideBlock() {
        // ⭐ 版本分支：2022 的 basicParamCfgType 只有这四个字段，正好用来钉"块内无 DeviceID"。
        //    2016 分支会多三个（PositionCapability/Longitude/Latitude），见单独的测试。
        val block = BasicParamConfig.render(
            BasicParamConfig.effective(config(), null),
            gbVersion = GbVersion.V2022,
            position = config().mockPosition,
        )
        assertFalse(block.contains("DeviceID"), "BasicParam 里不能有 DeviceID: $block")
        assertEquals(
            "<BasicParam>\n" +
                "<Name>我的设备</Name>\n" +
                "<Expiration>7200</Expiration>\n" +
                "<HeartBeatInterval>30</HeartBeatInterval>\n" +
                "<HeartBeatCount>5</HeartBeatCount>\n" +
                "</BasicParam>\n",
            block,
        )
    }

    /**
     * ⭐ **2016 的*回读*比 2022 多三个字段**（A.2.6 j)）：
     * `PositionCapability` / `Longitude` / `Latitude`。
     *
     * 这是本项目第三次遇到「同一 ConfigType，读/写门禁不同」：2016 下发侧只有那 4 个，
     * **回读应答**才带上这三个。所以三个字段是**设备能力的出口**，不属于 `BasicParamState`。
     */
    @Test fun basicParam_2016RenderAppendsPositionCapabilityAndCoordinates() {
        val block = BasicParamConfig.render(
            BasicParamConfig.effective(config(), null),
            gbVersion = GbVersion.V2016,
            position = config().mockPosition,
        )
        assertTrue(block.contains("<PositionCapability>1</PositionCapability>"), "2016 要报 GPS 能力: $block")
        assertTrue(block.contains("<Longitude>116.404000</Longitude>"), "actual: $block")
        assertTrue(block.contains("<Latitude>39.915000</Latitude>"), "actual: $block")
        // 顺序：四个配置项之后
        assertTrue(block.indexOf("<HeartBeatCount>") < block.indexOf("<PositionCapability>"))
    }

    /**
     * ⛔ 2022 分支**不得**发那三个：2022 的 `basicParamCfgType` 把它们删掉了，
     * 多发会让严格校验的 2022 对端判整条非法。这条是上一条的**反面对照**——
     * 只测 2016 会测不出"漏分支"，只测 2022 会测不出"多发"。
     */
    @Test fun basicParam_2022RenderDoesNotAppendTheThree2016OnlyFields() {
        val block = BasicParamConfig.render(
            BasicParamConfig.effective(config(), null),
            gbVersion = GbVersion.V2022,
            position = config().mockPosition,
        )
        assertFalse(block.contains("PositionCapability"), "2022 不得出现 PositionCapability: $block")
        assertFalse(block.contains("Longitude"), "2022 不得出现 Longitude: $block")
        assertFalse(block.contains("Latitude"), "2022 不得出现 Latitude: $block")
    }

    /**
     * `PositionCapability` 报的是**能力**，不是"此刻有没有定位"。
     * 模拟器走系统定位（WGS-84 系）⇒ 恒报 1。
     * ⛔ 若改成"没拿到 fix 就报 0"，平台会在设备尚未定位成功时**永远不去订阅位置**。
     */
    @Test fun basicParam_positionCapabilityIsCapabilityNotCurrentFix() {
        assertEquals(1, BasicParamConfig.POSITION_CAPABILITY_GPS)
        val block = BasicParamConfig.render(
            BasicParamConfig.effective(config(), null),
            gbVersion = GbVersion.V2016,
            position = config().mockPosition,
        )
        assertTrue(block.contains("<PositionCapability>1</PositionCapability>"))
    }

    private fun basicParam(body: String) = BasicParamConfig.parse("<Control><BasicParam>$body</BasicParam></Control>")

    @Test fun basicParam_parseTreatsAllFourFieldsAsOptional() {
        val partial = accepted(basicParam("<Name>甲</Name>"))
        assertEquals("甲", partial.name)
        assertNull(partial.expiration)
        assertFalse(partial.isEmpty)

        assertEquals(
            BasicParamState("甲", 60, 20, 4),
            accepted(
                basicParam(
                    "<Name>甲</Name><Expiration>60</Expiration>" +
                        "<HeartBeatInterval>20</HeartBeatInterval><HeartBeatCount>4</HeartBeatCount>"
                )
            ),
        )
    }

    /** 空下发（四项全缺）没有可落库的内容 —— 静默收下会让日志与状态都看不出这次下发做了什么。 */
    @Test fun basicParam_rejectsEmptyPayloadInBothSpellings() {
        assertTrue(rejected(basicParam("")).contains("四项全缺"))
        assertTrue(
            rejected(BasicParamConfig.parse("<Control><BasicParam/></Control>")).contains("元素在场但无内容")
        )
    }

    /** 可选字段的"缺席"与"在场但非法"必须分开：合并之后平台的报文错误会被静默吞掉。 */
    @Test fun basicParam_separatesIllegalFromAbsent() {
        assertTrue(rejected(basicParam("<Name>甲</Name><Expiration>abc</Expiration>")).contains("Expiration"))
        assertTrue(rejected(basicParam("<Name>甲</Name><Expiration>0</Expiration>")).contains("越界"))
        assertTrue(rejected(basicParam("<Name>甲</Name><HeartBeatCount>2000</HeartBeatCount>")).contains("越界"))
        // 但完全不带这些元素是正常的（minOccurs=0）
        assertEquals(45, accepted(basicParam("<HeartBeatInterval>45</HeartBeatInterval>")).heartBeatInterval)
    }

    /** ⭐ 回读的"出厂默认"不是编出来的常量，是设备此刻真实的运行参数（与注册/心跳同源）。 */
    @Test fun basicParam_effectiveFallsBackToLiveConfig() {
        val cfg = config()
        val fallback = BasicParamConfig.effective(cfg, null)
        assertEquals(cfg.device.name, fallback.name)
        assertEquals(cfg.expiresSeconds, fallback.expiration)
        assertEquals(cfg.keepaliveIntervalSeconds, fallback.heartBeatInterval)
        assertEquals(cfg.maxKeepaliveTimeouts, fallback.heartBeatCount)

        val overridden = BasicParamConfig.effective(
            cfg, BasicParamState(name = "改过", expiration = 120)
        )
        assertEquals("改过", overridden.name)
        assertEquals(120, overridden.expiration)
        assertEquals(cfg.keepaliveIntervalSeconds, overridden.heartBeatInterval, "没配的项逐字段退回本机值")
    }

    // ===================== J. A.2.1.24 图像抓拍（回读） =====================

    /**
     * ⛔ 回读块名必须是 `SnapShot`（A.2.6.9），**不是** `SnapShotConfig`。
     * 写成下发块名，对端解不出来且不报错 —— 现象只是"有应答无数据"。
     */
    @Test fun snapShotReport_renderUsesResponseElementName() {
        val block = SnapShotReport.render(
            SnapShotState(snapNum = 3, intervalSeconds = 2, uploadUrl = "http://p/up", sessionId = "s-1")
        )
        assertEquals(
            "<SnapShot>\n<SnapNum>3</SnapNum>\n<Interval>2</Interval>\n" +
                "<UploadURL>http://p/up</UploadURL>\n<SessionID>s-1</SessionID>\n</SnapShot>\n",
            block,
        )
        assertFalse(block.contains("SnapShotConfig"), "回读不能用下发块名: $block")
    }

    /** `Interval` 最小 1 秒；平台没给/给 0 时**整个元素不出现**（报 0 是对端判协议非法的现成材料）。 */
    @Test fun snapShotReport_omitsIntervalWhenAbsent() {
        val block = SnapShotReport.render(
            SnapShotState(snapNum = 1, intervalSeconds = null, uploadUrl = "http://p/up", sessionId = "s-1")
        )
        assertFalse(block.contains("<Interval>"), block)
    }

    /**
     * ⛔ 从未被配置过时**不回这一块** —— `UploadURL` / `SessionID` 是平台按会话下发的，
     * 设备侧没有"出厂默认上传地址"这种东西。硬造一条回给平台，比不回更危险。
     */
    @Test fun snapShotReport_snapshotOfIsNullWithoutPlatformPush() {
        assertNull(SnapShotReport.snapshotOf(null))
        assertEquals(
            SnapShotState(snapNum = 2, intervalSeconds = 1, uploadUrl = "http://p/up", sessionId = "s-1"),
            SnapShotReport.snapshotOf(
                SnapShotConfig(sessionId = "s-1", uploadUrl = "http://p/up", snapNum = 2, intervalMs = 1500L)
            ),
            "1500ms 向下取整到 1s（Interval 单位是秒且最小 1）",
        )
    }

    // ===================== K. A.2.6.9 整条回读应答 =====================

    private val deviceId = "34020000001320000001"

    private fun readBack(
        configTypes: List<String>,
        gbVersion: GbVersion = GbVersion.V2022,
        deviceConfigs: DeviceConfigState = DeviceConfigState(),
    ) = ConfigDownloadResponse.build(
        config(), sn = "9", requestedDeviceId = deviceId,
        configTypes = configTypes, gbVersion = gbVersion, deviceConfigs = deviceConfigs,
    )

    @Test fun readBack_emitsAllTenBlocksInA269Order() {
        val xml = readBack(
            DeviceConfigBlock.entries.map { it.configType } + listOf("SVACEncodeConfig"),
            deviceConfigs = DeviceConfigState(snapShot = SnapShotState(1, null, "http://p/up", "s-1")),
        )
        assertTrue(xml.contains("<Result>OK</Result>"))
        val positions = DeviceConfigBlock.entries.map { xml.indexOf("<${it.responseElement}") }
        assertTrue(positions.all { it >= 0 }, "十个块都要在: $xml")
        assertEquals(positions.sorted(), positions, "块顺序必须照 A.2.6.9 的消息体定义: $xml")
        assertTrue(xml.contains("<SnapShot>"), "回读块名是 SnapShot")
        assertFalse(xml.contains("<SnapShotConfig>"))
    }

    /** ⛔ 未实现的类型不回块、但**仍回 OK** —— 别改成 ERROR（那会把"不支持"报成"查询失败"）。 */
    @Test fun readBack_unimplementedTypesStillAnswerOk() {
        val xml = readBack(listOf("SVACEncodeConfig", "SVACDecodeConfig"))
        assertTrue(xml.contains("<Result>OK</Result>"))
        assertFalse(xml.contains("<SVACEncodeConfig>"))
        assertEquals(
            listOf("SVACEncodeConfig", "SVACDecodeConfig"),
            ConfigDownloadResponse.requestedButNotImplemented(listOf("SVACEncodeConfig", "SVACDecodeConfig")),
        )
    }

    /** 有效版本是 2016 时，2022 新增的块**整块不回**、仍回 OK —— 这正是真实 2016 设备的形态。 */
    @Test fun readBack_gatesEvery2022BlockOnEffectiveVersion() {
        val xml = readBack(DeviceConfigBlock.entries.map { it.configType }, gbVersion = GbVersion.V2016)
        assertTrue(xml.contains("<Result>OK</Result>"))
        assertTrue(xml.contains("<BasicParam>"))
        assertTrue(xml.contains("<VideoParamOpt>"))
        DeviceConfigBlock.entries.filter { !it.isGb2016 }.forEach {
            assertFalse(xml.contains("<${it.responseElement}"), "2016 有效版本不该回 ${it.configType}")
        }
    }

    /**
     * ⭐ `type_absent` 有三种原因、处置方式相反（换设备 / 切档位重注册 / 只是没人配过）。
     * 报文上三者同形，只能靠这两个清单把原因分开。
     */
    @Test fun readBack_separatesNotImplementedFromVersionGated() {
        val requested = listOf("PictureMask", "SVACEncodeConfig", "BasicParam")
        assertEquals(listOf("SVACEncodeConfig"), ConfigDownloadResponse.requestedButNotImplemented(requested))
        assertEquals(emptyList(), ConfigDownloadResponse.requestedButVersionGated(requested, GbVersion.V2022))
        assertEquals(listOf("PictureMask"), ConfigDownloadResponse.requestedButVersionGated(requested, GbVersion.V2016))
        assertTrue(
            ConfigDownloadResponse.requestedButNotImplemented(listOf("svacencodeconfig")).isNotEmpty(),
            "大小写不敏感",
        )
    }

    /**
     * ⛔ 抓拍配置是"回读永远有值"这条通则有意的**唯一例外**：从没配过就不回。
     * 其它类型必须回出厂默认，否则平台判 `type_absent`（"设备不支持该配置类型"）—— 假阴性。
     */
    @Test fun readBack_snapShotIsTheOnlyBlockThatMayStayAbsent() {
        val requested = DeviceConfigBlock.entries.map { it.configType }
        val neverConfigured = readBack(requested)
        assertFalse(neverConfigured.contains("<SnapShot>"), "从没配过就不回: $neverConfigured")
        listOf("PictureMask", "FrameMirror", "AlarmReport", "VideoRecordPlan", "VideoAlarmRecord", "OSDConfig")
            .forEach { assertTrue(neverConfigured.contains("<$it"), "其余类型必须回出厂默认: $it") }

        val configured = readBack(
            requested,
            deviceConfigs = DeviceConfigState(snapShot = SnapShotState(1, null, "u", "s")),
        )
        assertTrue(configured.contains("<SnapShot>"), configured)
    }

    /** 平台写入过的值必须**盖住**出厂默认（回读对账就是靠这一条）。 */
    @Test fun readBack_platformWrittenValuesWinOverFactoryDefaults() {
        val xml = readBack(
            listOf(
                "PictureMask", "FrameMirror", "AlarmReport", "VideoRecordPlan",
                "VideoAlarmRecord", "OSDConfig", "BasicParam",
            ),
            deviceConfigs = DeviceConfigState(
                basicParam = BasicParamState(name = "平台改名", expiration = 300),
                pictureMask = PictureMaskState(on = 1, regions = listOf(PictureMaskRegion(1, 10, 20, 30, 40))),
                frontOsd = FrontOsdState(length = 640, width = 480, timeX = 1, timeY = 2),
                frameMirror = FrameMirrorState(2),
                alarmReport = AlarmReportState(motionDetection = 0, fieldDetection = 1),
                videoRecordPlan = VideoRecordPlanState(
                    recordEnable = 1, schedules = listOf(RecordSchedule(2, emptyList())), streamNumber = 1,
                ),
                videoAlarmRecord = VideoAlarmRecordState(recordEnable = 1, recordTime = 30, streamNumber = 0),
            ),
        )
        assertTrue(xml.contains("<Name>平台改名</Name>"), xml)
        assertTrue(xml.contains("<On>1</On>") && xml.contains("<Point>10,20,30,40</Point>"), xml)
        assertTrue(xml.contains("<FrameMirror>2</FrameMirror>"), xml)
        assertTrue(xml.contains("<FieldDetection>1</FieldDetection>"), xml)
        assertTrue(xml.contains("<RecordEnable>1</RecordEnable>"), xml)
        assertTrue(xml.contains("<StreamNumber>1</StreamNumber>"), "录像计划取平台写的码流号: $xml")
        assertTrue(xml.contains("<TextEnable>1</TextEnable>"), "平台没下发的字段按 XSD default 落 1")
        assertFalse(xml.contains("<PreRecordTime>"), "预录时间缺席不输出: $xml")
    }

    /** 顶层 `DeviceID` 回的是**请求里的那个**（平台按通道编码查），块里不许再冒一个。 */
    @Test fun readBack_echoesRequestedDeviceIdAndKeepsDeviceIdOutOfBlocks() {
        val xml = readBack(listOf("BasicParam"))
        assertTrue(xml.contains("<DeviceID>$deviceId</DeviceID>"), xml)
        assertEquals(1, xml.split("<DeviceID>").size - 1, "BasicParam 里不能再冒一个 DeviceID: $xml")
    }
}
