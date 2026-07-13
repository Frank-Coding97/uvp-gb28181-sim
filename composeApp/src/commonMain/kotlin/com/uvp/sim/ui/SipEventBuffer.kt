package com.uvp.sim.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.uvp.sim.domain.SimEvent

internal class SipEventBuffer {
    var events by mutableStateOf<List<SimEvent>>(emptyList())
        private set

    /**
     * 追加一个事件到时间线。语义上是"最新一条",但存储顺序 = **最新在头**,
     * 跟 Android `SipViewModel._events`(`listOf(ev) + current`)对齐 —— 上层
     * [SipLogListView] 依赖"events[0] 是最新"这个不变量来直接渲染,无需再 reverse。
     */
    fun append(event: SimEvent) {
        events = (listOf(event) + events).take(MAX_EVENTS)
    }

    fun clear() {
        events = emptyList()
    }

    private companion object {
        const val MAX_EVENTS = 200
    }
}
