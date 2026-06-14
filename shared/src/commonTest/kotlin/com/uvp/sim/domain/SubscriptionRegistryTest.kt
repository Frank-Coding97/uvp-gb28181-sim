package com.uvp.sim.domain

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SubscriptionRegistryTest {

    private fun dialog(
        callId: String = "call1@host",
        interval: Int = 5,
        expires: Int = 30
    ) = SubscriptionDialog(
        kind = "MobilePosition",
        subscriberUri = "sip:platform@192.168.1.100:5060",
        callId = callId,
        fromTag = "platform-tag",
        toTag = "device-tag",
        intervalSeconds = interval,
        expiresSeconds = expires,
        remainingSeconds = expires
    )

    @Test
    fun activatePublishesActiveSnapshot() = runTest {
        val registry = SubscriptionRegistry(this)
        registry.activate(dialog()) {}
        val snap = registry.subscriptions.value["MobilePosition"]
        assertTrue(snap != null && snap.active)
        assertEquals("sip:platform@192.168.1.100:5060", snap.subscriber)
        assertEquals(30, snap.expiresSeconds)
    }

    @Test
    fun notifyFiresAfterInterval() = runTest {
        val registry = SubscriptionRegistry(this)
        var notifyCount = 0
        registry.activate(dialog(interval = 5)) { notifyCount++ }
        advanceTimeBy(5_001)
        assertEquals(1, notifyCount)
        advanceTimeBy(5_000)
        assertEquals(2, notifyCount)
    }

    @Test
    fun expiryAutoClears() = runTest {
        val registry = SubscriptionRegistry(this)
        registry.activate(dialog(expires = 3)) {}
        advanceTimeBy(3_001)
        val snap = registry.subscriptions.value["MobilePosition"]
        assertNull(snap)
    }

    @Test
    fun refreshResetsRemaining() = runTest {
        val registry = SubscriptionRegistry(this)
        registry.activate(dialog(expires = 10)) {}
        advanceTimeBy(5_000)
        registry.refresh("call1@host", 20)
        val snap = registry.subscriptions.value["MobilePosition"]
        assertEquals(20, snap?.remainingSeconds)
    }

    @Test
    fun cancelStopsNotify() = runTest {
        val registry = SubscriptionRegistry(this)
        var notifyCount = 0
        registry.activate(dialog(interval = 2)) { notifyCount++ }
        advanceTimeBy(2_001)
        assertEquals(1, notifyCount)
        registry.cancel("call1@host")
        advanceTimeBy(4_000)
        assertEquals(1, notifyCount)
        assertNull(registry.subscriptions.value["MobilePosition"])
    }

    @Test
    fun cancelAllClearsEverything() = runTest {
        val registry = SubscriptionRegistry(this)
        registry.activate(dialog(callId = "a")) {}
        registry.activate(dialog(callId = "b")) {}
        registry.cancelAll()
        assertTrue(registry.subscriptions.value.isEmpty())
    }

    @Test
    fun multipleSubscribersSameKindShowsFirst() = runTest {
        val registry = SubscriptionRegistry(this)
        registry.activate(dialog(callId = "first")) {}
        registry.activate(dialog(callId = "second").copy(
            subscriberUri = "sip:other@host"
        )) {}
        val snap = registry.subscriptions.value["MobilePosition"]
        assertEquals("sip:platform@192.168.1.100:5060", snap?.subscriber)
    }

    @Test
    fun catalogActivateDoesNotStartHeartbeat() = runTest {
        val registry = SubscriptionRegistry(this)
        var notifyCount = 0
        val cat = dialog(callId = "cat-call").copy(kind = "Catalog", intervalSeconds = 0, expiresSeconds = 86400, remainingSeconds = 86400)
        registry.activate(cat) { notifyCount++ }
        // Catalog 注册后,即使 advanceTimeBy 也不应触发 onNotify
        advanceTimeBy(60_000)
        assertEquals(0, notifyCount)
        // 但订阅快照仍 active
        val snap = registry.subscriptions.value["Catalog"]
        assertTrue(snap != null && snap.active)
    }

    @Test
    fun catalogExpiryStillCountdownsAndClears() = runTest {
        val registry = SubscriptionRegistry(this)
        val cat = dialog(callId = "cat-call").copy(
            kind = "Catalog",
            intervalSeconds = 0,
            expiresSeconds = 3,
            remainingSeconds = 3
        )
        registry.activate(cat) {}
        assertTrue(registry.subscriptions.value["Catalog"]?.active == true)
        advanceTimeBy(3_001)
        assertNull(registry.subscriptions.value["Catalog"])
    }

    @Test
    fun bumpNotifyIncrementsCounterAndReturnsDialog() = runTest {
        val registry = SubscriptionRegistry(this)
        val cat = dialog(callId = "cat-call").copy(kind = "Catalog", intervalSeconds = 0, expiresSeconds = 60, remainingSeconds = 60)
        registry.activate(cat) {}
        val bumped = registry.bumpNotify("cat-call")
        assertTrue(bumped != null)
        assertEquals(1, bumped!!.notifyCount)
        assertEquals(1, bumped.cseqNotify)
        // 第二次再 bump
        val bumped2 = registry.bumpNotify("cat-call")
        assertEquals(2, bumped2!!.notifyCount)

        val snap = registry.subscriptions.value["Catalog"]
        assertEquals(2, snap?.notifyCount)
    }

    @Test
    fun bumpNotifyReturnsNullForUnknownCallId() = runTest {
        val registry = SubscriptionRegistry(this)
        assertNull(registry.bumpNotify("nope"))
    }

    @Test
    fun dialogsByKindFiltersCorrectly() = runTest {
        val registry = SubscriptionRegistry(this)
        registry.activate(dialog(callId = "mp1")) {}
        val cat = dialog(callId = "cat1").copy(kind = "Catalog", intervalSeconds = 0)
        registry.activate(cat) {}

        assertEquals(1, registry.dialogsByKind("MobilePosition").size)
        assertEquals(1, registry.dialogsByKind("Catalog").size)
        assertEquals(0, registry.dialogsByKind("Other").size)

        registry.cancelAll()
    }
}
