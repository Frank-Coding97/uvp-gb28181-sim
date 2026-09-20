package com.uvp.sim.osd

import com.uvp.sim.gb28181.PictureMaskState

/**
 * 一块要**烧进真实视频流**的遮挡区域（GB/T 28181-2022 A.2.1.17）。
 *
 * 坐标是**归一化 0~1**，原点在画面左上角。渲染端只做「[0,1] → 自己的绘制坐标系」，
 * ⛔ 不再从协议像素换算第二次 —— 归一化只在 [VideoMaskOverlay.of] 里做一次
 * （与 `DeviceConfigMapper` 那条「归一化只做一次」是同一个约定，理由相同：
 * 两处各换算一次，改一处就静默错位，而矩形**照样画得出来**，肉眼很难发现）。
 */
data class VideoMaskRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

/**
 * 当前生效的画面遮挡 —— 采集管线（GL FBO / iOS 位图）逐帧读的**只读快照**。
 *
 * ## 为什么需要它（而不是继续在 Compose 画布上画黑块）
 * 模拟器推给平台的是**手机真实摄像头**的画面（CameraX + MediaCodec / AVCaptureSession +
 * VideoToolbox），而「模拟中心」那块 3D 画布是另一条互不相干的链路。
 * 画布上盖黑块 = 遮挡做在了**唯一不上传的那条链路上** → 平台点播到的画面一点遮挡都没有。
 * ⇒ 遮挡必须烧进推流帧本身，判据才是「平台看到的画面真的被挡住了」。
 *
 * ## 语义边界
 *  - `On=0`（停用）或平台从没配过 → 空快照：**一帧都不改**（不是"画一块透明的"）。
 *  - 本快照**不改变**任何协议行为 —— 它纯粹是把平台已下发的配置落到画面上。
 *  - 只读：由 `AppEngine` 从 `deviceControlState.deviceConfigs.pictureMask` 派生，
 *    没有任何 UI 能写它（写入通道始终只有 `DeviceConfig` 下发这一条）。
 */
data class VideoMaskOverlay(
    val rects: List<VideoMaskRect> = emptyList(),
) {
    /** 没有要烧的区域 —— 渲染端据此**完全不进绘制分支**（省一次 GL 状态切换）。 */
    val isEmpty: Boolean get() = rects.isEmpty()

    companion object {
        val EMPTY = VideoMaskOverlay()

        /**
         * 从协议状态派生。
         *
         * ⛔ [frameWidthPx] / [frameHeightPx] 必须传**协议参考帧**（即设备对外声明的
         * `config.video.resolution`）—— 平台就是按它换算 `Point` 的。
         * 本仓采集 / FBO / 编码尺寸都是从同一个 `config.video.resolution` 派生的
         * （`AppEngine.captureConfigOf`），所以这一份归一化同时对得上画布与编码帧。
         *
         * 退化矩形（宽或高为 0）直接丢弃：协议侧 `parsePoint` 已挡掉倒置矩形，
         * 这里是第二道闸 —— 画一块零面积的黑块只会白切一次 GL 状态。
         */
        fun of(
            mask: PictureMaskState?,
            frameWidthPx: Int,
            frameHeightPx: Int,
        ): VideoMaskOverlay {
            if (mask == null || mask.on != 1) return EMPTY
            val w = frameWidthPx.toFloat().coerceAtLeast(1f)
            val h = frameHeightPx.toFloat().coerceAtLeast(1f)
            val rects = mask.regions.mapNotNull { region ->
                val left = (region.left / w).coerceIn(0f, 1f)
                val top = (region.top / h).coerceIn(0f, 1f)
                val right = (region.right / w).coerceIn(0f, 1f)
                val bottom = (region.bottom / h).coerceIn(0f, 1f)
                if (right <= left || bottom <= top) {
                    null
                } else {
                    VideoMaskRect(left = left, top = top, right = right, bottom = bottom)
                }
            }
            return if (rects.isEmpty()) EMPTY else VideoMaskOverlay(rects)
        }
    }
}
