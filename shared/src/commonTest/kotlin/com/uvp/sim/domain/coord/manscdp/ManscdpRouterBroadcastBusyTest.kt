package com.uvp.sim.domain.coord.manscdp

import com.uvp.sim.domain.coord.BroadcastInvoker
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T-E2-5:ManscdpRouter 层 busy 分支专项测试(iOS 场景对齐)。
 *
 * 已有 [BroadcastSubRouterTest.broadcast_when_busy_replies_error_busy] 覆盖了 fake busy=true 路径,
 * 本测试从 iOS 侧的视角补:
 *   - busy 时 Result=ERROR;body 含 <Reason> 或统一 ERROR + 不发 INVITE
 *   - busy 从 true → false 转换过来后同 XML 应能通(证明 gate 是幂等条件评估)
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ManscdpRouterBroadcastBusyTest {

    private class Recorder : BroadcastInvoker {
        var invoked = 0
        override suspend fun fireBroadcastInvite(sourceId: String, platformUri: String, targetId: String) {
            invoked += 1
        }
    }

    private val broadcastXml = "<?xml version=\"1.0\"?><Notify><CmdType>Broadcast</CmdType><SN>7</SN>" +
        "<SourceID>34020000002000000001</SourceID>" +
        "<TargetID>34020000001110000001</TargetID></Notify>"

    @Test
    fun recording_active_returns_error_and_skips_invite() = runTest {
        val f = SubRouterTestFixtures.newFixture(this)
        val invoker = Recorder()
        var busy = true // 模拟平台媒体资源冲突
        val r = BroadcastSubRouter(f.ctx, invoker, broadcastBusy = { busy })

        assertTrue(r.handle("Broadcast", broadcastXml, fromUri = null))
        runCurrent()

        assertEquals(0, invoker.invoked, "busy 时不应触发 fireBroadcastInvite")
        val body = f.transport.sent.first().body.decodeToString()
        assertTrue(body.contains("<Result>ERROR</Result>"), "应回 ERROR: $body")
        assertTrue(body.contains("<CmdType>Broadcast</CmdType>"))
        // busy 消退后再来一发,应能通
        busy = false
        assertTrue(r.handle("Broadcast", broadcastXml, fromUri = null))
        runCurrent()
        assertEquals(1, invoker.invoked, "gate 松开后 broadcast 应能落地一次")
    }

    @Test
    fun busy_response_is_synchronous_with_incoming() = runTest {
        val f = SubRouterTestFixtures.newFixture(this)
        val invoker = Recorder()
        val r = BroadcastSubRouter(f.ctx, invoker, broadcastBusy = { true })
        assertTrue(r.handle("Broadcast", broadcastXml, fromUri = null))
        runCurrent()

        // busy 分支下也要发 Broadcast Response(不能沉默),平台需要看到 ERROR 才会 retry
        assertEquals(1, f.transport.sent.size, "busy 分支也必须发 Broadcast Response(不沉默)")
    }
}
