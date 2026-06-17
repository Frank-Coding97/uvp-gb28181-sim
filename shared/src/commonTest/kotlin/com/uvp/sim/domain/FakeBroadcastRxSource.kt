package com.uvp.sim.domain

import com.uvp.sim.network.BroadcastRxSource
import com.uvp.sim.network.RtpMode
import com.uvp.sim.network.RtpPacket
import kotlinx.coroutines.Job

/**
 * 测试用 RX 源:bind 同步返回固定端口(不碰真 socket / Dispatchers.IO),记录 mode/connect 调用。
 * start 返回活跃 Job 供 isRxActive / teardown 断言。RTP 包由测试直接调
 * [SimulatorEngine.handleRxPacket] 注入,不经此 fake。
 */
class FakeBroadcastRxSource(private val boundPort: Int = 40000) : BroadcastRxSource {
    var startCalled = false
        private set
    var boundMode: RtpMode? = null
        private set
    var connectedHost: String? = null
        private set
    var connectedPort: Int = -1
        private set
    var connectCount = 0
        private set
    private var job: Job? = null

    override val localPort: Int get() = boundPort

    override suspend fun bind(mode: RtpMode): Int {
        boundMode = mode
        // TCP_ACTIVE 真实实现返回 0(不监听);此处保持 fake 行为:端口对路由无影响
        return boundPort
    }

    override suspend fun connect(remoteHost: String, remotePort: Int) {
        connectedHost = remoteHost
        connectedPort = remotePort
        connectCount++
    }

    override fun start(onPacket: (RtpPacket) -> Unit): Job {
        startCalled = true
        val j = Job()
        job = j
        return j
    }

    override suspend fun close() {
        job?.cancel()
    }
}
