package com.uvp.sim.camera

import kotlinx.cinterop.ExperimentalForeignApi
import kotlin.concurrent.Volatile
import kotlin.time.Clock
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFRetain
import platform.CoreVideo.CVImageBufferRef

/**
 * cross-review R1 #4 拆分 step 1(from [IosCameraController]):
 *  - 每帧 CVImageBuffer 的 publish + 引用计数(snapshot 订阅侧,Fix #6)
 *  - 采样统计(sampleCount / lastSampleAtMs)
 *
 * 保持跟 controller 静态相同的语义:
 *  - [publish] 幂等替换,旧 buffer CFRelease
 *  - 无订阅者时不 publish(quick path)
 *  - [release] 归零 + 释放当前引用
 *
 * 单例对象,跟 [IosCameraController] 一样是进程级 —— 相机只能有一个采样源。
 * 消费方(SnapshotCapture)不直接引用本对象,继续走 controller facade。
 */
@OptIn(ExperimentalForeignApi::class)
internal object IosCameraFrameBuffer {

    /**
     * 最近一帧 CVImageBufferRef(delegate 每帧原子替换,旧值 CFRelease)。
     * 生命周期同 v1.2 [IosCameraStreamer.latestFrame],语义完全对齐。
     */
    @Volatile
    private var latestFrame: CVImageBufferRef? = null

    /**
     * Fix #6:latestFrame publish 是"常驻税"(每帧 CFRetain/CFRelease),但只有 SnapshotCapture
     * 需要。用 subscribers 引用计数,只有 > 0 时 onSample 才 publishLatestFrame。
     * PreviewOnly + 无抓拍请求时,onSample 走轻路径(仅可能的 encode 分支),不动 latest。
     */
    @Volatile
    private var snapshotSubscribers: Int = 0

    @Volatile
    private var lastSampleAtMsInternal: Long = -1L

    @Volatile
    private var sampleCountInternal: Long = 0L

    /** 有 snapshot 订阅者时,onSample 才走 publish 常驻路径。 */
    fun hasSnapshotSubscribers(): Boolean = snapshotSubscribers > 0

    /**
     * 每帧原子替换 latestFrame,自 [CFRetain] newFrame,旧值 [CFRelease]。
     * 只在 [hasSnapshotSubscribers] 为 true 时该调用(controller.onSample 已做判定)。
     */
    fun publish(newFrame: CVImageBufferRef) {
        val old = latestFrame
        CFRetain(newFrame)
        latestFrame = newFrame
        if (old != null) CFRelease(old)
    }

    /**
     * 取当前最近一帧 pixel buffer,并额外 CFRetain 一次交给调用方。
     * 调用方使用完必须 [CFRelease]。返回 null 表示尚未有帧到达 或 snapshot 未订阅。
     */
    fun latestFramePixelBuffer(): CVImageBufferRef? {
        val current = latestFrame ?: return null
        CFRetain(current)
        return current
    }

    /**
     * Fix #6:开始订阅 latestFrame publish。SnapshotCapture 在 takeJpeg 起手调,完成后 [endSubscription]。
     * 引用计数支持并发多个 SnapshotCapture 请求。
     */
    fun beginSubscription() {
        snapshotSubscribers += 1
    }

    /**
     * Fix #6:结束订阅。归零时 onSample 不再 publish latestFrame。
     * 清理 latestFrame 以释放最后引用(下次 begin 再从 delegate 首帧填)。
     */
    fun endSubscription() {
        snapshotSubscribers = maxOf(0, snapshotSubscribers - 1)
        if (snapshotSubscribers == 0) {
            latestFrame?.let { CFRelease(it) }
            latestFrame = null
        }
    }

    /**
     * 每 25 帧刷一次时间戳戳(避免 Clock.now 每帧调,采样统计只用于诊断心跳)。
     */
    fun recordSample() {
        sampleCountInternal += 1
        if (sampleCountInternal % 25L == 0L) {
            lastSampleAtMsInternal = Clock.System.now().toEpochMilliseconds()
        }
    }

    fun lastSampleAtMs(): Long = lastSampleAtMsInternal
    fun sampleCount(): Long = sampleCountInternal

    /**
     * controller.releaseInternal 调:清空 latestFrame + 采样统计。
     */
    fun release() {
        latestFrame?.let { CFRelease(it) }
        latestFrame = null
        lastSampleAtMsInternal = -1L
        sampleCountInternal = 0L
    }
}
