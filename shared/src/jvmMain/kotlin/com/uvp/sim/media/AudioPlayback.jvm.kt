package com.uvp.sim.media

import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine

/**
 * JVM 扬声器输出 — javax.sound.sampled。主要给 jvmTest smoke 用,不追求严格音质。
 * headless / 无音频设备时静默兜底(start/write/stop 永不抛)。
 */
actual class AudioPlayback actual constructor(
    private val sampleRate: Int,
    private val channelCount: Int
) {
    private var line: SourceDataLine? = null

    actual fun start(): Boolean {
        return runCatching {
            val format = AudioFormat(sampleRate.toFloat(), 16, channelCount, true, false) // signed LE
            val info = DataLine.Info(SourceDataLine::class.java, format)
            val l = AudioSystem.getLine(info) as SourceDataLine
            l.open(format)
            l.start()
            line = l
            true
        }.onFailure { line = null }.getOrDefault(false)
    }

    actual fun write(pcm: ShortArray) {
        runCatching {
            val l = line ?: return
            val bytes = ByteArray(pcm.size * 2)
            for (i in pcm.indices) {
                val v = pcm[i].toInt()
                bytes[i * 2] = (v and 0xFF).toByte()
                bytes[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
            }
            l.write(bytes, 0, bytes.size)
        }
    }

    actual fun stop() {
        runCatching { line?.drain(); line?.stop(); line?.close() }
        line = null
    }
}
