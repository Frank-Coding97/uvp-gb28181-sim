package com.uvp.sim.osd

import kotlin.concurrent.Volatile

/**
 * iOS 侧「画面遮挡」来源的**进程级**装配点（对应 Android 的 `OsdRendererHolder.installMaskSupplier`）。
 *
 * ⭐ 为什么做成进程级静态而不是逐层传参：iOS 侧从 `PlatformRuntimeIos` 到真正画帧的
 * `IosFrameProcessor` 中间隔着 `IosCameraController` → `IosCameraEncodingCoordinator` →
 * `EncodingSession` 三层，而遮挡是**同一条画面源**上的属性 —— 逐层加参数会让
 * 「直播有遮挡、录像没有」这种不一致成为可能（Android 侧同理，故两侧用同一形状）。
 *
 * 默认 [VideoMaskOverlay.EMPTY]：没装来源时行为与加本功能之前**逐帧一致**。
 * ⚠️ 读它的时机是采集线程逐帧，所以 supplier 内部读的必须是线程安全的值
 * （`AppEngine` 传进来的实现读的是 `StateFlow.value`，满足）。
 */
internal object IosVideoMaskHolder {

    @Volatile
    private var supplier: () -> VideoMaskOverlay = { VideoMaskOverlay.EMPTY }

    /** 装画面遮挡来源。重复装以最后一次为准（进程内只有一个场景）。 */
    fun install(source: () -> VideoMaskOverlay) {
        supplier = source
    }

    /** 取当前遮挡快照。渲染端每帧调一次。 */
    fun current(): VideoMaskOverlay = supplier()
}
