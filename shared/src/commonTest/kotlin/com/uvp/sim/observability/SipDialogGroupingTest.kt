package com.uvp.sim.observability

import com.uvp.sim.config.DeviceConfig
import com.uvp.sim.config.GbVersion
import com.uvp.sim.config.ServerConfig
import com.uvp.sim.config.SimConfig
import com.uvp.sim.network.TransportType
import com.uvp.sim.sip.SipBuilders
import com.uvp.sim.sip.SipHeader
import com.uvp.sim.sip.SipMessage
import com.uvp.sim.sip.SipMethod
import com.uvp.sim.sip.SipRequest
import com.uvp.sim.sip.SipResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SipDialogGroupingTest {

    private fun cfg() = SimConfig(
        gbVersion = GbVersion.V2022,
        server = ServerConfig("127.0.0.1", 5060, "34020000002000000001", "3402000000"),
        device = DeviceConfig(
            deviceId = "34020000001110000001",
            videoChannelId = "34020000001320000001",
            alarmChannelId = "34020000001340000001",
            username = "34020000001110000001",
            password = "secret"
        ),
        transport = TransportType.UDP, keepaliveIntervalSeconds = 60
    )

    private fun buildRegister(callId: String, t: Long, cseq: Int = 1): SipFlowEvent {
        val req = SipBuilders.buildRegister(
            cfg(), cseq, callId, "z9hG4bK-$cseq", "fromtag", "192.168.1.50", 5060
        )
        return SipFlowEvent(t, outgoing = true, message = req, callId = callId)
    }

    private fun buildResp(forCallId: String, status: Int, reason: String, t: Long, cseq: Int = 1, method: String = "REGISTER"): SipFlowEvent {
        val resp = SipResponse(
            statusCode = status,
            reasonPhrase = reason,
            headers = listOf(
                SipMessage.Header(SipHeader.VIA, "SIP/2.0/UDP 192.168.1.50:5060"),
                SipMessage.Header(SipHeader.FROM, "<sip:u@e>;tag=t"),
                SipMessage.Header(SipHeader.TO, "<sip:u@e>;tag=server"),
                SipMessage.Header(SipHeader.CALL_ID, forCallId),
                SipMessage.Header(SipHeader.CSEQ, "$cseq $method")
            )
        )
        return SipFlowEvent(t, outgoing = false, message = resp, callId = forCallId)
    }

    private fun buildKeepalive(callId: String, sn: Int, t: Long): SipFlowEvent {
        val req = SipBuilders.buildKeepalive(cfg(), sn, sn, callId, "z9hG4bK-$sn", "ftag", "192.168.1.50", 5060)
        return SipFlowEvent(t, outgoing = true, message = req, callId = callId)
    }

    @Test fun groupsRegisterDialogIncludingChallengeAndAuth() {
        val cid = "abc@host"
        val events = listOf(
            buildRegister(cid, 100, 1),
            buildResp(cid, 401, "Unauthorized", 200, 1),
            buildRegister(cid, 300, 2),
            buildResp(cid, 200, "OK", 400, 2)
        )
        val items = SipDialogGrouping.group(events)
        assertEquals(1, items.size, "应只有 1 个 Dialog")
        val d = items[0] as FlowItem.Dialog
        assertEquals(cid, d.callId)
        assertEquals(4, d.rows.size)
    }

    @Test fun foldsThirtyConsecutiveKeepalivesIntoOneCluster() {
        val events = (1..30).map {
            buildKeepalive("kp-$it@host", it, 1000L + it * 1000)
        }
        val items = SipDialogGrouping.group(events)
        assertEquals(1, items.size, "30 心跳应折成 1 个 cluster")
        val c = items[0] as FlowItem.HeartbeatCluster
        assertEquals(30, c.count)
    }

    @Test fun inviteDialogWithMediaSegmentAtTail() {
        val cid = "inv@host"
        val invite = SipRequest(
            method = SipMethod.INVITE,
            requestUri = "sip:34020000001320000001@3402000000",
            headers = listOf(
                SipMessage.Header(SipHeader.VIA, "SIP/2.0/UDP 192.168.1.50:5060"),
                SipMessage.Header(SipHeader.FROM, "<sip:u@e>;tag=t"),
                SipMessage.Header(SipHeader.TO, "<sip:u@e>"),
                SipMessage.Header(SipHeader.CALL_ID, cid),
                SipMessage.Header(SipHeader.CSEQ, "1 INVITE")
            )
        )
        val events = listOf(
            SipFlowEvent(1000, outgoing = false, message = invite, callId = cid),
            buildResp(cid, 200, "OK", 1100, 1, "INVITE")
        )
        val media = listOf(
            MediaSegmentEvent(cid, 1200, null, 100, 200, "10.0.0.1", 9000)
        )
        val items = SipDialogGrouping.group(events, media)
        assertEquals(1, items.size)
        val d = items[0] as FlowItem.Dialog
        // 2 SIP messages + 1 MediaSegment
        assertEquals(3, d.rows.size)
        assertTrue(d.rows.last() is DialogRow.MediaSegment)
    }

    @Test fun heartbeatInterleavedWithInvitePreservesBothClustersAndDialog() {
        val events = mutableListOf<SipFlowEvent>()
        // 5 心跳
        events.addAll((1..5).map { buildKeepalive("kp-$it@h", it, 1000L + it * 60_000) })
        // 1 INVITE 在心跳中间
        val cidInv = "inv@h"
        val invite = SipRequest(
            method = SipMethod.INVITE,
            requestUri = "sip:dest",
            headers = listOf(
                SipMessage.Header(SipHeader.VIA, "SIP/2.0/UDP a"),
                SipMessage.Header(SipHeader.FROM, "<sip:u@e>;tag=t"),
                SipMessage.Header(SipHeader.TO, "<sip:u@e>"),
                SipMessage.Header(SipHeader.CALL_ID, cidInv),
                SipMessage.Header(SipHeader.CSEQ, "1 INVITE")
            )
        )
        events += SipFlowEvent(3 * 60_000 + 30_000, outgoing = false, message = invite, callId = cidInv)
        // 5 个心跳后期(间隔 > 5min,应切成新 cluster)
        events.addAll((6..10).map { buildKeepalive("kp-$it@h", it, 10_000_000L + it * 60_000) })

        val items = SipDialogGrouping.group(events)
        val dialogs = items.filterIsInstance<FlowItem.Dialog>()
        val clusters = items.filterIsInstance<FlowItem.HeartbeatCluster>()
        assertEquals(1, dialogs.size, "1 个 INVITE Dialog")
        assertEquals(2, clusters.size, "应有 2 个心跳 cluster (前 5 + 后 5)")
    }

    @Test fun keepalivesSeparatedByMoreThan5MinNotMerged() {
        val events = listOf(
            buildKeepalive("kp-1@h", 1, 1_000_000),
            buildKeepalive("kp-2@h", 2, 1_000_000 + 6 * 60_000)  // 6 分钟后
        )
        val items = SipDialogGrouping.group(events)
        // 2 个独立心跳,但每个都是单条 < HEARTBEAT_MIN_COUNT,不显示为 cluster
        // 这里验证不会错误合并:cluster 数量 0(都被过滤为单条)
        val clusters = items.filterIsInstance<FlowItem.HeartbeatCluster>()
        assertEquals(0, clusters.size, "单条心跳不形成 cluster,实际 ${clusters.size}")
    }
}
