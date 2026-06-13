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
}
