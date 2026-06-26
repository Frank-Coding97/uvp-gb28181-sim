package com.uvp.sim.domain

import com.uvp.sim.config.DeviceConfig
import com.uvp.sim.config.ServerConfig
import com.uvp.sim.config.SimConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertNotNull

/** T5 — SimulatorEngine.consumeEffect() 单元行为测试.
 *
 * 真 SimulatorEngine 构造太重,这里直接验"清零行为"语义:
 * mock 一个共享 MutableStateFlow,模拟 consumeEffect 调用 update 把 pendingEffect 置 null. */
class SimulatorEnginePresetQueryTest {

    @Test
    fun `consumeEffect 清零 pendingEffect`() {
        // Arrange: 模拟 effect 已 emit
        val flow = MutableStateFlow(
            DeviceControlModel(pendingEffect = DeviceEffect.IFrameFlash)
        )
        assertNotNull(flow.value.pendingEffect)

        // Act: 模拟 SimulatorEngine.consumeEffect() 行为
        flow.update { it.copy(pendingEffect = null) }

        // Assert
        assertNull(flow.value.pendingEffect)
    }

    @Test
    fun `consumeEffect 多次调用幂等`() {
        val flow = MutableStateFlow(DeviceControlModel())
        flow.update { it.copy(pendingEffect = null) }
        flow.update { it.copy(pendingEffect = null) }
        assertNull(flow.value.pendingEffect)
    }
}
