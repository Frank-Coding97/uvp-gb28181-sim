package com.uvp.sim.domain.coord.manscdp

import com.uvp.sim.config.CatalogNode
import com.uvp.sim.config.CatalogNodeType
import com.uvp.sim.config.GbVersion
import com.uvp.sim.recording.NoopRecordingService
import com.uvp.sim.recording.RecordSource
import com.uvp.sim.recording.RecordType
import com.uvp.sim.recording.RecordingFile
import com.uvp.sim.recording.RecordingService
import com.uvp.sim.recording.RecordingState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Wave 4 PR-D / P2-1:[CatalogSubRouter] 直接路径覆盖。
 *
 * 验证按 CmdType 调用 → outbound MESSAGE body 含对应 Response 节点。
 * 4 个 SubRouter 共用 [SubRouterTestFixtures] 装配。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CatalogSubRouterTest {

    @Test
    fun accepts_returns_true_only_for_owned_cmdTypes() = runTest {
        val f = SubRouterTestFixtures.newFixture(this)
        val r = CatalogSubRouter(f.ctx, NoopRecordingService)

        assertTrue(r.accepts("Catalog"))
        assertTrue(r.accepts("DeviceInfo"))
        assertTrue(r.accepts("DeviceStatus"))
        assertTrue(r.accepts("ConfigDownload"))
        assertTrue(r.accepts("MobilePosition"))
        assertTrue(r.accepts("RecordInfo"))

        // 其它 CmdType 一律不接
        assertFalse(r.accepts("DeviceControl"))
        assertFalse(r.accepts("Broadcast"))
        assertFalse(r.accepts("AlarmStatus"))
        assertFalse(r.accepts("PresetQuery"))
        assertFalse(r.accepts("UnknownCmd"))
    }

    @Test
    fun catalog_query_emits_catalog_response_message() = runTest {
        val f = SubRouterTestFixtures.newFixture(this)
        val r = CatalogSubRouter(f.ctx, NoopRecordingService)
        val xml = "<?xml version=\"1.0\"?><Query><CmdType>Catalog</CmdType><SN>1</SN>" +
            "<DeviceID>34020000001110000001</DeviceID></Query>"

        val handled = r.handle("Catalog", xml, fromUri = null)
        runCurrent()

        assertTrue(handled)
        assertTrue(
            with(SubRouterTestFixtures) { f.transport.containsBody("<CmdType>Catalog</CmdType>") },
            "Catalog Response 应出栈"
        )
    }

    @Test
    fun catalog_query_uses_configured_multi_response_page_size() = runTest {
        val cfg = SubRouterTestFixtures.config().copy(multiResponsePageSize = 1)
        val f = SubRouterTestFixtures.newFixture(this, cfg)
        val r = CatalogSubRouter(f.ctx, NoopRecordingService)
        val xml = "<?xml version=\"1.0\"?><Query><CmdType>Catalog</CmdType><SN>17</SN>" +
            "<DeviceID>34020000001110000001</DeviceID></Query>"

        assertTrue(r.handle("Catalog", xml, fromUri = null))
        runCurrent()

        val bodies = f.transport.sent.map { it.body.decodeToString() }
        assertTrue(bodies.size > 1, "默认目录包含多个节点，pageSize=1 应产生多条 MESSAGE")
        assertTrue(bodies.all { it.contains("<SN>17</SN>") })
        assertTrue(bodies.all { it.contains("<DeviceList Num=\"1\">") })
    }

    @Test
    fun catalog_query_filters_business_group_subtree_and_echoes_target() = runTest {
        val rootId = "34020000001110000001"
        val groupId = "34020000002150000001"
        val orgId = "34020000002160000001"
        val channelId = "34020000001320000001"
        val unrelatedId = "34020000001320000002"
        val cfg = SubRouterTestFixtures.config().copy(catalogTree = listOf(
            CatalogNode(rootId, CatalogNodeType.Device, "设备", rootId),
            CatalogNode(groupId, CatalogNodeType.BusinessGroup, "重点场所", rootId),
            CatalogNode(orgId, CatalogNodeType.VirtualOrg, "校园", groupId),
            CatalogNode(channelId, CatalogNodeType.VideoChannel, "校门", orgId),
            CatalogNode(unrelatedId, CatalogNodeType.VideoChannel, "无关通道", rootId),
        ))
        val f = SubRouterTestFixtures.newFixture(this, cfg)
        val r = CatalogSubRouter(f.ctx, NoopRecordingService)
        val xml = "<Query><CmdType>Catalog</CmdType><SN>18</SN><DeviceID>$groupId</DeviceID></Query>"

        assertTrue(r.handle("Catalog", xml, fromUri = null))
        runCurrent()

        val body = f.transport.sent.single().body.decodeToString()
        assertTrue(body.contains("<DeviceID>$groupId</DeviceID>"))
        assertTrue(body.contains("<DeviceID>$orgId</DeviceID>"))
        assertTrue(body.contains("<DeviceID>$channelId</DeviceID>"))
        val itemIds = """<Item>\s*<DeviceID>(.+?)</DeviceID>"""
            .toRegex()
            .findAll(body)
            .map { it.groupValues[1] }
            .toSet()
        assertFalse(rootId in itemIds)
        assertFalse(unrelatedId in itemIds)
    }

    @Test
    fun catalog_query_unknown_target_returns_empty_result() = runTest {
        val f = SubRouterTestFixtures.newFixture(this)
        val r = CatalogSubRouter(f.ctx, NoopRecordingService)

        assertTrue(r.handle(
            "Catalog",
            "<Query><CmdType>Catalog</CmdType><SN>19</SN><DeviceID>34020000009990000001</DeviceID></Query>",
            fromUri = null,
        ))
        runCurrent()

        val body = f.transport.sent.single().body.decodeToString()
        assertTrue(body.contains("<SumNum>0</SumNum>"))
        assertFalse(body.contains("<DeviceList"))
    }

    @Test
    fun device_info_query_emits_device_info_response() = runTest {
        val f = SubRouterTestFixtures.newFixture(this)
        val r = CatalogSubRouter(f.ctx, NoopRecordingService)
        val xml = "<?xml version=\"1.0\"?><Query><CmdType>DeviceInfo</CmdType><SN>2</SN>" +
            "<DeviceID>34020000001110000001</DeviceID></Query>"

        assertTrue(r.handle("DeviceInfo", xml, fromUri = null))
        runCurrent()
        assertTrue(
            with(SubRouterTestFixtures) { f.transport.containsBody("<CmdType>DeviceInfo</CmdType>") },
        )
    }

    @Test
    fun device_status_query_emits_device_status_with_state_snapshot() = runTest {
        val f = SubRouterTestFixtures.newFixture(this)
        // 修改 device control 状态为录像中,验证 snapshot 反映过去
        f.deviceControlState.value = f.deviceControlState.value.copy(isRecording = true, isAlarming = true)
        val r = CatalogSubRouter(f.ctx, NoopRecordingService)
        val xml = "<?xml version=\"1.0\"?><Query><CmdType>DeviceStatus</CmdType><SN>3</SN>" +
            "<DeviceID>34020000001110000001</DeviceID></Query>"

        assertTrue(r.handle("DeviceStatus", xml, fromUri = null))
        runCurrent()

        val responseBody = f.transport.sent.first().body.decodeToString()
        assertTrue(responseBody.contains("<CmdType>DeviceStatus</CmdType>"))
        // DeviceStatusResponse 用 "ON"/"OFF"
        assertTrue(responseBody.contains("ON"), "录像状态应为 ON: actual=$responseBody")
    }

    @Test
    fun config_download_extracts_config_types_param() = runTest {
        val f = SubRouterTestFixtures.newFixture(this)
        val r = CatalogSubRouter(f.ctx, NoopRecordingService)
        val xml = "<?xml version=\"1.0\"?><Query><CmdType>ConfigDownload</CmdType>" +
            "<SN>4</SN><DeviceID>34020000001110000001</DeviceID>" +
            "<ConfigType>BasicParam</ConfigType></Query>"

        assertTrue(r.handle("ConfigDownload", xml, fromUri = null))
        runCurrent()
        assertTrue(
            with(SubRouterTestFixtures) { f.transport.containsBody("<CmdType>ConfigDownload</CmdType>") },
        )
    }

    @Test
    fun mobile_position_query_returns_geo_point() = runTest {
        val f = SubRouterTestFixtures.newFixture(this)
        val r = CatalogSubRouter(f.ctx, NoopRecordingService)
        val xml = "<?xml version=\"1.0\"?><Query><CmdType>MobilePosition</CmdType><SN>5</SN>" +
            "<DeviceID>34020000001110000001</DeviceID></Query>"

        assertTrue(r.handle("MobilePosition", xml, fromUri = null))
        runCurrent()
        assertTrue(
            with(SubRouterTestFixtures) { f.transport.containsBody("<CmdType>MobilePosition</CmdType>") },
        )
    }

    @Test
    fun record_info_query_returns_empty_when_no_files() = runTest {
        // NoopRecordingService 的 files.value 是 emptyList(),应该返回 1 个空 RecordInfo 包
        val f = SubRouterTestFixtures.newFixture(this)
        val r = CatalogSubRouter(f.ctx, NoopRecordingService)
        val xml = "<?xml version=\"1.0\"?><Query><CmdType>RecordInfo</CmdType><SN>6</SN>" +
            "<DeviceID>34020000001110000001</DeviceID>" +
            "<StartTime>2026-01-01T00:00:00</StartTime>" +
            "<EndTime>2026-12-31T23:59:59</EndTime></Query>"

        assertTrue(r.handle("RecordInfo", xml, fromUri = null))
        runCurrent()
        assertTrue(
            with(SubRouterTestFixtures) { f.transport.containsBody("<CmdType>RecordInfo</CmdType>") },
            "RecordInfo Response 应出栈 (空集也要回)"
        )
    }

    @Test
    fun unknown_cmdtype_returns_false_unhandled() = runTest {
        val f = SubRouterTestFixtures.newFixture(this)
        val r = CatalogSubRouter(f.ctx, NoopRecordingService)
        // 即便 accepts 兜底了,handle 也防御性 false(双保险)
        assertFalse(r.handle("Broadcast", "<x/>", fromUri = null))
        assertEquals(0, f.transport.sent.size)
    }

    // ============ 2026-09-19 G-7 / G-12：2022 录像查询过滤 + 版本分支 ============

    /** 有录像的假服务 —— 过滤链只有在**确实有命中集**时才测得出来。 */
    private class FakeRecordingService(files: List<RecordingFile>) : RecordingService {
        override val state = MutableStateFlow(RecordingState.Idle)
        override val files: StateFlow<List<RecordingFile>> = MutableStateFlow(files)
        override suspend fun start(source: RecordSource, channelId: String, streamNumber: Int): Result<Unit> =
            Result.success(Unit)
        override suspend fun stop(): Result<RecordingFile?> = Result.success(null)
        override suspend fun load() = Unit
        override suspend fun delete(id: String): Result<Unit> = Result.success(Unit)
    }

    /**
     * ⚠️ 录像时段与查询窗口必须**真的重叠**，否则命中恒为 0、整套过滤断言都会"通过"得莫名其妙
     * （空集上做过滤看不出任何差别）。这里把两者都钉在 2026-06：
     * `1781452800000` = 2026-06-15T00:00:00+08:00。
     */
    private fun rec(
        id: String,
        streamNumber: Int = 0,
        type: RecordType = RecordType.Time,
    ) = RecordingFile(
        id = id,
        startTimeMs = 1_781_452_800_000L,
        endTimeMs = 1_781_452_800_000L + 600_000L,
        durationMs = 600_000L,
        channelId = "34020000001320000001",
        filePath = "/data/recordings/$id.mp4",
        sizeBytes = 2048L,
        source = RecordSource.Manual,
        type = type,
        secrecy = 0,
        streamNumber = streamNumber,
    )

    /** 主码流定时录像 + 子码流报警录像，覆盖两个过滤维度。 */
    private fun twoRecordings() = listOf(
        rec("main_time", streamNumber = 0, type = RecordType.Time),
        rec("sub_alarm", streamNumber = 1, type = RecordType.Alarm),
    )

    /** 查询窗口与 [rec] 的录像时段必须重叠（2026-06 整月）。 */
    private fun recordQuery(vararg extra: String) = buildString {
        append("<?xml version=\"1.0\"?><Query><CmdType>RecordInfo</CmdType><SN>9</SN>")
        append("<DeviceID>34020000001320000001</DeviceID>")
        append("<StartTime>2026-06-01T00:00:00</StartTime>")
        append("<EndTime>2026-06-30T23:59:59</EndTime>")
        for (e in extra) append(e)
        append("</Query>")
    }

    /**
     * 投一条 RecordInfo 查询并返回出栈的报文体。
     *
     * ⛔ `scope` 必须显式传：`runCurrent()` 是 `TestScope` 的扩展，而这里是 `Fixture` 的扩展，
     * 隐式接收者已经不是 TestScope 了 —— 直接写 `runCurrent()` 编译不过（会报
     * "None of the following candidates is applicable"，看不出真正原因）。
     */
    private suspend fun SubRouterTestFixtures.Fixture.runRecordQuery(
        scope: TestScope,
        svc: RecordingService,
        xml: String,
    ): String {
        val r = CatalogSubRouter(ctx, svc)
        assertTrue(r.handle("RecordInfo", xml, fromUri = null))
        scope.runCurrent()
        return transport.sent.last().body.decodeToString()
    }

    private fun bodyOf(xml: String): String = xml.substringAfter("?>")

    private fun itemCount(xml: String): Int = Regex("<Item>").findAll(xml).count()

    /** 不带任何 2022 筛选条件 ⇒ 两条都回（对照基线）。 */
    @Test
    fun recordInfo_without2022Filters_returnsEverything() = runTest {
        val f = SubRouterTestFixtures.newFixture(this)
        val xml = f.runRecordQuery(this, FakeRecordingService(twoRecordings()), recordQuery())
        assertTrue(xml.contains("<SumNum>2</SumNum>"), "actual: ${bodyOf(xml)}")
        assertEquals(2, itemCount(xml))
    }

    /**
     * ⭐ `StreamNumber=0` 只回**主码流**那条。
     *
     * ⛔ 这是 G-7 的核心：不解析这个条件就变成**静默返回超集** ——
     * 平台按码流筛，设备把另一路码流也回了，而平台无法分辨哪条是超发的。
     */
    @Test
    fun recordInfo_streamNumberFilter_narrowsToThatStream() = runTest {
        val f = SubRouterTestFixtures.newFixture(this)
        val xml = f.runRecordQuery(
            this,
            FakeRecordingService(twoRecordings()),
            recordQuery("<StreamNumber>0</StreamNumber>")
        )
        assertTrue(xml.contains("<SumNum>1</SumNum>"), "actual: ${bodyOf(xml)}")
        assertTrue(xml.contains("/data/recordings/main_time.mp4"))
        assertFalse(xml.contains("sub_alarm.mp4"), "子码流那条不该被带出来: ${bodyOf(xml)}")
    }

    /**
     * ⭐ 平台筛子码流而设备只有主码流录像 ⇒ **诚实的空结果**。
     *
     * ⛔ 绝不能"忽略条件返回全部"：那会让平台把主码流录像当成子码流录像展示，
     * 且平台没有任何办法分辨。
     */
    @Test
    fun recordInfo_subStreamQuery_returnsHonestEmptyResult() = runTest {
        val f = SubRouterTestFixtures.newFixture(this)
        val xml = f.runRecordQuery(
            this,
            FakeRecordingService(twoRecordings()),
            recordQuery("<StreamNumber>2</StreamNumber>")
        )
        assertTrue(xml.contains("<SumNum>0</SumNum>"), "actual: ${bodyOf(xml)}")
        assertTrue(xml.contains("<RecordList Num=\"0\""), "actual: ${bodyOf(xml)}")
        assertEquals(0, itemCount(xml))
    }

    /**
     * ⭐ 报警维度筛选（`AlarmMethod` / `AlarmType`）**只回报警录像**。
     *
     * ⚠️ 口径刻意保守：本仓 `RecordingFile` 只有 `type` 一列，没有"报警方式/报警类型"
     * 两列，所以做不到精确相等；但**"绝不忽略条件"** 这条底线必须守住 ——
     * 返回超集比返回空更糟（平台无法分辨哪条是超发的）。
     */
    @Test
    fun recordInfo_alarmFilter_returnsOnlyAlarmRecordings() = runTest {
        val f = SubRouterTestFixtures.newFixture(this)
        val xml = f.runRecordQuery(
            this,
            FakeRecordingService(twoRecordings()),
            recordQuery("<AlarmMethod>5</AlarmMethod>")
        )
        assertTrue(xml.contains("<SumNum>1</SumNum>"), "actual: ${bodyOf(xml)}")
        assertTrue(xml.contains("/data/recordings/sub_alarm.mp4"))
        assertFalse(xml.contains("main_time.mp4"), "定时录像不该被当成报警录像回: ${bodyOf(xml)}")
    }

    @Test
    fun recordInfo_alarmTypeAloneAlsoNarrows() = runTest {
        val f = SubRouterTestFixtures.newFixture(this)
        val xml = f.runRecordQuery(
            this,
            FakeRecordingService(twoRecordings()),
            recordQuery("<AlarmType>2</AlarmType>")
        )
        assertTrue(xml.contains("<SumNum>1</SumNum>"), "actual: ${bodyOf(xml)}")
        assertTrue(xml.contains("sub_alarm.mp4"))
    }

    /**
     * ⛔ **有效版本是 2016 时 item 里不得出现 2022 新增的两个字段**。
     *
     * 用 `ctx.effectiveGbVersion`（协商结果）而不是 `config.gbVersion`，
     * 因为"对面是 2016"这一路只在联调时出现，多发的元素会让严格校验的对端判整条非法。
     */
    @Test
    fun recordInfo_itemsFollowEffectiveGbVersion() = runTest {
        val files = listOf(rec("main_time"))

        val f2022 = SubRouterTestFixtures.newFixture(this)
        val xml2022 = f2022.runRecordQuery(this, FakeRecordingService(files), recordQuery())
        assertTrue(xml2022.contains("<RecordLocation>"), "2022 要有 RecordLocation: ${bodyOf(xml2022)}")
        assertTrue(xml2022.contains("<StreamNumber>0</StreamNumber>"))
        assertTrue(xml2022.contains("<FileSize>2048</FileSize>"), "FileSize 两版都有")

        val f2016 = SubRouterTestFixtures.newFixture(
            this, cfg = SubRouterTestFixtures.config().copy(gbVersion = GbVersion.V2016)
        )
        val xml2016 = f2016.runRecordQuery(this, FakeRecordingService(files), recordQuery())
        assertFalse(xml2016.contains("<RecordLocation>"), "2016 不得出现 RecordLocation: ${bodyOf(xml2016)}")
        assertFalse(xml2016.contains("<StreamNumber>"), "2016 不得出现 StreamNumber: ${bodyOf(xml2016)}")
        assertTrue(xml2016.contains("<FileSize>2048</FileSize>"), "FileSize 在 2016 也要发: ${bodyOf(xml2016)}")
    }
}
