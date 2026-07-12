package com.uvp.sim.domain.coord.manscdp

import com.uvp.sim.domain.coord.BroadcastInvoker
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T-E4-0:iOS 真机场景 busy 分支专项(与 T-E2-5 语义互补,聚焦"多个 SN + 平台重试"节奏)。
 *
 * 场景:
 *   1. 平台媒体资源忙 → 平台喊 SN=1 → busy 分支 ERROR
 *   2. 平台重试 SN=2 (5-10s 后) → 仍 busy → ERROR
 *   3. 资源释放 (busy = false) → 平台 SN=3 → OK + 发 INVITE
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ManscdpRouterBroadcastBusyRealScenarioTest {

    private class Recorder : BroadcastInvoker {
        var invoked = 0
        val calledSourceIds = mutableListOf<String>()
        override suspend fun fireBroadcastInvite(sourceId: String, platformUri: String, targetId: String):
            com.uvp.sim.domain.coord.BroadcastInviteStart {
            invoked += 1
            calledSourceIds += sourceId
            return com.uvp.sim.domain.coord.BroadcastInviteStart.Started
        }
    }

    private fun broadcastXml(sn: Int) = "<?xml version=\"1.0\"?><Notify><CmdType>Broadcast</CmdType>" +
        "<SN>$sn</SN>" +
        "<SourceID>34020000002000000001</SourceID>" +
        "<TargetID>34020000001110000001</TargetID></Notify>"

    @Test
    fun platform_retry_sequence_busy_busy_then_ok() = runTest {
        val f = SubRouterTestFixtures.newFixture(this)
        val invoker = Recorder()
        var busy = true
        val r = BroadcastSubRouter(f.ctx, invoker, broadcastBusy = { busy })

        // SN=1 → busy → ERROR
        r.handle("Broadcast", broadcastXml(1), fromUri = null)
        runCurrent()
        assertEquals(0, invoker.invoked, "SN=1 期间资源忙,不应发 INVITE")

        // SN=2 平台重试 → busy → ERROR
        r.handle("Broadcast", broadcastXml(2), fromUri = null)
        runCurrent()
        assertEquals(0, invoker.invoked, "SN=2 平台重试期间录像仍在,不应发 INVITE")
        assertEquals(2, f.transport.sent.size, "两次 busy 各回一次 Broadcast Response ERROR")

        // 录像结束
        busy = false

        // SN=3 → OK → INVITE 触发
        r.handle("Broadcast", broadcastXml(3), fromUri = null)
        runCurrent()
        assertEquals(1, invoker.invoked, "gate 松开后应触发 INVITE 一次")
        assertEquals(3, f.transport.sent.size, "总共 3 次 Broadcast Response")

        // 验前两次是 ERROR,第三次是 OK
        val bodies = f.transport.sent.map { it.body.decodeToString() }
        assertTrue(bodies[0].contains("<Result>ERROR</Result>"))
        assertTrue(bodies[1].contains("<Result>ERROR</Result>"))
        assertTrue(bodies[2].contains("<Result>OK</Result>"))
    }
}
