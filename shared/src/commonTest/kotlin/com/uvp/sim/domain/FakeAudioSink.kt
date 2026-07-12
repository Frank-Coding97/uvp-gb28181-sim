package com.uvp.sim.domain

import com.uvp.sim.media.AudioSink

/** 测试用扬声器:记录 start/stop/write 调用次数,不发声。 */
class FakeAudioSink : AudioSink {
    var startCount = 0
        private set
    var stopCount = 0
        private set
    var writeCount = 0
        private set

    /** cross-review R1 #7 支持模拟启动失败(默认成功)。 */
    var startResult: Boolean = true

    override fun start(): Boolean { startCount++; return startResult }
    override fun write(pcm: ShortArray) { writeCount++ }
    override fun stop() { stopCount++ }
}
