package com.uvp.sim.osd

import com.uvp.sim.gb28181.FrameMirrorConfig

/**
 * 画面翻转（GB/T 28181-2022 A.2.1.23 `FrameMirror`）在**渲染端**的归一形态。
 *
 * ## 为什么要有这个类型（而不是把 0~3 直接传给两个渲染端）
 * 采集管线有两条：Android（`CameraTexturePass` 的 GL 顶点）与 iOS（`IosFrameProcessor`
 * 的 CoreImage 变换）。两侧都需要"这一帧该不该翻"，但**都不该知道协议里 1 是水平、
 * 3 是中心** —— 各写一份 `when (value)` 就是"改一处、另一平台静默不一致"的经典来源，
 * 而画面翻转出错的现象是"看起来只是方向不对"，最难当场发现。
 *
 * ⇒ 码值 ↔ 布尔对 的换算**只在本文件的 [of] 里发生一次**，两端只消费 [mirrorX] / [mirrorY]。
 * 这与 `DeviceConfigMapper`「归一化只做一次」、`deriveCommandCategory`「协议字符串泄露
 * 压缩到一个函数」是同一条约定。
 *
 * ## 与遮挡的**顺序不能反**
 * 本变换作用于**画面本身**（相机帧），必须在 OSD 文字与画面遮挡**之前**施加 ——
 * 后两者是"盖在画面上的内容"，位置由平台按画面坐标给定，跟着一起翻会让设备上显示的
 * 位置与平台配置的坐标系统性错位（而画面**看起来仍然正常**）。
 * Android 侧因此改的是 `CameraTexturePass` 的顶点，不是 blit；iOS 侧改的是
 * `composeOverlays` 之前的 `positioned`。
 *
 * ## 语义边界
 *  - `null`（平台从没配过）与 `0`（平台配了"不启用"）**在这里合并** —— 都返回 [NONE]。
 *    这是刻意的：渲染端只回答"这一帧要不要翻"，而这两者在画面上**确实完全一样**。
 *    设备屏幕上的「平台未配置 / 不启用」两态由展示层读 `deviceConfigs.frameMirror == null`
 *    区分（见 `ptz/ImageTabContent.kt`），不靠这里的信息。
 *  - 越界值（`FrameMirrorConfig.parse` 本来就会拒，落库不进越界值）按 [NONE] 处理，
 *    是第二道闸：真漏进来一个 7 也只表现为"不翻"，而不是翻成不可预期的方向。
 *  - 只读：由 `AppEngine` 从 `deviceControlState.deviceConfigs.frameMirror` 派生，
 *    没有任何 UI 能写它（写入通道始终只有 `DeviceConfig` 下发这一条）。
 */
data class FrameMirrorTransform(
    /** 水平翻转（左右镜像），A.2.1.23 的 `1`。 */
    val mirrorX: Boolean = false,
    /** 上下翻转（垂直镜像），A.2.1.23 的 `2`。 */
    val mirrorY: Boolean = false,
) {
    /** 两个方向都不翻 —— 渲染端据此**完全不进变换分支**（省一次图像变换）。 */
    val isIdentity: Boolean get() = !mirrorX && !mirrorY

    companion object {
        val NONE = FrameMirrorTransform()

        /**
         * 从协议码值派生（值域见 `FrameMirrorConfig`，逐个抄自 A.2.1.23 原文）：
         * ```
         *   0 不启用镜像，基准画面   → (false, false)
         *   1 水平镜像（左右翻转）   → (true,  false)
         *   2 上下镜像（上下翻转）   → (false, true)
         *   3 中心镜像（上下左右都翻）→ (true,  true)
         * ```
         */
        fun of(value: Int?): FrameMirrorTransform {
            if (value == null || !FrameMirrorConfig.isValid(value)) return NONE
            return when (value) {
                1 -> FrameMirrorTransform(mirrorX = true)
                2 -> FrameMirrorTransform(mirrorY = true)
                3 -> FrameMirrorTransform(mirrorX = true, mirrorY = true)
                else -> NONE
            }
        }
    }
}
