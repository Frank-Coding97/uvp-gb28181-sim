package com.uvp.sim.network

import com.uvp.sim.observability.ErrorCategory
import com.uvp.sim.observability.LogLevel
import com.uvp.sim.observability.LogTag
import com.uvp.sim.observability.SystemLogger
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
 *
 * M-1 (audit §3) 源验证:UDP 模式下若构造时给了 [expectedSourceHost],非该 IP 来的
 * 数据报会被静默丢弃 + 一条 ProtocolViolation Warning。TCP 模式因为已经在 connect/accept
 * 时绑定了对端,中途换源不可能,只验 SSRC。SSRC 看到首个合法包后锁定,后续包 SSRC
 * 不一致同样丢弃。null = 不验,旧行为兼容。
 */
expect class RtpReceiver(
    parentScope: CoroutineScope? = null,
    expectedSourceHost: String? = null,
) {
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

/**
 * 默认实现:包装真实 [RtpReceiver]。
 *
 * [expectedSourceHost] 见 [RtpReceiver] 文档 — M-1 源 IP 验证,null = 不验。
 */
fun realBroadcastRxSource(
    scope: CoroutineScope,
    expectedSourceHost: String? = null,
): BroadcastRxSource {
    val r = RtpReceiver(scope, expectedSourceHost)
    return object : BroadcastRxSource {
        override val localPort: Int get() = r.localPort
        override suspend fun bind(mode: RtpMode): Int = r.bind(mode)
        override suspend fun connect(remoteHost: String, remotePort: Int) = r.connect(remoteHost, remotePort)
        override fun start(onPacket: (RtpPacket) -> Unit): Job = r.start(onPacket)
        override suspend fun close() = r.close()
    }
}

/**
 * M-1 — SSRC 锁:首个合法包出现后,后续包 SSRC 必须一致。多平台 actual 共用,
 * 不引入 atomicfu,因为 RtpReceiver 单连接单消费者,只在一个 IO 协程里写。
 */
internal class RtpSourceGuard(private val expectedSourceHost: String?) {
    private var lockedSsrc: Long? = null

    /**
     * @return true 表示包合法可 emit;false 表示被丢弃(异常源/SSRC 漂移),
     *               同时已发 SystemLogger 警告。
     */
    fun accept(packet: RtpPacket, observedHost: String?): Boolean {
        if (expectedSourceHost != null && observedHost != null && observedHost != expectedSourceHost) {
            SystemLogger.emit(
                LogLevel.Warning,
                LogTag.Media,
                "RTP 异常源丢弃: 来自 $observedHost 期望 $expectedSourceHost",
                category = ErrorCategory.ProtocolViolation,
            )
            return false
        }
        val locked = lockedSsrc
        if (locked == null) {
            lockedSsrc = packet.ssrc
            return true
        }
        if (locked != packet.ssrc) {
            SystemLogger.emit(
                LogLevel.Warning,
                LogTag.Media,
                "RTP SSRC 漂移丢弃: 收到 0x${packet.ssrc.toString(16)} 锁定 0x${locked.toString(16)}",
                category = ErrorCategory.ProtocolViolation,
            )
            return false
        }
        return true
    }

    /** 仅供测试:重置 SSRC 锁。 */
    internal fun resetLock() {
        lockedSsrc = null
    }
}
