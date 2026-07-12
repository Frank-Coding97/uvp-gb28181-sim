package com.uvp.sim.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.uvp.sim.domain.SimEvent

internal class SipEventBuffer {
    var events by mutableStateOf<List<SimEvent>>(emptyList())
        private set

    fun append(event: SimEvent) {
        events = (events + event).takeLast(MAX_EVENTS)
    }

    fun clear() {
        events = emptyList()
    }

    private companion object {
        const val MAX_EVENTS = 200
    }
}
