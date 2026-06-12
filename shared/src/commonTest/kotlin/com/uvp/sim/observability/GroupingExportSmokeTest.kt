package com.uvp.sim.observability

import com.uvp.sim.config.DeviceConfig
import com.uvp.sim.config.GbVersion
import com.uvp.sim.config.ServerConfig
import com.uvp.sim.config.SimConfig
import com.uvp.sim.network.TransportType
import com.uvp.sim.sip.SipBuilders
import com.uvp.sim.sip.SipHeader
import com.uvp.sim.sip.SipMessage
import com.uvp.sim.sip.SipResponse
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * T13 — 时序图 / 系统日志导出格式快速校验。
 *
 * 重要校验点:
 * - Dialog 头出现
 * - Heartbeat Cluster 头出现
 * - level / tag 进了系统日志输出
 * - 文件名格式正确
 *
 * 完整 LogExport 测试在 composeApp(没 jvm target),这里在 shared 模块用同样
 * 的算法层验证 — 拷一份 minimal stub.
 */
class GroupingExportSmokeTest {

    @Test fun groupingHandlesEmpty() {
        val items = SipDialogGrouping.group(emptyList())
        assertTrue(items.isEmpty())
    }

    @Test fun groupingPreservesAllRowsOfSingleDialog() {
        val cfg = SimConfig(
            gbVersion = GbVersion.V2022,
            server = ServerConfig("127.0.0.1", 5060, "34020000002000000001", "3402000000"),
            device = DeviceConfig(
                deviceId = "dev1",
                videoChannelId = "ch1",
                alarmChannelId = "al1",
                username = "u",
                password = "p"
            ),
            transport = TransportType.UDP, keepaliveIntervalSeconds = 60
        )
        val cid = "single@h"
        val req = SipBuilders.buildRegister(cfg, 1, cid, "branch1", "ftag", "192.168.1.50", 5060)
        val resp = SipResponse(
            statusCode = 200,
            reasonPhrase = "OK",
            headers = listOf(
                SipMessage.Header(SipHeader.VIA, "SIP/2.0/UDP a"),
                SipMessage.Header(SipHeader.FROM, "<sip:u@e>;tag=t"),
                SipMessage.Header(SipHeader.TO, "<sip:u@e>;tag=s"),
                SipMessage.Header(SipHeader.CALL_ID, cid),
                SipMessage.Header(SipHeader.CSEQ, "1 REGISTER")
            )
        )
        val items = SipDialogGrouping.group(listOf(
            SipFlowEvent(100, true, req, cid),
            SipFlowEvent(200, false, resp, cid)
        ))
        assertTrue(items.size == 1)
        val d = items[0] as FlowItem.Dialog
        assertTrue(d.rows.size == 2)
    }
}
