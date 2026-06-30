package com.uvp.sim.domain.coord

import com.uvp.sim.config.CatalogNode
import com.uvp.sim.config.CatalogNodeType
import com.uvp.sim.sip.SipHeader
import com.uvp.sim.sip.SipMessage
import com.uvp.sim.sip.SipMethod
import com.uvp.sim.sip.SipRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * R4 #5 + R4 #4:top-level [classifyInviteTarget] / [extractInviteTarget] 纯函数测试。
 *
 * 这两个函数是主类 + AcceptHandler 共用的真实 channel 路由实现,任何回归会同时影响两路。
 * 关键保护(对应 cross-review fix):
 * - 空 catalogTree → 404(fail-closed,不再 fail-open 落默认 channel)
 * - 未知 channelId → 404
 * - AlarmChannel / Device / BusinessGroup / VirtualOrg → 488
 * - 空 channelId → null(给上层走配置默认 channel)
 */
class InviteTargetClassifierTest {
    private val videoNode = CatalogNode(
        id = "35020000001320000001",
        type = CatalogNodeType.VideoChannel,
        name = "video-1",
        parentId = "35020000001000000001",
    )
    private val alarmNode = CatalogNode(
        id = "35020000001340000001",
        type = CatalogNodeType.AlarmChannel,
        name = "alarm-1",
        parentId = "35020000001000000001",
    )

    @Test fun empty_tree_unknown_channel_returns_404() {
        val (code, reason) = classifyInviteTarget("35020000001320000001", emptyList())!!
        assertEquals(404, code)
        assertEquals("Channel Not Found", reason)
    }

    @Test fun unknown_channel_returns_404() {
        val (code, _) = classifyInviteTarget("99999999990000000000", listOf(videoNode))!!
        assertEquals(404, code)
    }

    @Test fun video_channel_returns_null_accept() {
        assertNull(classifyInviteTarget(videoNode.id, listOf(videoNode)))
    }

    @Test fun alarm_channel_returns_488() {
        val (code, _) = classifyInviteTarget(alarmNode.id, listOf(videoNode, alarmNode))!!
        assertEquals(488, code)
    }

    @Test fun blank_channel_returns_null_default() {
        assertNull(classifyInviteTarget("", listOf(videoNode)))
    }

    @Test fun extract_channel_from_uri() {
        val req = SipRequest(
            method = SipMethod.INVITE,
            requestUri = "sip:35020000001320000001@1.2.3.4:5060",
            headers = listOf(SipMessage.Header(SipHeader.CALL_ID, "c")),
        )
        assertEquals("35020000001320000001", extractInviteTarget(req))
    }

    @Test fun extract_returns_empty_when_no_at_sign() {
        val req = SipRequest(
            method = SipMethod.INVITE,
            requestUri = "sip:malformed",
            headers = listOf(SipMessage.Header(SipHeader.CALL_ID, "c")),
        )
        assertEquals("", extractInviteTarget(req))
    }
}
