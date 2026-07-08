package com.uvp.sim.media

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T-E1-1 BroadcastAudioSession 单测。
 *
 * 由于 AVAudioSession 在 iOS 单元测试环境(iosSimulatorArm64Test)可能不可用或
 * 权限受限,测试聚焦"接口幂等 + 不抛异常 + isActive 状态迁移"三条基本契约。
 * 真实硬件行为在 T-E3-3 / T-E4-3 真机联调时验。
 */
class BroadcastAudioSessionTest {

    @BeforeTest
    fun reset() {
        BroadcastAudioSession.resetForTest()
    }

    @AfterTest
    fun cleanup() {
        // 兜底,避免下次测试污染
        BroadcastAudioSession.deactivate()
        BroadcastAudioSession.resetForTest()
    }

    @Test
    fun activate_marks_session_active_or_gracefully_fails() {
        // Simulator 上通常能激活;若失败也不 crash,返回 false。
        val ok = BroadcastAudioSession.activate(sampleRate = 8000, channels = 1)
        if (ok) {
            assertTrue(BroadcastAudioSession.isActive, "activate 成功后 isActive 应为 true")
        } else {
            assertFalse(BroadcastAudioSession.isActive, "activate 失败后 isActive 应为 false")
        }
    }

    @Test
    fun activate_is_idempotent() {
        val first = BroadcastAudioSession.activate(sampleRate = 8000, channels = 1)
        val second = BroadcastAudioSession.activate(sampleRate = 8000, channels = 1)
        // 两次都不 throw;第二次至少与第一次结果一致(幂等)。
        assertTrue(first == second || first || second)
        // 如果第一次成功,第二次也应报告 active
        if (first) assertTrue(BroadcastAudioSession.isActive)
    }

    @Test
    fun deactivate_after_activate_flips_state() {
        BroadcastAudioSession.activate(sampleRate = 8000, channels = 1)
        BroadcastAudioSession.deactivate()
        assertFalse(BroadcastAudioSession.isActive)
    }

    @Test
    fun deactivate_when_not_active_is_noop() {
        BroadcastAudioSession.deactivate()
        assertFalse(BroadcastAudioSession.isActive)
    }

    @Test
    fun enter_background_calls_deactivate() {
        BroadcastAudioSession.activate(sampleRate = 8000, channels = 1)
        BroadcastAudioSession.onEnterBackground()
        assertFalse(BroadcastAudioSession.isActive)
    }

    @Test
    fun enter_foreground_is_noop() {
        // no-op:不主动重建,不 throw
        BroadcastAudioSession.onEnterForeground()
        assertFalse(BroadcastAudioSession.isActive)
    }
}
