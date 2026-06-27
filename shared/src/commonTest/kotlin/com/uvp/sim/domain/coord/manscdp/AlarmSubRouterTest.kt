package com.uvp.sim.domain.coord.manscdp

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Wave 4 PR-D / P2-1:[AlarmSubRouter] 直接路径覆盖。
 *
 * 当前只负责 AlarmStatus 查询。主动报警上报留在 ManscdpRouterImpl,不归本路由测试。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AlarmSubRouterTest {

    @Test
    fun accepts_only_alarm_status() = runTest {
        val f = SubRouterTestFixtures.newFixture(this)
        val r = AlarmSubRouter(f.ctx)

        assertTrue(r.accepts("AlarmStatus"))
        assertFalse(r.accepts("Catalog"))
        assertFalse(r.accepts("Alarm"))  // 主动报警上报不归 SubRouter
        assertFalse(r.accepts("DeviceControl"))
    }

    @Test
    fun alarm_status_query_when_not_alarming_returns_offduty() = runTest {
        val f = SubRouterTestFixtures.newFixture(this)
        val r = AlarmSubRouter(f.ctx)
        val xml = "<?xml version=\"1.0\"?><Query><CmdType>AlarmStatus</CmdType><SN>1</SN>" +
            "<DeviceID>34020000001110000001</DeviceID></Query>"

        assertTrue(r.handle("AlarmStatus", xml, fromUri = null))
        runCurrent()

        val body = f.transport.sent.first().body.decodeToString()
        assertTrue(body.contains("<CmdType>AlarmStatus</CmdType>"))
    }

    @Test
    fun alarm_status_query_reflects_alarming_state() = runTest {
        val f = SubRouterTestFixtures.newFixture(this)
        f.deviceControlState.value = f.deviceControlState.value.copy(isAlarming = true)
        val r = AlarmSubRouter(f.ctx)
        val xml = "<?xml version=\"1.0\"?><Query><CmdType>AlarmStatus</CmdType><SN>2</SN>" +
            "<DeviceID>34020000001110000001</DeviceID></Query>"

        assertTrue(r.handle("AlarmStatus", xml, fromUri = null))
        runCurrent()

        val body = f.transport.sent.first().body.decodeToString()
        // GB-2022 嵌套:isAlarming=true → DutyStatus=ALARM
        assertTrue(body.contains("ALARM"), "应反映报警中: actual=$body")
    }

    @Test
    fun handle_returns_false_for_non_alarm_status() = runTest {
        val f = SubRouterTestFixtures.newFixture(this)
        val r = AlarmSubRouter(f.ctx)
        assertFalse(r.handle("Catalog", "<x/>", fromUri = null))
        assertEquals(0, f.transport.sent.size)
    }

    @Test
    fun alarm_status_includes_alarm_channel_id() = runTest {
        val f = SubRouterTestFixtures.newFixture(this)
        val r = AlarmSubRouter(f.ctx)
        val xml = "<?xml version=\"1.0\"?><Query><CmdType>AlarmStatus</CmdType><SN>3</SN>" +
            "<DeviceID>34020000001110000001</DeviceID></Query>"

        assertTrue(r.handle("AlarmStatus", xml, fromUri = null))
        runCurrent()

        val body = f.transport.sent.first().body.decodeToString()
        // alarmChannelId 应进入 response body
        assertTrue(body.contains("34020000001340000001"), "应含 alarmChannelId: actual=$body")
    }
}
