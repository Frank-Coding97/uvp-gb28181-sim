package com.uvp.sim.domain.coord

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * PR1 T1.1 RED→GREEN — [AcceptResult] / [DialogResult] sealed 代数契约。
 *
 * 这两个 sealed result 是 InviteCoordinator handler 拆分(plan §2.0)的核心契约:
 * - AcceptHandler 完成后返回 [AcceptResult],主类按 Success/Rejected/Failed 分支决策
 * - DialogHandler 完成后返回 [DialogResult],主类按 TerminateDialog/KeepDialog 决策
 *
 * Sealed 代数错了所有 handler 拆分都不成立。
 */
class InviteHandlerResultsTest {

    // ---- AcceptResult ----

    @Test
    fun acceptResult_success_carries_acceptedInvite() {
        val accepted = stubAcceptedInvite(cid = "cid-1")
        val r: AcceptResult = AcceptResult.Success(accepted)
        assertTrue(r is AcceptResult.Success)
        assertSame(accepted, r.accepted)
    }

    @Test
    fun acceptResult_rejected_carries_statusCode_and_reason() {
        val r: AcceptResult = AcceptResult.Rejected(488, "SDP parse")
        assertTrue(r is AcceptResult.Rejected)
        assertEquals(488, r.statusCode)
        assertEquals("SDP parse", r.reason)
    }

    @Test
    fun acceptResult_failed_carries_cause() {
        val ex = RuntimeException("network down")
        val r: AcceptResult = AcceptResult.Failed(ex)
        assertTrue(r is AcceptResult.Failed)
        assertSame(ex, r.cause)
    }

    @Test
    fun acceptResult_rejected_equality_by_value() {
        val a = AcceptResult.Rejected(488, "SDP parse")
        val b = AcceptResult.Rejected(488, "SDP parse")
        val c = AcceptResult.Rejected(500, "RTP bind")
        assertEquals(a, b, "同 statusCode + reason 应 equals")
        assertNotEquals(a, c)
    }

    // ---- DialogResult ----

    @Test
    fun dialogResult_terminateDialog_carries_reason() {
        val r: DialogResult = DialogResult.TerminateDialog("remote BYE")
        assertTrue(r is DialogResult.TerminateDialog)
        assertEquals("remote BYE", r.reason)
    }

    @Test
    fun dialogResult_keepDialog_is_singleton_object() {
        val a: DialogResult = DialogResult.KeepDialog
        val b: DialogResult = DialogResult.KeepDialog
        assertTrue(a === b, "KeepDialog 应是 object 单例")
    }

    @Test
    fun dialogResult_exhaustive_when_compiles() {
        val cases: List<DialogResult> = listOf(
            DialogResult.TerminateDialog("BYE"),
            DialogResult.KeepDialog,
        )
        cases.forEach { r ->
            val label = when (r) {
                is DialogResult.TerminateDialog -> "t:${r.reason}"
                DialogResult.KeepDialog -> "keep"
            }
            assertTrue(label.isNotEmpty())
        }
    }

    // ---- 构造 stub AcceptedInvite(测试用) ----
    private fun stubAcceptedInvite(cid: String) =
        AcceptedInvite(
            cid = cid,
            rtp = com.uvp.sim.network.RtpSender("127.0.0.1", 0),
            offer = com.uvp.sim.sip.SdpOffer(
                remoteIp = "127.0.0.1",
                remotePort = 30000,
                ssrc = "0100000001",
                direction = com.uvp.sim.sip.SdpDirection.SENDRECV,
                rawBody = "",
            ),
            ssrc = "0100000001",
            cam = com.uvp.sim.camera.CameraCapture(com.uvp.sim.camera.CaptureConfig()),
            channelId = "ch-1",
            localUri = "sip:34020000001310000001@127.0.0.1",
            localTag = "lt",
            remoteUri = "sip:server@127.0.0.1",
            remoteTag = "rt",
            remoteTarget = "sip:server@127.0.0.1",
            remoteSourceIp = "127.0.0.1",
        )
}
