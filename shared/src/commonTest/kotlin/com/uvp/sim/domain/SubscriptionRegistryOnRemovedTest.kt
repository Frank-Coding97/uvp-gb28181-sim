package com.uvp.sim.domain

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * plan §3.2 P0 fix 回归 — SubscriptionRegistry.onDialogRemoved 钩子必须由 3 条移除路径触发,
 * 且 Alarm onExpire 现有语义(cb 内可读 subscriberUri)不能被破坏。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SubscriptionRegistryOnRemovedTest {

    private fun dialog(
        callId: String = "call1@host",
        kind: String = "MobilePosition",
        expires: Int = 30,
    ) = SubscriptionDialog(
        kind = kind,
        subscriberUri = "sip:platform@192.168.1.100:5060",
        callId = callId,
        fromTag = "platform-tag",
        toTag = "device-tag",
        intervalSeconds = 5,
        expiresSeconds = expires,
        remainingSeconds = expires,
    )

    @Test
    fun cancelTriggersOnDialogRemoved() = runTest {
        val removed = mutableListOf<SubscriptionDialog>()
        val registry = SubscriptionRegistry(this) { removed += it }
        registry.activate(dialog()) {}

        registry.cancel("call1@host")

        assertEquals(1, removed.size, "cancel 必须触发一次 onDialogRemoved")
        assertEquals("MobilePosition", removed[0].kind)
        assertEquals("sip:platform@192.168.1.100:5060", removed[0].subscriberUri)
    }

    @Test
    fun naturalExpiryTriggersOnDialogRemoved() = runTest {
        // P0 关键回归 — 自然过期路径也必须触发 onDialogRemoved
        val removed = mutableListOf<SubscriptionDialog>()
        val registry = SubscriptionRegistry(this) { removed += it }
        registry.activate(dialog(expires = 2)) {}

        advanceTimeBy(3_000L) // 倒计时归零 + delay(1000) 保底

        assertEquals(1, removed.size, "自然过期必须触发 onDialogRemoved (P0 fix)")
        assertEquals("MobilePosition", removed[0].kind)
    }

    @Test
    fun cancelAllTriggersOnDialogRemovedForEach() = runTest {
        val removed = mutableListOf<SubscriptionDialog>()
        val registry = SubscriptionRegistry(this) { removed += it }
        registry.activate(dialog(callId = "c1")) {}
        registry.activate(dialog(callId = "c2")) {}
        registry.activate(dialog(callId = "c3", kind = "Alarm")) {}

        registry.cancelAll()

        assertEquals(3, removed.size, "cancelAll 必须对每个 dialog 各触发一次")
        assertEquals(setOf("c1", "c2", "c3"), removed.map { it.callId }.toSet())
    }

    @Test
    fun refreshDoesNotTriggerOnDialogRemoved() = runTest {
        val removed = mutableListOf<SubscriptionDialog>()
        val registry = SubscriptionRegistry(this) { removed += it }
        registry.activate(dialog()) {}

        registry.refresh("call1@host", newExpires = 60)

        assertTrue(removed.isEmpty(), "refresh 不移除 dialog,不该触发钩子")
    }

    @Test
    fun alarmOnExpireStillReadsSubscriberUriBeforeCancel() = runTest {
        // 关键 —— A 方案会破坏这条语义,B 方案(current impl)必须保住
        var capturedSubscriber: String? = null
        val registry = SubscriptionRegistry(this)
        registry.activate(
            dialog(kind = "Alarm", expires = 2),
            onExpire = { d -> capturedSubscriber = d.subscriberUri },
        ) {}

        advanceTimeBy(3_000L)

        assertNotNull(capturedSubscriber, "Alarm onExpire 触发时 dialog 必须还在(cb 先于 cancel)")
        assertEquals("sip:platform@192.168.1.100:5060", capturedSubscriber)
    }

    @Test
    fun onDialogRemovedIsOptionalNoOpWhenNullCallback() = runTest {
        // 默认 null 钩子(现有测试 fixture 传统 style)不能崩
        val registry = SubscriptionRegistry(this) // onDialogRemoved = null
        registry.activate(dialog()) {}
        registry.cancel("call1@host") // 不应抛异常
    }
}
