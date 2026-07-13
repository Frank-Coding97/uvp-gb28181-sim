package com.uvp.sim.media

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

/**
 * Android 扬声器输出 — AudioTrack,MODE_STREAM PCM16 单声道。
 *
 * USAGE_MEDIA + CONTENT_TYPE_SPEECH(plan §6 Q4):等价 STREAM_MUSIC 外放但带语音优化,
 * 不会切到听筒 / 蓝牙电话路由。硬件初始化失败 try-catch 兜底,不崩。
 */
actual class AudioPlayback actual constructor(
    private val sampleRate: Int,
    @Suppress("UNUSED_PARAMETER") private val channelCount: Int
) {
    private var track: AudioTrack? = null

    actual fun start(): Boolean {
        return runCatching {
            val bufSize = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(sampleRate * 2 * 2 / 5)   // 至少 200ms 缓冲
            track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            track?.play()
            track != null
        }.onFailure { track = null }.getOrDefault(false)
    }

    actual fun write(pcm: ShortArray) {
        runCatching { track?.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING) }
    }

    actual fun stop() {
        runCatching { track?.stop(); track?.release() }
        track = null
    }
}
