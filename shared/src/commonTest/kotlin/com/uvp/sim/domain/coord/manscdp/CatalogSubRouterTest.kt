package com.uvp.sim.domain.coord.manscdp

import com.uvp.sim.config.CatalogNode
import com.uvp.sim.config.CatalogNodeType
import com.uvp.sim.recording.NoopRecordingService
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
}
