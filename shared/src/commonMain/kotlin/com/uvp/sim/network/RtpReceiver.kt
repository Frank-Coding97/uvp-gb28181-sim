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

/**
 * RX 接收端口抽象 — 让 [com.uvp.sim.domain.SimulatorEngine] 依赖接口而非具体 expect class,
 * 从而单测可注入 fake(expect class 不可继承)。
 */
interface BroadcastRxSource {
    val localPort: Int
    suspend fun bindLocalPort(): Int
    fun start(onPacket: (RtpPacket) -> Unit): Job
    suspend fun close()
}

/** 默认实现:包装真实 [RtpReceiver]。 */
fun realBroadcastRxSource(scope: CoroutineScope): BroadcastRxSource {
    val r = RtpReceiver(scope)
    return object : BroadcastRxSource {
        override val localPort: Int get() = r.localPort
        override suspend fun bindLocalPort(): Int = r.bindLocalPort()
        override fun start(onPacket: (RtpPacket) -> Unit): Job = r.start(onPacket)
        override suspend fun close() = r.close()
    }
}
