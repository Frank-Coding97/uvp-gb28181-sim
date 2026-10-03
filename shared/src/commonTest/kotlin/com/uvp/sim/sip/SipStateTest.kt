package com.uvp.sim.sip

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [SipState.hasActiveRegistration] 真值表(2026-10-03)。
 *
 * 它是"重建会话时走 `disconnect()` 还是 `cancelConnect()`"的唯一判据
 * (见 `AppEngine.updateConfig`),判错一边各有代价:
 * - **漏判**(把无会话的态算成有)→ 给一个平台侧不存在的会话发 `Expires=0`,本机空等 3s;
 * - **多判**(把有会话的态算成无)→ 静默清场,平台侧留下僵尸在线记录,要到 Expires 过期才消失。
 *
 * UI 侧 `SipStateDto.hasActiveRegistration()` 是同一口径的独立实现,
 * 对应测试在 composeApp 的 `QrProvisionUiTest`。
 */
class SipStateTest {

    @Test
    fun only_registering_registered_and_in_call_hold_a_registration_session() {
        val active = SipState.entries.filter { it.hasActiveRegistration() }
        assertEquals(
            listOf(SipState.Registering, SipState.Registered, SipState.InCall),
            active,
        )
    }

    @Test
    fun disconnected_and_failed_have_nothing_to_unregister() {
        assertEquals(false, SipState.Disconnected.hasActiveRegistration())
        assertEquals(false, SipState.Failed.hasActiveRegistration())
    }
}
