package com.uvp.sim.camera

import com.uvp.sim.media.AudioFrame
import kotlinx.coroutines.flow.Flow
import kotlin.concurrent.Volatile

/**
 * iOS actual for [AudioCapture]. Delegates to [IosAudioStreamer] mirroring
 * the Android `AndroidAudioStreamer` + `AudioCapture.android.kt` wrapper pattern.
 *
 * v1.1 skeleton: AVAudioEngine tap lands after T2 cinterop spike verifies
 * ObjC block bridging.
 */
actual class AudioCapture actual constructor(config: AudioCaptureConfig) {

    @Volatile
    private var config: AudioCaptureConfig = config

    @Volatile
    private var streamer: IosAudioStreamer = IosAudioStreamer(config)

    actual fun start(): Flow<AudioFrame> = streamer.stream()

    actual suspend fun stop() {
        streamer.stop()
    }

    internal fun applyConfig(config: AudioCaptureConfig) {
        this.config = config
        // 旧 streamer 上可能挂着 tap + running AVAudioEngine —— 直接丢弃会漏引用,
        // 跟 Android [PlatformRuntimeAndroid.applyVideoConfig] 的 old.stop() 收尾对齐。
        runCatching { streamer.stopSync() }
        streamer = IosAudioStreamer(config)
    }

    internal fun configuredConfigForTest(): AudioCaptureConfig = config

    /** 测试专用:回读当前 streamer 实例,verify applyConfig 是否真替换了(而非原地 mutate)。 */
    internal fun streamerForTest(): IosAudioStreamer = streamer
}
