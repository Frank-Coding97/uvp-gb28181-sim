package com.uvp.sim.observability

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SessionTrackerTest {

    @BeforeTest
    fun setup() = SessionTracker.resetForTest()

    @Test fun firstStartReturnsOne() {
        val store = MemorySessionStore()
        SessionTracker.install(store)
        assertEquals(1, SessionTracker.currentId)
        assertEquals(1, store.readLastSessionId())
    }

    @Test fun secondStartReturnsTwo() {
        val store = MemorySessionStore().apply { writeLastSessionId(1) }
        SessionTracker.install(store)
        assertEquals(2, SessionTracker.currentId)
        assertEquals(2, store.readLastSessionId())
    }

    @Test fun currentExposesMarkerWithReasonableTime() {
        val store = MemorySessionStore()
        SessionTracker.install(store)
        val marker = SessionTracker.current
        assertEquals(SessionTracker.currentId, marker.sessionId)
        assertTrue(marker.startedAtMs > 0, "startedAtMs not set: ${marker.startedAtMs}")
    }
}
