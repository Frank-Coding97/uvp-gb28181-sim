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
     * 画面遮挡的**实时快照来源**（GB-2022 A.2.1.17），进程级只装一次。
     *
     * ⭐ 做成进程级的 install 而不是往 [acquire] 加参数：`acquire` 有 3 个调用点
     * （直播 streamer / 录像 pipeline / 屏幕预览），而遮挡是**同一条画面源**上的属性 ——
     * 逐个传参数会让"录像有遮挡、直播没有"这种不一致成为可能，且每加一个消费者都要记得传。
     * 装一次、所有消费者共享，与 [current] 本身是单例这件事同构。
     *
     * 默认返回 [VideoMaskOverlay.EMPTY]（不画），所以测试 / 桌面 runtime 不装也行为不变。
     * ⚠️ 读它的时机是 GL 线程逐帧，所以 source 内部读的必须是线程安全的值
     * （`AppEngine` 传进来的实现读的是 `StateFlow.value`，满足）。
     */
    @Volatile
    private var maskSupplier: () -> VideoMaskOverlay = { VideoMaskOverlay.EMPTY }

    /** 装画面遮挡来源。重复装以最后一次为准（进程内只有一个场景）。 */
    fun installMaskSupplier(supplier: () -> VideoMaskOverlay) {
        maskSupplier = supplier
    }

    /**
     * 画面翻转（GB-2022 A.2.1.23）的实时来源 —— 与 [maskSupplier] **同一形状、同一理由**：
     * 它是同一条画面源上的属性，必须一次装好、直播/录像/屏幕预览共享，
     * 否则会出现"直播翻了、录像没翻"这种不一致。
     */
    @Volatile
    private var mirrorSupplier: () -> FrameMirrorTransform = { FrameMirrorTransform.NONE }

    /** 装画面翻转来源。重复装以最后一次为准。 */
    fun installMirrorSupplier(supplier: () -> FrameMirrorTransform) {
        mirrorSupplier = supplier
    }

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
            targetHeight = targetHeight,
            maskSupplier = { maskSupplier() },
            frameMirrorSupplier = { mirrorSupplier() }
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
