package com.uvp.sim.ui.simulate.scenekit

import kotlin.math.PI

/**
 * v1.3-C · SceneKitSelfTest — 6.5s 开机自检轨迹计算(时段位与 Android CameraGlbView 对齐).
 *
 * 只输出 sample(pan/tilt),不动 SceneKit 场景;dispatcher / Compose 层拿到样本
 * 后调 `SceneKitEffectDispatcher.syncPtz(pan, tilt, 1f)` 驱动.
 *
 * 时间线(秒):
 * ```
 * 0.0 ~ 0.5   停顿
 * 0.5 ~ 1.5   pan  0 → -50 (左)
 * 1.5 ~ 2.5   pan -50 → +50 (右)
 * 2.5 ~ 3.0   pan +50 → 0
 * 3.0 ~ 3.5   停顿
 * 3.5 ~ 4.5   tilt 0 → +25 (上)
 * 4.5 ~ 5.5   tilt +25 → -25 (下)
 * 5.5 ~ 6.0   tilt -25 → 0
 * ≥ 6.0       done
 * ```
 *
 * spec §5 AC-9 nice-to-have. plan §6.1 SceneKitSelfTest.kt.
 */
object SceneKitSelfTest {

    /** 完成阈值:6.5s(留 0.5s buffer 给最后一段 ease). */
    const val TOTAL_DURATION_SEC: Double = 6.5

    /**
     * 采样 t 秒时的 pan/tilt.
     *
     * @param tSec 自检开始后的秒数
     * @return (pan, tilt, done)
     */
    fun sample(tSec: Double): Sample {
        val t = tSec.coerceAtLeast(0.0)
        val panAmp = 50f
        val tiltAmp = 25f
        var pan = 0f
        var tilt = 0f
        var done = false
        when {
            t < 0.5 -> { pan = 0f; tilt = 0f }
            t < 1.5 -> { pan = lerpEase(0f, -panAmp, ((t - 0.5) / 1.0).toFloat()); tilt = 0f }
            t < 2.5 -> { pan = lerpEase(-panAmp, panAmp, ((t - 1.5) / 1.0).toFloat()); tilt = 0f }
            t < 3.0 -> { pan = lerpEase(panAmp, 0f, ((t - 2.5) / 0.5).toFloat()); tilt = 0f }
            t < 3.5 -> { pan = 0f; tilt = 0f }
            t < 4.5 -> { pan = 0f; tilt = lerpEase(0f, tiltAmp, ((t - 3.5) / 1.0).toFloat()) }
            t < 5.5 -> { pan = 0f; tilt = lerpEase(tiltAmp, -tiltAmp, ((t - 4.5) / 1.0).toFloat()) }
            t < 6.0 -> { pan = 0f; tilt = lerpEase(-tiltAmp, 0f, ((t - 5.5) / 0.5).toFloat()) }
            else -> {
                pan = 0f
                tilt = 0f
                done = true
            }
        }
        return Sample(pan = pan, tilt = tilt, done = done)
    }

    /** cosine ease-in-out 缓动. */
    private fun lerpEase(a: Float, b: Float, tRaw: Float): Float {
        val t = tRaw.coerceIn(0f, 1f)
        val eased = 0.5f - 0.5f * kotlin.math.cos(PI.toFloat() * t)
        return a + (b - a) * eased
    }

    data class Sample(val pan: Float, val tilt: Float, val done: Boolean)
}
