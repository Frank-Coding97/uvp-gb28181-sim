package com.uvp.sim.ui.model.mapper

import com.uvp.sim.config.SimConfig
import com.uvp.sim.domain.DeviceControlModel
import com.uvp.sim.ui.model.DeviceConfigDto
import com.uvp.sim.ui.model.MaskRectDto
import com.uvp.sim.ui.model.OsdTextLineDto
import com.uvp.sim.ui.model.PictureMaskViewDto

/**
 * 「设备配置族」（GB/T 28181-2022 A.2.3.2）→ UI 视图态。
 *
 * 两条**必须**守住的规矩：
 *
 * 1. **归一化只做一次，且只对"要往画布上画"的东西做**。协议侧的 OSD 坐标 `X/Y` 是绝对像素，
 *    而画布尺寸是运行期才知道的，所以这里按 OSD **自己声明的 `Length/Width` 窗口**除一次。
 *    ⛔ **遮挡不做归一化**（2026-09-19 起）：它已经不从画布走了，而是烧进真实视频流，
 *    那条链路的归一化在 `VideoMaskOverlay.of()` 里按协议参考帧做一次；这里保留原始像素，
 *    因为设备屏幕上要显示的就是"平台发的坐标是多少"。
 *    两边各除一次就会静默错位（而矩形/文字**照样画得出来**，肉眼很难发现）。
 *
 * 2. **只映射"平台真的下发过"的那部分**（`deviceConfigs.xxx != null`），不回退出厂默认。
 *    理由见 [DeviceConfigDto] 的类注释：出厂默认的 OSD 已经由本机三层 OSD 烧进视频流，
 *    回退出来就是同一行字重合两遍。
 */
fun DeviceControlModel.toDeviceConfigDto(config: SimConfig): DeviceConfigDto {
    val frameWidth = config.video.resolution.widthPx.toFloat().coerceAtLeast(1f)
    val frameHeight = config.video.resolution.heightPx.toFloat().coerceAtLeast(1f)

    // ---- 画面遮挡：保留协议原始像素 ----
    // ⛔ 2026-09-19 起**不再归一化**：遮挡已从 Compose 画布搬到"烧进真实视频流"
    // （Android `OsdRenderer` 的 `MaskPass` / iOS `IosFrameProcessor` 的 CoreImage 组合），
    // 那条链路的归一化在 `VideoMaskOverlay.of()` 里按**协议参考帧**做一次。
    // 而设备屏幕上要显示的是「平台发的坐标是多少」，那就是原始像素 —— 这里再除一次
    // 只会让屏幕上的数和平台上的数对不上。
    val mask = deviceConfigs.pictureMask
    val maskView = if (mask == null) {
        PictureMaskViewDto()
    } else {
        PictureMaskViewDto(
            on = mask.on == 1,
            rects = mask.regions.map { region ->
                MaskRectDto(
                    seq = region.seq,
                    left = region.left,
                    top = region.top,
                    right = region.right,
                    bottom = region.bottom,
                )
            },
            configured = true,
        )
    }
    val osd = deviceConfigs.frontOsd
    // OSD 的画布坐标要按**它自己声明的窗口尺寸**归一化，不能用 config 的分辨率 ——
    // 标准里 Length/Width 就是"这块 OSD 的坐标系有多大"，平台按自己那份填的。
    // 用 config 的分辨率归一化会在平台填了别的窗口尺寸时整体偏移。
    val osdWidth = (osd?.length ?: 0).toFloat().takeIf { it > 0f } ?: frameWidth
    val osdHeight = (osd?.width ?: 0).toFloat().takeIf { it > 0f } ?: frameHeight
    val osdLines = osd?.items.orEmpty().map { item ->
        OsdTextLineDto(
            text = item.text,
            x = (item.x / osdWidth).coerceIn(0f, 1f),
            y = (item.y / osdHeight).coerceIn(0f, 1f),
        )
    }

    return DeviceConfigDto(
        pictureMask = maskView,
        osdLines = osdLines,
        // ⛔ 翻转**保留 `null`**（平台没配过）而不是兜成 0 —— 见 `DeviceConfigDto.frameMirror`
        //    的注释：两者在画面上一样，但设备屏幕上该说不同的话。
        frameMirror = deviceConfigs.frameMirror?.value,
        // 原样透出（不做归一化）：设置页要拿它回写成协议字段。
        frontOsd = osd,
    )
}
