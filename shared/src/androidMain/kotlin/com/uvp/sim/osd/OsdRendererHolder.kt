package com.uvp.sim.osd

import android.content.Context
import android.view.Surface
import com.uvp.sim.config.OsdConfig
import com.uvp.sim.observability.LogLevel
import com.uvp.sim.observability.LogTag
import com.uvp.sim.observability.SystemLogger
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicInteger

/**
 * 进程级 OsdRenderer 单例 — 直播 / 录像 / 屏幕预览共享一个 GL pipeline。
 *
 * 跟工业 IPC 硬件 OSD region 同构:一份"已烧 OSD 的画面"分发给所有消费者,
 * 屏幕看到什么 = 录像写下什么 = 直播推出去什么 = WVP 回放看到什么。
 *
 * 生命周期:
 * - 第一个消费者 [acquire] → 懒启动 OsdRenderer
 * - 后续消费者 [acquire] → 复用同一个 renderer 实例
 * - 全部 [release] 完 → 自动 release renderer + tear down GL
 *
 * 失败 fallback:[acquire] 返回 null 表示 GL 启动失败,调用方走 fallback 路径
 * (无 OSD,流仍能推)。已 emit OSD_INIT_FAILED。
 *
 * 线程安全:所有公共方法都同步,不要在 GL thread 上调(避免死锁)。
 */
internal object OsdRendererHolder {

    private var current: OsdRenderer? = null
    private val refCount = AtomicInteger(0)
    private val lock = Object()

    /**
     * 获取当前 OsdRenderer。第一次调用懒启动 GL pipeline。
     *
     * 调用方负责后续 [release]([acquire] 配对)。
     *
     * @return 启动成功的 renderer,失败 null
     */
    fun acquire(
        context: Context,
        configFlow: StateFlow<OsdConfig>,
        targetWidth: Int = 1280,
        targetHeight: Int = 720
    ): OsdRenderer? = synchronized(lock) {
        val existing = current
        if (existing != null) {
            refCount.incrementAndGet()
            return existing
        }
        val renderer = OsdRenderer(
            context = context.applicationContext,
            configFlow = configFlow,
            targetWidth = targetWidth,
            targetHeight = targetHeight
        )
        return if (renderer.start()) {
            current = renderer
            refCount.set(1)
            SystemLogger.emit(LogLevel.Info, LogTag.Media, "OSD_HOLDER_STARTED",
                detail = "${targetWidth}x${targetHeight}")
            renderer
        } else {
            null
        }
    }

    /**
     * 释放一个引用。所有引用都释放后 tear down GL pipeline。
     */
    fun release() = synchronized(lock) {
        val n = refCount.decrementAndGet()
        if (n <= 0) {
            current?.release()
            current = null
            refCount.set(0)
            SystemLogger.emit(LogLevel.Info, LogTag.Media, "OSD_HOLDER_TORN_DOWN")
        }
    }

    /** 获取当前正在跑的 renderer(只读用,不增加引用)。 */
    fun peek(): OsdRenderer? = synchronized(lock) { current }
}
