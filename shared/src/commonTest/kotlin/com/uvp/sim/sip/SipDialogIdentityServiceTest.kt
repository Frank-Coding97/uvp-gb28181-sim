package com.uvp.sim.sip

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * [SipDialogIdentityService] 单测(Wave 2 PR-SN-IDENTITY,2026-06-26)。
 *
 * 验收要点:
 *   - happy path:nextRegister 连续调返回单调递增 cseq
 *   - 三类独立:register cseq=1 时 invite cseq 也从 1 起,互不串
 *   - 并发:100 协程同时调 nextInvite,断言所有 cseq 唯一 + callId 唯一(toSet().size == 100)
 *   - callId 不被预测:1000 个 callId 全部唯一(SecureRandom 性质,Random.Default 派生)
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SipDialogIdentityServiceTest {

    private fun newService(): DefaultSipDialogIdentityService =
        DefaultSipDialogIdentityService(localIp = "192.168.1.50")

    // ---------- happy path ----------

    @Test
    fun nextRegister_monotonic_cseq() = runTest {
        val svc = newService()
        val first = svc.nextRegister()
        val second = svc.nextRegister()
        val third = svc.nextRegister()
        assertEquals(1L, first.cseq, "首次 nextRegister cseq 应为 1")
        assertEquals(2L, second.cseq)
        assertEquals(3L, third.cseq)
        assertEquals(1L, first.sn, "sn 也单调递增 from 1")
        assertEquals(2L, second.sn)
    }

    @Test
    fun nextMessageNotify_monotonic_cseq() = runTest {
        val svc = newService()
        repeat(5) { i ->
            val id = svc.nextMessageNotify()
            assertEquals((i + 1).toLong(), id.cseq, "第 ${i + 1} 次 nextMessageNotify cseq 应为 ${i + 1}")
        }
    }

    @Test
    fun nextInvite_monotonic_cseq() = runTest {
        val svc = newService()
        repeat(5) { i ->
            val id = svc.nextInvite()
            assertEquals((i + 1).toLong(), id.cseq)
        }
    }

    // ---------- 三类独立 ----------

    @Test
    fun three_pools_independent_cseq() = runTest {
        val svc = newService()
        val r1 = svc.nextRegister()
        val r2 = svc.nextRegister()
        val m1 = svc.nextMessageNotify()
        val i1 = svc.nextInvite()
        val i2 = svc.nextInvite()
        val i3 = svc.nextInvite()
        // register 推到 2,notify 推到 1,invite 推到 3 — 三套各自独立
        assertEquals(1L, r1.cseq)
        assertEquals(2L, r2.cseq)
        assertEquals(1L, m1.cseq, "MessageNotify cseq 必须从 1 起(不被 register 推进)")
        assertEquals(1L, i1.cseq, "Invite cseq 必须从 1 起(不被 register/notify 推进)")
        assertEquals(2L, i2.cseq)
        assertEquals(3L, i3.cseq)
    }

    @Test
    fun three_pools_independent_sn() = runTest {
        val svc = newService()
        repeat(3) { svc.nextRegister() }
        repeat(5) { svc.nextMessageNotify() }
        repeat(2) { svc.nextInvite() }
        // 再各调一次,验证 sn 是当前池子的 next,不被其他池子影响
        val r = svc.nextRegister()
        val m = svc.nextMessageNotify()
        val i = svc.nextInvite()
        assertEquals(4L, r.sn, "Register pool 第 4 个 sn")
        assertEquals(6L, m.sn, "MessageNotify pool 第 6 个 sn")
        assertEquals(3L, i.sn, "Invite pool 第 3 个 sn")
    }

    // ---------- callId / fromTag 唯一性 ----------

    @Test
    fun callId_format_contains_localIp() = runTest {
        val svc = DefaultSipDialogIdentityService(localIp = "10.0.0.42")
        val id = svc.nextRegister()
        assertTrue(
            id.callId.endsWith("@10.0.0.42"),
            "callId 应该以 `@<localIp>` 结尾,实际 = ${id.callId}",
        )
        val hexPart = id.callId.substringBefore('@')
        assertEquals(16, hexPart.length, "16 hex char callId 前缀(8 bytes → 16 hex)")
        assertTrue(hexPart.all { it in '0'..'9' || it in 'a'..'f' }, "callId 应为 hex,实际 = $hexPart")
    }

    @Test
    fun fromTag_format_hex_10chars() = runTest {
        val svc = newService()
        val id = svc.nextRegister()
        assertEquals(10, id.fromTag.length, "fromTag 应为 10 hex chars(5 bytes)")
        assertTrue(id.fromTag.all { it in '0'..'9' || it in 'a'..'f' }, "fromTag 应为 hex")
    }

    @Test
    fun callId_1000_consecutive_no_collision() = runTest {
        val svc = newService()
        val ids = (1..1000).map { svc.nextInvite().callId }
        val unique = ids.toSet()
        assertEquals(
            1000, unique.size,
            "1000 个 callId 必须全部唯一(SecureRandom 派生),实际重复 ${1000 - unique.size} 个",
        )
    }

    @Test
    fun fromTag_1000_consecutive_no_collision() = runTest {
        val svc = newService()
        val tags = (1..1000).map { svc.nextInvite().fromTag }
        val unique = tags.toSet()
        // 10 hex chars = 40 bits = 1.1e12 空间,1000 抽样冲突几乎不可能;但保守允许 1 个偶发
        assertTrue(
            unique.size >= 999,
            "1000 个 fromTag 至少 999 个唯一(实际唯一数 = ${unique.size})",
        )
    }

    @Test
    fun callId_consecutive_different() = runTest {
        // 连续两次 callId 不应该相同(基础健康检查)
        val svc = newService()
        val a = svc.nextRegister().callId
        val b = svc.nextRegister().callId
        assertNotEquals(a, b, "连续两次 callId 不应相同")
    }

    // ---------- 并发交错 ----------

    @Test
    fun concurrent_100_nextInvite_all_unique() = runTest {
        // 用 Dispatchers.Default(真实并发)而非 runTest 默认的 TestDispatcher,
        // 才能撞出 race condition;Mutex 内部 withLock 保证原子性。
        val svc = newService()
        val results = withContext(Dispatchers.Default) {
            (1..100).map { idx ->
                async { svc.nextInvite() }
            }.awaitAll()
        }
        val cseqs = results.map { it.cseq }.toSet()
        val callIds = results.map { it.callId }.toSet()
        val sns = results.map { it.sn }.toSet()
        assertEquals(100, cseqs.size, "100 并发 nextInvite 的 cseq 必须全唯一,实际 = ${cseqs.size}")
        assertEquals(100, callIds.size, "100 并发 nextInvite 的 callId 必须全唯一,实际 = ${callIds.size}")
        assertEquals(100, sns.size, "100 并发 nextInvite 的 sn 必须全唯一,实际 = ${sns.size}")
        // cseq 集合应该正好是 1..100 (单调递增 + 唯一)
        assertEquals((1L..100L).toSet(), cseqs, "cseq 集合应为 1..100")
    }

    @Test
    fun concurrent_three_pools_mixed_no_crosstalk() = runTest {
        // 三类各 50 协程混合并发,验证池子互不串
        val svc = newService()
        val regResults = mutableListOf<RegisterDialogIdentity>()
        val notifyResults = mutableListOf<MessageNotifyIdentity>()
        val inviteResults = mutableListOf<InviteDialogIdentity>()
        val regLock = kotlinx.coroutines.sync.Mutex()
        val notifyLock = kotlinx.coroutines.sync.Mutex()
        val inviteLock = kotlinx.coroutines.sync.Mutex()

        withContext(Dispatchers.Default) {
            val jobs = mutableListOf<kotlinx.coroutines.Deferred<Unit>>()
            repeat(50) {
                jobs += async {
                    val r = svc.nextRegister()
                    regLock.lock(); try { regResults += r } finally { regLock.unlock() }
                }
                jobs += async {
                    val n = svc.nextMessageNotify()
                    notifyLock.lock(); try { notifyResults += n } finally { notifyLock.unlock() }
                }
                jobs += async {
                    val i = svc.nextInvite()
                    inviteLock.lock(); try { inviteResults += i } finally { inviteLock.unlock() }
                }
            }
            jobs.awaitAll()
        }

        assertEquals(50, regResults.size)
        assertEquals(50, notifyResults.size)
        assertEquals(50, inviteResults.size)

        assertEquals(
            (1L..50L).toSet(), regResults.map { it.cseq }.toSet(),
            "Register pool cseq 必须是 1..50,跟 notify/invite 不串",
        )
        assertEquals(
            (1L..50L).toSet(), notifyResults.map { it.cseq }.toSet(),
            "MessageNotify pool cseq 必须是 1..50",
        )
        assertEquals(
            (1L..50L).toSet(), inviteResults.map { it.cseq }.toSet(),
            "Invite pool cseq 必须是 1..50",
        )

        // callId 总共 150 个,跨池也应该全唯一
        val allCallIds = (regResults.map { it.callId } +
            notifyResults.map { it.callId } +
            inviteResults.map { it.callId }).toSet()
        assertEquals(150, allCallIds.size, "150 个跨池 callId 必须全唯一")
    }
}
