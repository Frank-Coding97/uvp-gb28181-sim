package com.uvp.sim.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

/**
 * 语音广播下行(§9.8)RTP 接收端,支持三种媒体传输([RtpMode]):
 *   - UDP:绑定本地 UDP 端口收数据报
 *   - TCP_ACTIVE(主力,公网常用):设备主动连平台,从 TCP 流按 RFC 4571 解帧
 *   - TCP_PASSIVE:设备监听,平台连进来,从 TCP 流解帧
 *
 * 生命周期:
 *   1. [bind] — UDP/TCP_PASSIVE 绑定本地端口(写进 SDP offer);TCP_ACTIVE 不监听返回 0
 *   2. [connect] — 仅 TCP_ACTIVE:收到平台 answer 拿到 IP:端口后主动建 TCP 连接;其它 mode no-op
 *   3. [start] — 启动接收循环(TCP_PASSIVE 在此 accept 平台连接),每包回调 onPacket(IO 线程)
 *   4. [close] — 关闭所有 socket,接收循环退出
 *
 * iOS 为 stub(本轮只做安卓/JVM)。
 */
expect class RtpReceiver(parentScope: CoroutineScope? = null) {
    /** 实际绑定的本地端口;bind 之前为 -1,TCP_ACTIVE 为 0。 */
    val localPort: Int

    suspend fun bind(mode: RtpMode): Int
    /** TCP_ACTIVE:主动连平台 [remoteHost]:[remotePort];其它 mode 无操作。 */
    suspend fun connect(remoteHost: String, remotePort: Int)
    fun start(onPacket: (RtpPacket) -> Unit): Job
    suspend fun close()
}

/**
 * RX 接收端口抽象 — 让 [com.uvp.sim.domain.SimulatorEngine] 依赖接口而非具体 expect class,
 * 从而单测可注入 fake(expect class 不可继承)。
 */
interface BroadcastRxSource {
    val localPort: Int
    suspend fun bind(mode: RtpMode): Int
    suspend fun connect(remoteHost: String, remotePort: Int)
    fun start(onPacket: (RtpPacket) -> Unit): Job
    suspend fun close()
}

/** 默认实现:包装真实 [RtpReceiver]。 */
fun realBroadcastRxSource(scope: CoroutineScope): BroadcastRxSource {
    val r = RtpReceiver(scope)
    return object : BroadcastRxSource {
        override val localPort: Int get() = r.localPort
        override suspend fun bind(mode: RtpMode): Int = r.bind(mode)
        override suspend fun connect(remoteHost: String, remotePort: Int) = r.connect(remoteHost, remotePort)
        override fun start(onPacket: (RtpPacket) -> Unit): Job = r.start(onPacket)
        override suspend fun close() = r.close()
    }
}
