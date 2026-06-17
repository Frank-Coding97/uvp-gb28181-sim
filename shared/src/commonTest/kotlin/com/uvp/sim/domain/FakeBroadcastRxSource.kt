package com.uvp.sim.domain

import com.uvp.sim.network.BroadcastRxSource
import com.uvp.sim.network.RtpPacket
import kotlinx.coroutines.Job

/**
 * 测试用 RX 源:bindLocalPort 同步返回固定端口(不碰真 socket / Dispatchers.IO),
 * start 返回一个活跃 Job 供 isRxActive / teardown 断言。RTP 包由测试直接调
 * [SimulatorEngine.handleRxPacket] 注入,不经此 fake。
 */
class FakeBroadcastRxSource(private val boundPort: Int = 40000) : BroadcastRxSource {
    var startCalled = false
        private set
    private var job: Job? = null

    override val localPort: Int get() = boundPort
    override suspend fun bindLocalPort(): Int = boundPort
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
