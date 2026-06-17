package com.uvp.sim.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

/**
 * 语音广播下行(§9.8)RTP 接收端 — 绑定本地 UDP 端口,接收平台推来的 G.711 音频。
 *
 * Lifecycle:
 *   1. [bindLocalPort] — 绑定本地 socket(端口 0 让 OS 分配),返回真实端口写进 SDP offer
 *   2. [start] — 启动接收协程,每收到一个 RTP 包回调 onPacket(IO 线程)
 *   3. [close] — 关闭 socket,接收协程随之退出
 *
 * iOS 为 stub(M3 范围,plan Q6);Android / JVM 走 java.net.DatagramSocket。
 */
expect class RtpReceiver(parentScope: CoroutineScope? = null) {
    /** 实际绑定的本地端口;bindLocalPort 之前为 -1。 */
    val localPort: Int

    suspend fun bindLocalPort(): Int
    /** 启动接收循环,onPacket 在 IO 线程回调。返回 Job 供取消。 */
    fun start(onPacket: (RtpPacket) -> Unit): Job
    suspend fun close()
}
