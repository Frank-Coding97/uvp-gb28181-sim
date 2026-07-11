package com.uvp.sim.observability

import platform.Foundation.NSUserDefaults
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class IosSessionStoreTest {

    private val suiteName = "com.uvp.sim.observability.IosSessionStoreTest"
    private lateinit var defaults: NSUserDefaults

    @BeforeTest
    fun setup() {
        defaults = NSUserDefaults(suiteName = suiteName)!!
        defaults.removePersistentDomainForName(suiteName)
    }

    @AfterTest
    fun tearDown() {
        defaults.removePersistentDomainForName(suiteName)
    }

    @Test
    fun readEmpty_returnsZero() {
        val store = IosSessionStore(defaults)
        assertEquals(0, store.readLastSessionId())
    }

    @Test
    fun write_thenRead_roundTrips() {
        val store = IosSessionStore(defaults)
        store.writeLastSessionId(7)
        assertEquals(7, store.readLastSessionId())
    }

    @Test
    fun sessionTrackerInstall_incrementsFromPersistedValue() {
        val store = IosSessionStore(defaults).apply { writeLastSessionId(4) }
        SessionTracker.resetForTest()
        SessionTracker.install(store)
        assertEquals(5, SessionTracker.currentId)
        assertEquals(5, store.readLastSessionId())
    }
}
