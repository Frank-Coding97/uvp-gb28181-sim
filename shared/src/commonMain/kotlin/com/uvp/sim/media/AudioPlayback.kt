package com.uvp.sim.media

/**
 * 语音广播下行(§9.8)扬声器输出抽象 — PCM16 单声道 8kHz 播放。
 *
 * Android = AudioTrack(USAGE_MEDIA + CONTENT_TYPE_SPEECH,plan §6 Q4);
 * JVM = javax.sound.sampled(给 jvmTest smoke);iOS = stub(M4 补 AVAudioEngine)。
 *
 * 实现内部对硬件异常做 try-catch,start/write/stop 不抛(plan §6 失败回退)。
 */
expect class AudioPlayback(sampleRate: Int, channelCount: Int) {
    fun start()
    fun write(pcm: ShortArray)
    fun stop()
}

/**
 * 扬声器输出抽象 — 让 [com.uvp.sim.domain.SimulatorEngine] 依赖接口而非 expect class,
 * 单测可注入 fake(expect class 不可继承)。
 */
interface AudioSink {
    fun start()
    fun write(pcm: ShortArray)
    fun stop()
}

/** 默认实现:包装真实 [AudioPlayback]。 */
fun realAudioSink(sampleRate: Int, channelCount: Int): AudioSink {
    val ap = AudioPlayback(sampleRate, channelCount)
    return object : AudioSink {
        override fun start() = ap.start()
        override fun write(pcm: ShortArray) = ap.write(pcm)
        override fun stop() = ap.stop()
    }
}
