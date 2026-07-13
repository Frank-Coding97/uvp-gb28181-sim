package com.uvp.sim.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import platform.posix.SIGPIPE
import platform.posix.raise

class IosSigpipeGuardTest {

    @Test
    fun installRunsNativeHandlerOnlyOnce() {
        val gate = SigpipeIgnoreGate()
        var installations = 0

        assertTrue(gate.install { installations += 1 })
        assertFalse(gate.install { installations += 1 })

        assertEquals(1, installations)
    }

    @Test
    fun installAllowsProcessToSurviveSigpipe() {
        IosSigpipeGuard.install()

        assertEquals(0, raise(SIGPIPE))
    }
}
