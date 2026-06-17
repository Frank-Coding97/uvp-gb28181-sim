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
