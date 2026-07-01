package com.uvp.sim.media

import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.CoreMedia.CMTime
import platform.Foundation.NSProcessInfo

/**
 * iOS 时钟基线 —— 音视频帧共用同一时钟源,以便 PS Muxer 交错正确。
 *
 * - [nowUs] 基于 `NSProcessInfo.systemUptime`(单调,系统启动为 0)。
 * - [cmTimeToMicros] 把 `CMTime{value, timescale}` 转微秒;timescale=0 时返回 0 而非除零崩溃。
 *
 * 时钟基线跟 Android `MediaCodec.presentationTimeUs`(单调 us)对齐,
 * commonMain `H264Frame.timestampUs` / `AudioFrame.timestampUs` 期望的是同一基线的相对时间。
 */
@OptIn(ExperimentalForeignApi::class)
object MediaTimebase {

    fun nowUs(): Long {
        val seconds = NSProcessInfo.processInfo.systemUptime
        return (seconds * 1_000_000.0).toLong()
    }

    fun cmTimeToMicros(cmTime: CValue<CMTime>): Long = cmTime.useContents {
        if (timescale == 0) 0L else value * 1_000_000L / timescale
    }
}
