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
import kotlin.test.assertTrue

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

    /**
     * 海康类 IPC 的注册方式:设备编码本身就是 132 段(摄像机/视频通道),平台点播直接打这个编号。
     * 目录树里它同时是根节点(Device),必须放行,否则这类平台永远点不通。
     */
    @Test fun device_root_with_132_type_code_is_accepted() {
        val ipcRoot = CatalogNode(
            id = "34020000001320000028",
            type = CatalogNodeType.Device,
            name = "ipc",
            parentId = "34020000001320000028",
        )
        assertNull(classifyInviteTarget("34020000001320000028", listOf(ipcRoot)))
    }

    /** 真正的设备编码(111 段)仍按 §C.2.3 拒绝 —— 放宽不能波及正常设备根。 */
    @Test fun device_root_with_111_type_code_still_rejected() {
        val devRoot = CatalogNode(
            id = "35020000001110000001",
            type = CatalogNodeType.Device,
            name = "dvr",
            parentId = "35020000001110000001",
        )
        val (code, reason) = classifyInviteTarget(devRoot.id, listOf(devRoot))!!
        assertEquals(488, code)
        assertTrue(reason.contains("device root"))
    }

    /** 非 20 位的自定义编码无从判断语义,保持 fail-closed。 */
    @Test fun device_root_with_non_gb_id_still_rejected() {
        val custom = CatalogNode(
            id = "dev-1",
            type = CatalogNodeType.Device,
            name = "custom",
            parentId = "dev-1",
        )
        val (code, _) = classifyInviteTarget("dev-1", listOf(custom))!!
        assertEquals(488, code)
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
