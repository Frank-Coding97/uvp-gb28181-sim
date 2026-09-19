package com.uvp.sim.domain.coord.manscdp

import com.uvp.sim.domain.CruiseTrackState
import com.uvp.sim.domain.DeviceControlActions
import com.uvp.sim.domain.DeviceControlDispatcher
import com.uvp.sim.domain.VirtualStorageCards
import com.uvp.sim.gb28181.SnapShotConfig
import com.uvp.sim.gb28181.decodeSignalingTestBody
import com.uvp.sim.recording.NoopRecordingService
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.random.Random

/**
 * Wave 4 PR-D / P2-1:[DeviceControlSubRouter] 直接路径覆盖。
 *
 * 关注点:DeviceControl 路径含 DeviceControlDispatcher 派生 + Record/StopRecord 触发副作用 +
 * 5 个 Query 路径(Preset / PtzPrecise / HomePosition / StorageCard / CruiseTrack 2 个)
 * 各能出栈 MANSCDP Response。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeviceControlSubRouterTest {

    private object NoopActions : DeviceControlActions {
        override suspend fun reboot() {}
        override suspend fun snapshot() {}
        override fun requestKeyFrame() {}
        override suspend fun triggerSnapshotConfig(cfg: SnapShotConfig) {}
        override fun startUpgrade(sessionId: String, firmware: String, fileUrl: String) {}
    }

    @Test
    fun accepts_returns_true_for_owned_cmdTypes() = runTest {
        val f = SubRouterTestFixtures.newFixture(this)
        val dispatcher = DeviceControlDispatcher(f.deviceControlState, f.ctx.config, NoopActions, this)
        val r = DeviceControlSubRouter(
            ctx = f.ctx, recordingService = NoopRecordingService, dispatcher = dispatcher,
            alarmResetCallback = {},
        )

        assertTrue(r.accepts("DeviceControl"))
        assertTrue(r.accepts("PresetQuery"))
        assertTrue(r.accepts("PTZPreciseStatusQuery"))
        assertTrue(r.accepts("HomePositionQuery"))
        // 标准名优先;旧的自造名仍被接受(兼容),但不是主路径。
        assertTrue(r.accepts("SDCardStatus"))
        assertTrue(r.accepts("StorageCardStatusQuery"))
        assertTrue(r.accepts("CruiseTrackListQuery"))
        assertTrue(r.accepts("CruiseTrackQuery"))
        // ⭐ 2026-09-18：`DeviceConfig`(A.2.3.2 设备配置) 与 `DeviceControl`(A.2.3.1 控制)
        //    是**两个不同的 CmdType**。漏了它的后果不是"某条命令不生效"，而是整类配置
        //    下发被静默吞掉（见下面 deviceConfig_... 用例）。
        assertTrue(r.accepts("DeviceConfig"))

        assertFalse(r.accepts("Catalog"))
        assertFalse(r.accepts("Broadcast"))
        assertFalse(r.accepts("AlarmStatus"))
        assertFalse(r.accepts("ConfigDownload"), "ConfigDownload 是查询,归 CatalogSubRouter")
    }

    /**
     * ⭐ 2026-09-18 真机实测抓到的缺陷守卫。
     *
     * 平台下发设备配置用的是 **`DeviceConfig`**（A.2.3.2），而 [ACCEPTED] 里原先只有
     * `DeviceControl`（A.2.3.1 控制类）→ 整类配置下发在 `accepts` 就被挡掉，落成
     * 「未识别 MANSCDP cmdType=DeviceConfig，已回 200 但不会处理」。
     * ⛔ 200 是回了的，所以平台侧看不出异常、设备侧也只有一条 Warning，
     * 平台面板则永远停在 `never_read` —— 极易被误判成"平台根本没发"。
     */
    @Test
    fun deviceConfig_writeReachesDispatcherAndLandsInState() = runTest {
        val f = SubRouterTestFixtures.newFixture(this)
        val dispatcher = DeviceControlDispatcher(f.deviceControlState, f.ctx.config, NoopActions, this)
        val r = DeviceControlSubRouter(f.ctx, NoopRecordingService, dispatcher) {}

        // ⛔ `<VideoParamAttribute>` 带 `Num` 属性，分发是按块名 `contains("<VideoParamAttribute")`
        //    做的（不带收尾 `>`）—— 这里照平台实际下发的形态构造。
        val xml = "<Control><CmdType>DeviceConfig</CmdType><SN>772</SN>" +
            "<DeviceID>34020000001320000001</DeviceID>" +
            "<VideoParamAttribute Num=\"1\"><Item>" +
            "<StreamNumber>0</StreamNumber><VideoFormat>2</VideoFormat>" +
            "<Resolution>6</Resolution><FrameRate>30</FrameRate>" +
            "<BitRateType>2</BitRateType></Item></VideoParamAttribute></Control>"

        // ⭐ 真实链路 `ManscdpDispatcher.route` 是 `firstOrNull { it.accepts(cmd) } ?: return false`：
        //    accepts 是必经的第一道门，不过它 handle 根本不会被调用。
        //    所以这里必须一并断言 —— 否则本用例会在「accepts 漏了 DeviceConfig」时仍然全绿。
        assertTrue(
            r.accepts("DeviceConfig"),
            "DeviceConfig 必须被 accepts 放行,否则整类配置下发在入口就被静默吞掉",
        )
        assertTrue(r.handle("DeviceConfig", xml, fromUri = "sip:34020000002000000002@3402000000"))
        runCurrent()

        // ⭐ 光落状态不够：平台 `applyDeviceConfigResponse` 等着解析 `<CmdType>DeviceConfig</CmdType>`
        //    的 MANSCDP 应答，收不到就把该 operation 置 timeout，**并且不会创建回读对账子 operation**
        //    （`createVideoParamReconcile` 只在 ack 成功路径里调用）→ 现象是"设备改了、平台上永远是旧值"。
        //    ⛔ 原先 `DeviceConfig` 复用的 DeviceControl 处理路径，在发应答之前先取 `<RecordCmd>`，
        //    取不到就 `?: return` —— 配置类报文没有这个元素，于是**一个字节都不回**。
        //    PTZ 只靠 SIP 200 就够，所以这个洞一直没暴露。
        assertTrue(
            with(SubRouterTestFixtures) { f.transport.containsBody("<CmdType>DeviceConfig</CmdType>") },
            "必须回 DeviceConfig 的 MANSCDP 应答,否则平台 operation 超时且永远不会回读",
        )
        // ⭐ DeviceID 回**请求里的那个**（这里是通道编码），不是本机设备编码：平台用
        //    `ConfigDownloadExpectation{DeviceID: operationTargetCode(operation)}` 校验，
        //    回设备编码 ⇒ 应答被判为不属于本次操作而丢弃（与 ConfigDownload 同一个坑）。
        assertTrue(
            with(SubRouterTestFixtures) {
                f.transport.containsBody("<DeviceID>34020000001320000001</DeviceID>")
            },
            "应答 DeviceID 要回请求里的(通道编码),回设备编码会被平台丢弃",
        )
        assertFalse(
            with(SubRouterTestFixtures) {
                f.transport.containsBody("<DeviceID>34020000001110000001</DeviceID>")
            },
            "⛔ 不能回本机设备编码:平台按 operationTargetCode 校验,回错值 = 静默丢弃",
        )
        assertTrue(
            with(SubRouterTestFixtures) { f.transport.containsBody("<Result>OK</Result>") },
            "已识别的配置块必须回 Result=OK",
        )

        val params = f.deviceControlState.value.videoParams
        assertEquals(1, params.size, "平台下发的码流配置必须落进设备状态,否则回读还是旧值")
        val p = assertNotNull(params[0])
        assertEquals("6", p.resolution)
        assertEquals("30", p.frameRate)
        assertEquals("2", p.bitRateType)
        assertNull(p.videoBitRate, "VBR 时码率元素必须缺席,不能被补成 0")
    }

    @Test
    fun preset_query_emits_preset_response() = runTest {
        val f = SubRouterTestFixtures.newFixture(this)
        val dispatcher = DeviceControlDispatcher(f.deviceControlState, f.ctx.config, NoopActions, this)
        val r = DeviceControlSubRouter(f.ctx, NoopRecordingService, dispatcher) {}
        val xml = "<?xml version=\"1.0\"?><Query><CmdType>PresetQuery</CmdType><SN>1</SN>" +
            "<DeviceID>34020000001320000001</DeviceID></Query>"

        assertTrue(r.handle("PresetQuery", xml, fromUri = null))
        runCurrent()
        assertTrue(
            with(SubRouterTestFixtures) { f.transport.containsBody("<CmdType>PresetQuery</CmdType>") },
        )
    }

    /**
     * ⛔ 平台按标准发的 `CmdType` 是 **`PTZPosition`**（A.2.4.13 请求 / A.2.6.15 应答同名）。
     *
     * 这条用例存在的唯一理由就是钉住「标准名必须被受理」：原先只认自造名
     * `PTZPreciseStatusQuery`，平台按标准发查询会在 `accepts()` 就被挡掉 ——
     * 现象是「回了 200 但不处理」，两侧日志都看不出异常。
     */
    @Test
    fun ptz_position_query_isAccepted_andEmitsSixFields() = runTest {
        val f = SubRouterTestFixtures.newFixture(this)
        f.deviceControlState.value = f.deviceControlState.value.copy(panAngle = 45f, tiltAngle = 10f, zoomLevel = 2f)
        val dispatcher = DeviceControlDispatcher(f.deviceControlState, f.ctx.config, NoopActions, this)
        val r = DeviceControlSubRouter(f.ctx, NoopRecordingService, dispatcher) {}
        val xml = "<?xml version=\"1.0\"?><Query><CmdType>PTZPosition</CmdType><SN>2</SN>" +
            "<DeviceID>34020000001320000001</DeviceID></Query>"

        assertTrue(r.accepts("PTZPosition"), "标准 CmdType 必须被 accepts 受理")
        assertTrue(r.handle("PTZPosition", xml, fromUri = null))
        runCurrent()
        with(SubRouterTestFixtures) {
            assertTrue(f.transport.containsBody("<CmdType>PTZPosition</CmdType>"))
            // A.2.6.15 的六个字段（原先只发 Pan/Tilt/Zoom 三个）
            f.transport.containsBody("<Pan>45.00</Pan>")
            f.transport.containsBody("<Tilt>10.00</Tilt>")
            f.transport.containsBody("<Zoom>2.00</Zoom>")
            f.transport.containsBody("<HorizontalFieldAngle>")
            f.transport.containsBody("<VerticalFieldAngle>")
            f.transport.containsBody("<MaxViewDistance>")
        }
    }

    /**
     * ⛔⛔ **防两处门禁不同步的专项锚点。**
     *
     * `accepts()` 用的是一份独立的 `ACCEPTED` 集合，与 `handle()` 的 `when` **并列**。
     * 2026-09-19 修 G-3 时就踩了：`when` 加了 `"PTZPosition"`、`ACCEPTED` 没加，
     * 结果标准查询仍然在 `accepts()` 被挡掉 —— 现象与 `DeviceConfig` 那次完全一样
     * （回了 200 但不处理，两侧日志都不报错，只有平台面板停在 never_read）。
     *
     * 本用例把该路由**应有的** CmdType 名单全列一遍。往 `when` 里加 case 而忘了
     * `ACCEPTED`，这里必红。
     */
    @Test
    fun allCmdTypes_inTheHandleSwitchAreAlsoAccepted() = runTest {
        val f = SubRouterTestFixtures.newFixture(this)
        val dispatcher = DeviceControlDispatcher(f.deviceControlState, f.ctx.config, NoopActions, this)
        val r = DeviceControlSubRouter(f.ctx, NoopRecordingService, dispatcher) {}

        // 标准名（对应 handle 的 when 分支）
        val standard = listOf(
            "DeviceControl",          // A.2.3.1 控制类
            "DeviceConfig",           // A.2.3.2 设备配置类（2026-09-18 踩过同一坑）
            "PTZPosition",            // A.2.4.13 / A.2.6.15（本次 G-3）
            "PresetQuery",
            "HomePositionQuery",
            "SDCardStatus",           // A.2.4.14
            "CruiseTrackListQuery",
            "CruiseTrackQuery",
        )
        for (cmd in standard) {
            assertTrue(r.accepts(cmd), "标准 CmdType `$cmd` 必须被 accepts 受理")
        }

        // 历史自造名：只为兼容旧客户端保留，也必须受理（否则按旧写法实现的客户端整段失效）
        for (cmd in listOf("PTZPreciseStatusQuery", "StorageCardStatusQuery")) {
            assertTrue(r.accepts(cmd), "历史自造名 `$cmd` 仍需受理（兼容）")
        }
    }
    /** 历史自造名只为兼容保留，仍然要被受理（否则按旧写法实现的客户端会整段失效）。 */
    @Test
    fun ptz_precise_query_legacyNameStillHandled_butEmitsStandardCmdType() = runTest {
        val f = SubRouterTestFixtures.newFixture(this)
        val dispatcher = DeviceControlDispatcher(f.deviceControlState, f.ctx.config, NoopActions, this)
        val r = DeviceControlSubRouter(f.ctx, NoopRecordingService, dispatcher) {}
        val xml = "<?xml version=\"1.0\"?><Query><CmdType>PTZPreciseStatusQuery</CmdType><SN>2</SN>" +
            "<DeviceID>34020000001320000001</DeviceID></Query>"

        assertTrue(r.accepts("PTZPreciseStatusQuery"), "旧名仅作兼容，不能反过来把标准名挡掉")
        assertTrue(r.handle("PTZPreciseStatusQuery", xml, fromUri = null))
        runCurrent()
        with(SubRouterTestFixtures) {
            // ⭐ 受理旧名，但**应答按标准**发 PTZPosition —— 设备对外只有一种形态。
            assertTrue(f.transport.containsBody("<CmdType>PTZPosition</CmdType>"))
        }
    }

    /**
     * 平台从未下发过看守位时应答 "没配置"。
     *
     * 断言必须看到 **`<HomePosition>` 包裹层** —— 只查 `<CmdType>` 是分不出"带数据"和
     * "不带数据"的,而平台侧解析器找的正是这个子元素:平铺三字段(老实现)会被读成
     * `ResponseHasData=false`,设备上真实的看守位配置永远传不回平台。这条用例就是
     * 为了让那种退化必然变红。
     */
    @Test
    fun home_position_query_wraps_config_in_home_position_element() = runTest {
        val f = SubRouterTestFixtures.newFixture(this)
        // 默认 DeviceControlModel:homePositionPresetIndex == null(平台没配过),
        // 但 homePositionEnabled 默认是 true —— 老实现直接用它会回 "Enabled=1"。
        val dispatcher = DeviceControlDispatcher(f.deviceControlState, f.ctx.config, NoopActions, this)
        val r = DeviceControlSubRouter(f.ctx, NoopRecordingService, dispatcher) {}
        val xml = "<?xml version=\"1.0\"?><Query><CmdType>HomePositionQuery</CmdType><SN>3</SN>" +
            "<DeviceID>34020000001320000001</DeviceID></Query>"

        assertTrue(r.handle("HomePositionQuery", xml, fromUri = null))
        runCurrent()

        val body = f.transport.sent
            .map { it.body.decodeToString() }
            .single { it.contains("<CmdType>HomePositionQuery</CmdType>") }
        assertTrue(body.contains("<HomePosition>"), "缺 <HomePosition> 包裹层: $body")
        assertTrue(body.contains("</HomePosition>"), "缺 </HomePosition>: $body")
        assertTrue(body.contains("<Enabled>0</Enabled>"), "未配置时 Enabled 必须为 0,实际: $body")
        assertTrue(body.contains("<ResetTime>0</ResetTime>"), "未配置时 ResetTime 必须为 0,实际: $body")
        assertTrue(body.contains("<PresetIndex>0</PresetIndex>"), "未配置时 PresetIndex 必须为 0,实际: $body")
    }

    /** 平台下发过看守位后,查询应答必须回**真实**配置,不能是写死的 30 / 凭空造的 1。 */
    @Test
    fun home_position_query_echoes_the_configured_values() = runTest {
        val f = SubRouterTestFixtures.newFixture(this)
        f.deviceControlState.value = f.deviceControlState.value.copy(
            homePositionEnabled = true,
            homePositionPresetIndex = 3,
            homePositionResetTime = 20,
        )
        val dispatcher = DeviceControlDispatcher(f.deviceControlState, f.ctx.config, NoopActions, this)
        val r = DeviceControlSubRouter(f.ctx, NoopRecordingService, dispatcher) {}
        val xml = "<?xml version=\"1.0\"?><Query><CmdType>HomePositionQuery</CmdType><SN>5</SN>" +
            "<DeviceID>34020000001320000001</DeviceID></Query>"

        assertTrue(r.handle("HomePositionQuery", xml, fromUri = null))
        runCurrent()

        val body = f.transport.sent
            .map { it.body.decodeToString() }
            .single { it.contains("<CmdType>HomePositionQuery</CmdType>") }
        assertTrue(body.contains("<Enabled>1</Enabled>"), "实际: $body")
        assertTrue(body.contains("<ResetTime>20</ResetTime>"), "ResetTime 必须回真实值而非写死的 30: $body")
        assertTrue(body.contains("<PresetIndex>3</PresetIndex>"), "PresetIndex 必须回真实值而非凭空造的 1: $body")
    }

    @Test
    fun storage_card_status_query_emits_standard_elements() = runTest {
        val f = SubRouterTestFixtures.newFixture(this)
        val dispatcher = DeviceControlDispatcher(f.deviceControlState, f.ctx.config, NoopActions, this)
        val r = DeviceControlSubRouter(
            f.ctx, NoopRecordingService, dispatcher, storageCards = VirtualStorageCards(Random(7)),
        ) {}
        val xml = "<?xml version=\"1.0\"?><Query><CmdType>SDCardStatus</CmdType><SN>4</SN>" +
            "<DeviceID>34020000001110000001</DeviceID></Query>"

        assertTrue(r.handle("SDCardStatus", xml, fromUri = null))
        runCurrent()
        val body = f.transport.sent
            .map { it.body.decodeSignalingTestBody() }
            .single { it.contains("<SumNum>") }

        // ⛔ CmdType 必须是标准的 `SDCardStatus`(A.2.4.14/A.2.6.16 请求应答同名)。
        assertTrue(body.contains("<CmdType>SDCardStatus</CmdType>"), "实际: $body")
        // ⛔ 旧骨架的四处不合标准之处,一个都不许留。
        listOf("StorageCardStatusQuery", "StorageList", "CardNum", "TotalCapacity", "RemainingSpace", "<Status>Normal</Status>")
            .forEach { assertFalse(body.contains(it), "非标准片段 $it 不该再出现: $body") }
        assertTrue(body.contains("<SN>4</SN>"), "SN 必须原样回: $body")
        assertTrue(body.contains("<DeviceID>34020000001110000001</DeviceID>"), "实际: $body")
    }

    @Test
    fun storage_card_status_sum_num_always_matches_item_count() = runTest {
        // 随机假数据下最要紧的不变量:SumNum 与实际 Item 条数必须一致。
        // 平台是按 A.2.6.16 的 SumNum 判"结果总数"的;不一致会让它一直在等更多应答。
        for (seed in 1..40) {
            val f = SubRouterTestFixtures.newFixture(this)
            val dispatcher = DeviceControlDispatcher(f.deviceControlState, f.ctx.config, NoopActions, this)
            val r = DeviceControlSubRouter(
                f.ctx, NoopRecordingService, dispatcher, storageCards = VirtualStorageCards(Random(seed)),
            ) {}
            val xml = "<?xml version=\"1.0\"?><Query><CmdType>SDCardStatus</CmdType><SN>1</SN>" +
                "<DeviceID>34020000001320000001</DeviceID></Query>"
            assertTrue(r.handle("SDCardStatus", xml, fromUri = null))
            runCurrent()

            val body = f.transport.sent
                .map { it.body.decodeSignalingTestBody() }
                .single { it.contains("<SumNum>") }
            val sumNum = Regex("<SumNum>(\\d+)</SumNum>").find(body)!!.groupValues[1].toInt()
            val itemCount = Regex("<Item>").findAll(body).count()
            assertEquals(sumNum, itemCount, "seed=$seed 时 SumNum 与 Item 数不符: $body")
            assertEquals(sumNum == 0, !body.contains("<SDCardStatusInfo>"), "0 张卡时不能带 SDCardStatusInfo: $body")
            // 每张卡的五项必选字段齐全,且 FreeSpace 不该超过 Capacity。
            Regex("<Item>(.*?)</Item>", RegexOption.DOT_MATCHES_ALL).findAll(body).forEach { match ->
                val item = match.groupValues[1]
                listOf("ID", "HddName", "Status", "Capacity", "FreeSpace").forEach { field ->
                    assertTrue(item.contains("<$field>"), "seed=$seed 缺 $field: $item")
                }
                val capacity = Regex("<Capacity>(\\d+)</Capacity>").find(item)!!.groupValues[1].toInt()
                val free = Regex("<FreeSpace>(\\d+)</FreeSpace>").find(item)!!.groupValues[1].toInt()
                assertTrue(free in 0..capacity, "seed=$seed 剩余空间越界: $item")
            }
        }
    }

    @Test
    fun storage_card_status_identity_is_stable_across_queries() = runTest {
        // 同一个 router 实例连查两次:张数与容量必须一致。
        // 否则平台每点一次"刷新"就换一套卡,排障时分不清是设备在变还是模拟器在掷骰子。
        val f = SubRouterTestFixtures.newFixture(this)
        val dispatcher = DeviceControlDispatcher(f.deviceControlState, f.ctx.config, NoopActions, this)
        val r = DeviceControlSubRouter(
            f.ctx, NoopRecordingService, dispatcher, storageCards = VirtualStorageCards(Random(11)),
        ) {}
        val xml = "<?xml version=\"1.0\"?><Query><CmdType>SDCardStatus</CmdType><SN>1</SN>" +
            "<DeviceID>34020000001320000001</DeviceID></Query>"

        assertTrue(r.handle("SDCardStatus", xml, fromUri = null))
        runCurrent()
        val first = f.transport.sent.map { it.body.decodeSignalingTestBody() }.last()
        assertTrue(r.handle("SDCardStatus", xml, fromUri = null))
        runCurrent()
        val second = f.transport.sent.map { it.body.decodeSignalingTestBody() }.last()

        assertEquals(
            Regex("<Capacity>\\d+</Capacity>").findAll(first).map { it.value }.toList(),
            Regex("<Capacity>\\d+</Capacity>").findAll(second).map { it.value }.toList(),
            "容量是物理属性,两次查询必须一致",
        )
        assertEquals(
            Regex("<HddName>[^<]*</HddName>").findAll(first).map { it.value }.toList(),
            Regex("<HddName>[^<]*</HddName>").findAll(second).map { it.value }.toList(),
            "卡名同样必须一致",
        )
    }

    @Test
    fun storage_card_status_accepts_legacy_command_name_but_answers_with_standard_one() = runTest {
        // 旧名只是兼容入口:应答的 CmdType 仍然是标准的 SDCardStatus,
        // 否则按旧写法实现的客户端会一直听到自己的回声。
        val f = SubRouterTestFixtures.newFixture(this)
        val dispatcher = DeviceControlDispatcher(f.deviceControlState, f.ctx.config, NoopActions, this)
        val r = DeviceControlSubRouter(
            f.ctx, NoopRecordingService, dispatcher, storageCards = VirtualStorageCards(Random(3)),
        ) {}
        val xml = "<?xml version=\"1.0\"?><Query><CmdType>StorageCardStatusQuery</CmdType><SN>9</SN>" +
            "<DeviceID>34020000001320000001</DeviceID></Query>"

        assertTrue(r.handle("StorageCardStatusQuery", xml, fromUri = null))
        runCurrent()
        val body = f.transport.sent
            .map { it.body.decodeSignalingTestBody() }
            .single { it.contains("<SumNum>") }
        assertTrue(body.contains("<CmdType>SDCardStatus</CmdType>"), "实际: $body")
        assertTrue(body.contains("<SN>9</SN>"), "实际: $body")
    }

    /**
     * 报文里的读数必须与写进 [DeviceControlModel] 的读数**逐字段相同**。
     *
     * 这条锁的是「单一真源」:模拟中心的卡片读 Model,平台读报文,两边来自**同一次**
     * `VirtualStorageCards.read()`。若哪天有人在报文路径或 UI 路径上又调了一次 `read()`,
     * 设备屏幕与平台就会显示两组不同的容量/剩余 —— 而两边**各自都看着正常**,
     * 只有把设备屏幕和平台页面摆在一起才会发现。
     */
    @Test
    fun storage_card_query_publishes_the_same_reading_it_reports() = runTest {
        val f = SubRouterTestFixtures.newFixture(this)
        val dispatcher = DeviceControlDispatcher(f.deviceControlState, f.ctx.config, NoopActions, this)
        val r = DeviceControlSubRouter(
            f.ctx, NoopRecordingService, dispatcher, storageCards = VirtualStorageCards(Random(5)),
        ) {}
        val xml = "<?xml version=\"1.0\"?><Query><CmdType>SDCardStatus</CmdType><SN>21</SN>" +
            "<DeviceID>34020000001320000001</DeviceID></Query>"

        assertTrue(r.handle("SDCardStatus", xml, fromUri = null))
        runCurrent()

        val body = f.transport.sent
            .map { it.body.decodeSignalingTestBody() }
            .single { it.contains("<SumNum>") }
        val model = f.deviceControlState.value

        assertEquals(1, model.storageCardQueryCount, "一次查询只该 +1")
        assertNotNull(model.storageCardQueriedAtMs, "查询时间戳必须落库(卡片要显示「最近查询」)")
        // 「卡片清单 ↔ 读数」必须按卡号一一对上:少一条就会出现"卡在但读数为空"的卡片。
        assertEquals(
            model.storageCards.map { it.id }.sorted(),
            model.storageCardReadings.keys.sorted(),
            "每张卡都该有一条读数",
        )

        val items = Regex("<Item>(.*?)</Item>", RegexOption.DOT_MATCHES_ALL)
            .findAll(body).map { it.groupValues[1] }.toList()
        assertEquals(model.storageCards.size, items.size, "清单与报文 Item 数必须一致: $body")

        model.storageCards.zip(items).forEach { (card, item) ->
            val reading = model.storageCardReadings.getValue(card.id)
            assertTrue(item.contains("<ID>${card.id}</ID>"), "实际: $item")
            assertTrue(item.contains("<HddName>${card.name}</HddName>"), "实际: $item")
            assertTrue(item.contains("<Capacity>${card.capacityMb}</Capacity>"), "实际: $item")
            assertTrue(item.contains("<Status>${reading.status.wireValue}</Status>"), "实际: $item")
            assertTrue(item.contains("<FreeSpace>${reading.freeMb}</FreeSpace>"), "实际: $item")
            // FormatProgress 的语义必须在报文与 Model 两边一致:
            // 有值则报文体里必须有,没值则**一律不许出现**(补 0 会被平台读成"正在格式化 0%")。
            if (reading.progress == null) {
                assertFalse(item.contains("FormatProgress"), "非 formatting 态不该带进度: $item")
            } else {
                assertTrue(
                    item.contains("<FormatProgress>${reading.progress}</FormatProgress>"),
                    "格式化的进度必须报出来: $item",
                )
            }
        }
    }

    /**
     * 存储卡查询**不得**碰 `lastCommand`。
     *
     * `lastCommand.timestampMs` 同时是看守位的「云台活动信号」——`SimulatorEngine` 的
     * `CameraActivity(model.lastCommand?.timestampMs, …)` 用它清零空闲倒计时。
     * 查询若写进去,现象是「平台在查存储卡,看守位就不自动归位了」,而且日志上看不出因果:
     * 设备侧只看到一条合法命令,平台侧只看到一次成功查询。
     */
    @Test
    fun storage_card_query_does_not_touch_last_command_activity_signal() = runTest {
        val f = SubRouterTestFixtures.newFixture(this)
        val dispatcher = DeviceControlDispatcher(f.deviceControlState, f.ctx.config, NoopActions, this)
        val r = DeviceControlSubRouter(
            f.ctx, NoopRecordingService, dispatcher, storageCards = VirtualStorageCards(Random(5)),
        ) {}
        val xml = "<?xml version=\"1.0\"?><Query><CmdType>SDCardStatus</CmdType><SN>22</SN>" +
            "<DeviceID>34020000001320000001</DeviceID></Query>"
        assertNull(f.deviceControlState.value.lastCommand, "前置:默认 Model 没有命令")

        assertTrue(r.handle("SDCardStatus", xml, fromUri = null))
        runCurrent()

        assertNull(
            f.deviceControlState.value.lastCommand,
            "查询不是云台操作,不该动看守位的活动信号",
        )
    }

    /**
     * 连查两次 → `storageCardQueryCount` 递增到 2。
     *
     * 这是模拟中心卡片「亮起」的**触发键**:UI 用 `LaunchedEffect(count)` 重播动效。
     * 若改成用时间戳当键,同一毫秒内的两次查询键不变 → 操作员第二次点查询设备屏幕没反应。
     */
    @Test
    fun storage_card_query_count_increments_on_every_query() = runTest {
        val f = SubRouterTestFixtures.newFixture(this)
        val dispatcher = DeviceControlDispatcher(f.deviceControlState, f.ctx.config, NoopActions, this)
        val r = DeviceControlSubRouter(
            f.ctx, NoopRecordingService, dispatcher, storageCards = VirtualStorageCards(Random(5)),
        ) {}
        fun query(sn: Int) = "<?xml version=\"1.0\"?><Query><CmdType>SDCardStatus</CmdType><SN>$sn</SN>" +
            "<DeviceID>34020000001320000001</DeviceID></Query>"

        assertTrue(r.handle("SDCardStatus", query(31), fromUri = null))
        runCurrent()
        assertEquals(1, f.deviceControlState.value.storageCardQueryCount)
        assertTrue(r.handle("SDCardStatus", query(32), fromUri = null))
        runCurrent()
        assertEquals(2, f.deviceControlState.value.storageCardQueryCount)

        // 两张卡的**物理属性**在整个过程里必须稳定(读数可以抖)。
        val cards = f.deviceControlState.value.storageCards
        assertEquals(cards.map { it.id }, cards.map { it.id }.distinct().sorted(), "卡号不该重复")
        assertEquals(2, f.transport.sent.count { it.body.decodeSignalingTestBody().contains("<SumNum>") })
    }

    @Test
    fun cruise_track_list_query_emits_standard_elements() = runTest {
        val f = SubRouterTestFixtures.newFixture(this)
        f.deviceControlState.value = f.deviceControlState.value.copy(
            cruiseTracks = mapOf(
                1 to CruiseTrackState(points = listOf(1, 3, 5), speed = 300, dwellTime = 45),
                2 to CruiseTrackState(points = listOf(2, 4)),
            ),
        )
        val dispatcher = DeviceControlDispatcher(f.deviceControlState, f.ctx.config, NoopActions, this)
        val r = DeviceControlSubRouter(f.ctx, NoopRecordingService, dispatcher) {}
        val xml = "<?xml version=\"1.0\"?><Query><CmdType>CruiseTrackListQuery</CmdType><SN>5</SN>" +
            "<DeviceID>34020000001320000001</DeviceID></Query>"

        assertTrue(r.handle("CruiseTrackListQuery", xml, fromUri = null))
        runCurrent()
        val body = f.transport.sent.last().body.decodeSignalingTestBody()
        // ⛔ 元素名必须是 2022 的 `CruiseTrackList`/`CruiseTrack`/`Number`。
        //    原来写的是 `<TrackList><Item><GroupID>`(2016 版 PresetQuery 的惯例),
        //    平台按标准解析 → "0 条轨迹",且 `0 >= SumNum(2)` 恒假 →
        //    operation 永不 finalize,前端巡航卡片永远停在"未验证"。
        assertTrue(body.contains("<CmdType>CruiseTrackListQuery</CmdType>"))
        assertTrue(body.contains("<SumNum>2</SumNum>"))
        assertTrue(body.contains("<CruiseTrackList Num=\"2\">"))
        assertTrue(body.contains("<CruiseTrack><Number>1</Number><Name>巡航 1</Name></CruiseTrack>"))
        assertTrue(body.contains("<CruiseTrack><Number>2</Number><Name>巡航 2</Name></CruiseTrack>"))
        assertFalse(body.contains("<Item>"), "不能再用 2016 PresetQuery 风格的 Item: $body")
        assertFalse(body.contains("GroupID"), "轨迹编号元素是 Number,不是 GroupID: $body")
        assertFalse(body.contains("<TrackList"), "列表元素是 CruiseTrackList: $body")
    }

    @Test
    fun cruise_track_query_uses_number_and_emits_points() = runTest {
        val f = SubRouterTestFixtures.newFixture(this)
        // 速度 300 是**故意**超过 255 的:锁住"12 位参数没被截断"这件事(见 PtzCmdDecoderTest)
        f.deviceControlState.value = f.deviceControlState.value.copy(
            cruiseTracks = mapOf(2 to CruiseTrackState(points = listOf(4, 6), speed = 300, dwellTime = 45)),
        )
        val dispatcher = DeviceControlDispatcher(f.deviceControlState, f.ctx.config, NoopActions, this)
        val r = DeviceControlSubRouter(f.ctx, NoopRecordingService, dispatcher) {}
        // 平台按标准发的是 `<Number>`(附录 A.2.4.12);老客户端可能发 GroupID,一样要认
        val xml = "<?xml version=\"1.0\"?><Query><CmdType>CruiseTrackQuery</CmdType><SN>6</SN>" +
            "<DeviceID>34020000001320000001</DeviceID><Number>2</Number></Query>"

        assertTrue(r.handle("CruiseTrackQuery", xml, fromUri = null))
        runCurrent()
        val body = f.transport.sent.last().body.decodeSignalingTestBody()
        assertTrue(body.contains("<CmdType>CruiseTrackQuery</CmdType>"))
        assertTrue(body.contains("<Number>2</Number>"))
        assertTrue(body.contains("<Name>巡航 2</Name>"))
        assertTrue(body.contains("<SumNum>2</SumNum>"))
        assertTrue(body.contains("<CruisePointList Num=\"2\">"))
        assertTrue(
            body.contains(
                "<CruisePoint><PresetIndex>4</PresetIndex><StayTime>45</StayTime><Speed>300</Speed></CruisePoint>"
            ),
            body,
        )
        assertTrue(
            body.contains(
                "<CruisePoint><PresetIndex>6</PresetIndex><StayTime>45</StayTime><Speed>300</Speed></CruisePoint>"
            ),
            body,
        )
        assertFalse(body.contains("PresetID"), "点位编号元素是 PresetIndex: $body")
        assertFalse(body.contains("DwellTime"), "停留元素是 StayTime: $body")
        assertFalse(body.contains("<PresetList"), "点位集合元素是 CruisePointList: $body")
    }

    @Test
    fun cruise_track_query_accepts_legacy_group_id_element() = runTest {
        val f = SubRouterTestFixtures.newFixture(this)
        f.deviceControlState.value = f.deviceControlState.value.copy(
            cruiseTracks = mapOf(7 to CruiseTrackState(points = listOf(1))),
        )
        val dispatcher = DeviceControlDispatcher(f.deviceControlState, f.ctx.config, NoopActions, this)
        val r = DeviceControlSubRouter(f.ctx, NoopRecordingService, dispatcher) {}
        val xml = "<?xml version=\"1.0\"?><Query><CmdType>CruiseTrackQuery</CmdType><SN>7</SN>" +
            "<DeviceID>34020000001320000001</DeviceID><GroupID>7</GroupID></Query>"

        assertTrue(r.handle("CruiseTrackQuery", xml, fromUri = null))
        runCurrent()
        val body = f.transport.sent.last().body.decodeToString()
        assertTrue(body.contains("<Number>7</Number>"), body)
        assertTrue(body.contains("<PresetIndex>1</PresetIndex>"), body)
    }

    /**
     * 平台没通过控制层 `0x86`/`0x87` 下发过速度/停留时间时,回**设备出厂值**。
     *
     * 应答里 `Speed`/`StayTime` 是必填元素,不能省略也不能回 0 —— 回 0 会被平台判成
     * "巡航点参数不合法"而整条丢掉。所以这里既钉住默认值,也钉住"必须有元素"。
     */
    @Test
    fun cruise_track_query_falls_back_to_device_defaults() = runTest {
        val f = SubRouterTestFixtures.newFixture(this)
        f.deviceControlState.value = f.deviceControlState.value.copy(
            cruiseTracks = mapOf(1 to CruiseTrackState(points = listOf(1))),
        )
        val dispatcher = DeviceControlDispatcher(f.deviceControlState, f.ctx.config, NoopActions, this)
        val r = DeviceControlSubRouter(f.ctx, NoopRecordingService, dispatcher) {}
        val xml = "<?xml version=\"1.0\"?><Query><CmdType>CruiseTrackQuery</CmdType><SN>8</SN>" +
            "<DeviceID>34020000001320000001</DeviceID><Number>1</Number></Query>"

        assertTrue(r.handle("CruiseTrackQuery", xml, fromUri = null))
        runCurrent()
        val body = f.transport.sent.last().body.decodeToString()
        assertTrue(body.contains("<StayTime>30</StayTime>"), body)
        assertTrue(body.contains("<Speed>128</Speed>"), body)
    }

    /**
     * 编号缺失 = 非标准请求:回空点位表,而**不是**猜一条轨迹。
     *
     * 猜错会静默查到别人的轨迹 —— 那种错比空表难查得多。
     */
    @Test
    fun cruise_track_query_without_number_answers_empty() = runTest {
        val f = SubRouterTestFixtures.newFixture(this)
        f.deviceControlState.value = f.deviceControlState.value.copy(
            cruiseTracks = mapOf(1 to CruiseTrackState(points = listOf(1, 3, 5))),
        )
        val dispatcher = DeviceControlDispatcher(f.deviceControlState, f.ctx.config, NoopActions, this)
        val r = DeviceControlSubRouter(f.ctx, NoopRecordingService, dispatcher) {}
        val xml = "<?xml version=\"1.0\"?><Query><CmdType>CruiseTrackQuery</CmdType><SN>9</SN>" +
            "<DeviceID>34020000001320000001</DeviceID></Query>"

        assertTrue(r.handle("CruiseTrackQuery", xml, fromUri = null))
        runCurrent()
        val body = f.transport.sent.last().body.decodeToString()
        assertTrue(body.contains("<CruisePointList Num=\"0\"/>"), body)
        assertTrue(body.contains("<SumNum>0</SumNum>"), body)
        assertFalse(body.contains("PresetIndex"), "不该猜任何一条轨迹的点位: $body")
    }

    @Test
    fun unknown_cmdtype_returns_false() = runTest {
        val f = SubRouterTestFixtures.newFixture(this)
        val dispatcher = DeviceControlDispatcher(f.deviceControlState, f.ctx.config, NoopActions, this)
        val r = DeviceControlSubRouter(f.ctx, NoopRecordingService, dispatcher) {}
        assertFalse(r.handle("Catalog", "<x/>", fromUri = null))
    }

    /**
     * 平台侧一次「从设备同步」会对清单里的轨迹**并发**下发多条 `CruiseTrackQuery`。
     *
     * 为什么是并发的:每条详情查询都要走一轮完整的 SIP 往返,串行下发 9 条最坏能拖到
     * 两分钟,操作员会以为卡死(见 PlayConsoleLinked 的 `syncCruises`)。它们在设备侧
     * 互相独立,没有顺序依赖,所以平台是同时发出去的。
     *
     * 这一条锁的就是并发下设备侧的契约:**按 SN 分别作答,且每条应答只报自己那条轨迹的
     * 点位**。串了的话后果不是"少显示一个数" —— 平台会把 A 的预置位链写进 B 的行,
     * 界面上显示的是一条根本不存在的轨迹内容,而且看不出是错的。
     */
    @Test
    fun concurrent_cruise_track_queries_answer_independently_by_sn() = runTest {
        val f = SubRouterTestFixtures.newFixture(this)
        f.deviceControlState.value = f.deviceControlState.value.copy(
            cruiseTracks = mapOf(
                1 to CruiseTrackState(points = listOf(1, 3), speed = 128, dwellTime = 30),
                2 to CruiseTrackState(points = listOf(7), speed = 128, dwellTime = 30),
                3 to CruiseTrackState(points = listOf(9, 8, 5), speed = 128, dwellTime = 30),
            ),
        )
        val dispatcher = DeviceControlDispatcher(f.deviceControlState, f.ctx.config, NoopActions, this)
        val r = DeviceControlSubRouter(f.ctx, NoopRecordingService, dispatcher) {}
        fun query(sn: Int, number: Int) =
            "<?xml version=\"1.0\"?><Query><CmdType>CruiseTrackQuery</CmdType><SN>$sn</SN>" +
                "<DeviceID>34020000001320000001</DeviceID><Number>$number</Number></Query>"

        launch { assertTrue(r.handle("CruiseTrackQuery", query(11, 1), fromUri = null)) }
        launch { assertTrue(r.handle("CruiseTrackQuery", query(12, 2), fromUri = null)) }
        launch { assertTrue(r.handle("CruiseTrackQuery", query(13, 3), fromUri = null)) }
        runCurrent()

        val bodies = f.transport.sent
            .map { it.body.decodeToString() }
            .filter { it.contains("<CmdType>CruiseTrackQuery</CmdType>") }
        assertEquals(3, bodies.size, "三条并发查询必须各得一条应答: $bodies")

        val bySn = bodies.associateBy { Regex("<SN>(\\d+)</SN>").find(it)!!.groupValues[1] }
        assertEquals(setOf("11", "12", "13"), bySn.keys, "应答的 SN 必须与请求一一对应: $bodies")

        val first = bySn.getValue("11")
        assertTrue(first.contains("<CruisePoint><PresetIndex>1</PresetIndex>"), first)
        assertTrue(first.contains("<CruisePoint><PresetIndex>3</PresetIndex>"), first)
        assertFalse(first.contains("PresetIndex>7<"), "应答串到别的轨迹上了: $first")

        val second = bySn.getValue("12")
        assertTrue(second.contains("<CruisePoint><PresetIndex>7</PresetIndex>"), second)
        assertFalse(second.contains("PresetIndex>1<"), "应答串到别的轨迹上了: $second")

        assertEquals(
            "3",
            Regex("<SumNum>(\\d+)</SumNum>").find(bySn.getValue("13"))!!.groupValues[1],
            "第三条轨迹有 3 个点位",
        )
    }
}
