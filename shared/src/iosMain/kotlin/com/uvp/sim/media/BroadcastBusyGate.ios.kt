package com.uvp.sim.media

import com.uvp.sim.camera.IosAudioStreamer

/**
 * iOS actual — 语音广播 busy 检测(T-E2-2)。
 *
 * 读 [IosAudioStreamer.activeCount] 判断"当前有活跃 audio tap"(即录像在采集 mic)。
 * 若有,broadcast 走 ERROR busy 分支(plan §5 Q4 排队策略),不发 INVITE。
 *
 * 与老的 `broadcastBusy = { broadcast.current.value != null }`(防第二路 broadcast)
 * 语义不冲突,由 Router 分支叠加使用(见 T-E2-5 wire)。
 */
actual object BroadcastBusyGate {
    actual fun isBusy(): Boolean = IosAudioStreamer.activeCount > 0

    actual fun busyReason(): String? =
        if (IosAudioStreamer.activeCount > 0) "recording-active" else null
}
