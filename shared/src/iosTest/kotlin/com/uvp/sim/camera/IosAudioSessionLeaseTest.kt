package com.uvp.sim.camera

import kotlin.test.Test
import kotlin.test.assertEquals

class IosAudioSessionLeaseTest {

    @Test
    fun release_is_idempotent() {
        var releaseCalls = 0
        val lease = IosAudioSessionLease { releaseCalls += 1 }

        lease.release()
        lease.release()

        assertEquals(1, releaseCalls)
    }
}
