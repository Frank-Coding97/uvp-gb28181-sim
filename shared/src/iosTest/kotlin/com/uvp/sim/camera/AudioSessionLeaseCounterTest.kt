package com.uvp.sim.camera

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AudioSessionLeaseCounterTest {

    @Test
    fun first_acquire_activates_and_last_release_deactivates() {
        val counter = AudioSessionLeaseCounter()

        assertTrue(counter.acquire())
        assertFalse(counter.acquire())
        assertEquals(2, counter.count)

        assertFalse(counter.release())
        assertTrue(counter.release())
        assertEquals(0, counter.count)
    }

    @Test
    fun extra_release_is_clamped() {
        val counter = AudioSessionLeaseCounter()

        assertFalse(counter.release())
        assertEquals(0, counter.count)
    }

    @Test
    fun broadcast_release_keeps_uplink_lease_active() {
        val counter = AudioSessionLeaseCounter()

        assertTrue(counter.acquire(), "uplink acquires and activates the shared session")
        assertFalse(counter.acquire(), "broadcast reuses the active shared session")
        assertFalse(counter.release(), "stopping broadcast must not deactivate uplink")
        assertEquals(1, counter.count)
        assertTrue(counter.release(), "the final uplink release deactivates the session")
    }

    @Test
    fun uplink_release_keeps_broadcast_lease_active() {
        val counter = AudioSessionLeaseCounter()

        assertTrue(counter.acquire(), "broadcast acquires and activates the shared session")
        assertFalse(counter.acquire(), "uplink reuses the active shared session")
        assertFalse(counter.release(), "stopping uplink must not deactivate broadcast")
        assertEquals(1, counter.count)
        assertTrue(counter.release(), "the final broadcast release deactivates the session")
    }
}
