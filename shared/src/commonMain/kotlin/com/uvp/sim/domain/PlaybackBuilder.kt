package com.uvp.sim.domain

import com.uvp.sim.recording.RecordingFile
import com.uvp.sim.sip.PlaybackOffer

/**
 * PLAYBACK 推流构造器(M2 D 块)。
 *
 * SimulatorEngine 不直接 new PlaybackEngine,通过这个 builder 解耦:
 *   - androidMain 里 ViewModel 拼装好 [com.uvp.sim.recording.PlaybackEngine] 注入
 *   - commonTest 用 fake 实现验 handleInvite 流程
 */
interface PlaybackBuilder {
    /**
     * 构造一个回放会话。返回 null 表示构造失败(平台 487)。
     *
     * @param offer 平台 PLAYBACK SDP 解析后的 offer
     * @param segments 命中区间的录像清单(已按 startTimeMs 升序)
     * @param ssrc 协商好的 10 位 SSRC 字符串(GB28181 PLAYBACK 首位 1)
     */
    suspend fun build(
        offer: PlaybackOffer,
        segments: List<RecordingFile>,
        ssrc: String
    ): PlaybackSession?
}

/**
 * 一次回放推流的运行句柄。SimulatorEngine 在 200 OK 之后 launch run(),
 * BYE 时调 cancel()。
 */
interface PlaybackSession {
    /** 设备本地 RTP 监听端口,塞入 SDP answer 的 m=video <port>。 */
    val localRtpPort: Int

    /** 推流主循环。完成后正常返回,异常往外抛。 */
    suspend fun run()

    /** 中止推流,关闭底层 RTP / demux。可重入。 */
    suspend fun cancel()
}
