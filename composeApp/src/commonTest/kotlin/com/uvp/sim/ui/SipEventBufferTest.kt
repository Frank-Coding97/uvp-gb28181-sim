package com.uvp.sim.ui

import com.uvp.sim.domain.SimEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SipEventBufferTest {

    @Test
    fun clear_removes_existing_events_and_accepts_later_events() {
        val buffer = SipEventBuffer()
        val beforeClear = SimEvent.RegistrationStarted("platform", timestampMs = 1)
        val afterClear = SimEvent.HeartbeatSent(sequence = 2, timestampMs = 2)

        buffer.append(beforeClear)
        buffer.clear()

        assertTrue(buffer.events.isEmpty())

        buffer.append(afterClear)

        assertEquals(listOf(afterClear), buffer.events)
    }
}
