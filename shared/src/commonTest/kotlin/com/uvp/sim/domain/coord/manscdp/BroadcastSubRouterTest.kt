package com.uvp.sim.domain.coord.manscdp

import com.uvp.sim.domain.coord.BroadcastInvoker
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Wave 4 PR-D / P2-1:[BroadcastSubRouter] 直接路径覆盖。
 *
 * 关注点:Broadcast 命令需要校验 TargetID 归属 + busy → 应答 OK/ERROR + 主动触发
 * [BroadcastInvoker.fireBroadcastInvite]。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BroadcastSubRouterTest {

    private class RecordingInvoker(
        private val outcome: com.uvp.sim.domain.coord.BroadcastInviteStart =
            com.uvp.sim.domain.coord.BroadcastInviteStart.Started,
    ) : BroadcastInvoker {
        var invoked = 0
        var source = ""
        var target = ""
        override suspend fun fireBroadcastInvite(sourceId: String, platformUri: String, targetId: String):
            com.uvp.sim.domain.coord.BroadcastInviteStart {
            invoked += 1
            source = sourceId
            target = targetId
            return outcome
        }
    }

    @Test
    fun accepts_only_broadcast() = runTest {
        val f = SubRouterTestFixtures.newFixture(this)
        val r = BroadcastSubRouter(f.ctx, RecordingInvoker(), broadcastBusy = { false })
        assertTrue(r.accepts("Broadcast"))
        assertFalse(r.accepts("Catalog"))
        assertFalse(r.accepts("DeviceControl"))
    }

    @Test
    fun broadcast_owned_target_replies_ok_and_invokes_invite() = runTest {
        val f = SubRouterTestFixtures.newFixture(this)
        val invoker = RecordingInvoker()
        val r = BroadcastSubRouter(f.ctx, invoker, broadcastBusy = { false })
        val xml = "<?xml version=\"1.0\"?><Notify><CmdType>Broadcast</CmdType><SN>1</SN>" +
            "<SourceID>34020000002000000001</SourceID>" +
            "<TargetID>34020000001110000001</TargetID></Notify>"

        assertTrue(r.handle("Broadcast", xml, fromUri = null))
        runCurrent()

        assertEquals(1, invoker.invoked, "owned target 应触发 fireBroadcastInvite")
        assertEquals("34020000002000000001", invoker.source)
        assertEquals("34020000001110000001", invoker.target)
        val body = f.transport.sent.first().body.decodeToString()
        assertTrue(body.contains("<CmdType>Broadcast</CmdType>"))
        assertTrue(body.contains("<Result>OK</Result>"))
    }

    @Test
    fun broadcast_unknown_target_replies_error_target_mismatch() = runTest {
        val f = SubRouterTestFixtures.newFixture(this)
        val invoker = RecordingInvoker()
        val r = BroadcastSubRouter(f.ctx, invoker, broadcastBusy = { false })
        // target 不属于本设备(既不是 deviceId / 视频通道 / 报警通道,也不在 catalogTree)
        val xml = "<?xml version=\"1.0\"?><Notify><CmdType>Broadcast</CmdType><SN>2</SN>" +
            "<SourceID>34020000002000000001</SourceID>" +
            "<TargetID>34020000009999999999</TargetID></Notify>"

        assertTrue(r.handle("Broadcast", xml, fromUri = null))
        runCurrent()

        assertEquals(0, invoker.invoked, "target mismatch 不应触发 invite")
        val body = f.transport.sent.first().body.decodeToString()
        assertTrue(body.contains("<Result>ERROR</Result>"), "应回 ERROR: $body")
    }

    @Test
    fun broadcast_when_busy_replies_error_busy() = runTest {
        val f = SubRouterTestFixtures.newFixture(this)
        val invoker = RecordingInvoker()
        val r = BroadcastSubRouter(f.ctx, invoker, broadcastBusy = { true })  // 模拟已有一路
        val xml = "<?xml version=\"1.0\"?><Notify><CmdType>Broadcast</CmdType><SN>3</SN>" +
            "<SourceID>34020000002000000001</SourceID>" +
            "<TargetID>34020000001110000001</TargetID></Notify>"

        assertTrue(r.handle("Broadcast", xml, fromUri = null))
        runCurrent()

        assertEquals(0, invoker.invoked, "busy 时不应触发 invite")
        val body = f.transport.sent.first().body.decodeToString()
        assertTrue(body.contains("<Result>ERROR</Result>"))
    }

    @Test
    fun non_broadcast_returns_false() = runTest {
        val f = SubRouterTestFixtures.newFixture(this)
        val r = BroadcastSubRouter(f.ctx, RecordingInvoker(), broadcastBusy = { false })
        assertFalse(r.handle("Catalog", "<x/>", fromUri = null))
        assertEquals(0, f.transport.sent.size)
    }
}
