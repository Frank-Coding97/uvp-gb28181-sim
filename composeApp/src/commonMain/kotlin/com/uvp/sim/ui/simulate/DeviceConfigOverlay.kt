package com.uvp.sim.ui.simulate

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.uvp.sim.ui.model.DeviceConfigDto

/**
 * 画布上的「平台下发的设备配置」只读叠层 —— GB/T 28181-2022 A.2.3.2 的设备配置族在设备屏幕上的可见面。
 *
 * ## 画什么（⛔ 2026-09-19 起只剩一样）
 *  - **前端 OSD 文本**（A.2.1.12）：按 `Item/Text` + `X/Y` 把文本落在像素坐标处。
 *  - **画面遮挡**（A.2.1.17）**已不在这里画**（曾经是按 `RegionList/Item/Point` 两个角点画黑块）。
 *    它改为**烧进真实视频流**，理由见下方 `BoxWithConstraints` 里那段注释。
 *  - **画面翻转**（A.2.1.23）不在这里 —— 它是**真流**上整幅画面的变换，施加点在采集链路
 *    （Android `CameraTexturePass` / iOS `IosFrameProcessor`，由 `installFrameMirrorSupplier`
 *    装配，且在 OSD/遮挡**之前**）。**本机 3D 画布不参与**（2026-09-20 明确）：那是另一条
 *    与推流互不相干的链路，在它上面翻一次不代表平台点播到的画面会动。
 *
 * ## 坐标：只做**一次**归一化，这里只乘画布尺寸
 * 协议坐标全部是视频帧的绝对像素，已在 `DeviceConfigMapper` 里按帧宽高归一化成 0~1。
 * 本文件只负责 `fraction × 画布像素尺寸`。⛔ 千万别在这里再从原始像素换算一次 ——
 * 两处各换算一次，改一处就静默错位，而且矩形**照样画得出来**，肉眼很难发现。
 *
 * ## 四条硬约束（技能里记着代价，别顺手破坏）
 *  1. **不带任何 `clickable` / `pointerInput`**：画布上的手势必须能穿透给 3D 视图。
 *     判据是"有没有可交互修饰符"，而不是"是不是画在画布上" —— 只读叠层一直都可以放。
 *  2. **叠层顺序**：本层放在装饰层（磨砂 / 雨刷 / 力场罩 / 拉框）之后、`PtzThumbnail` 之前。
 *     ⚠️ 原先"遮挡块该盖住装饰效果"这条理由**已随遮挡搬走而失效**（只剩 OSD 文字，它本就在最上）。
 *     ⚠️ 存储卡原本也是本层的一张 UI 卡片（右上角，所以它当时排在最上），2026-09-19 已搬去
 *     HUD「状态」页（`ptz/StorageCardSection.kt`）—— 画布这层只留"画面里的东西"。
 *  3. **文字必须显式给 `lineHeight`**：`UvpTheme` 没有覆写 `typography`，`Text(fontSize = 11.sp)`
 *     的实际行框仍继承 Material3 的 `lineHeight = 24.sp`。
 *  4. **画布底色是深色**（`CameraStageBase`）：文字/遮挡一律按深底配色，
 *     ⛔ 不能套 `UvpColor.Text*`（那些是浅色主题的深灰，在画布上等于看不见）。
 *
 * ## 刻意的边界
 *  - 只画**平台真的下发过**的配置（理由见 `DeviceConfigDto` 的类注释：回退到出厂默认会
 *    和本机三层 OSD 重复显示同一行字）。
 *  - 遮挡画成**纯黑块**而不是马赛克：真 IPC 两种都有，黑块更省算力、也更不容易被误认成"画面坏了"。
 *  - 本叠层不改变任何手势与播放行为 —— 它纯粹是把设备当前配置可视化。
 */
@Composable
internal fun DeviceConfigOverlay(
    config: DeviceConfigDto,
    modifier: Modifier = Modifier,
) {
    if (!config.hasCanvasContent) return
    val density = LocalDensity.current
    BoxWithConstraints(modifier = modifier) {
        val widthPx = constraints.maxWidth.toFloat()
        val heightPx = constraints.maxHeight.toFloat()

        // ⛔⛔ 画面遮挡（A.2.1.17）2026-09-19 起**不在这里画**。
        // 黑块已改为**烧进真实视频流**（Android = `OsdRenderer` 的 FBO 段 `MaskPass`；
        // iOS = `IosFrameProcessor` 的 CoreImage 组合）。原实现画在这块 3D 画布上，
        // 而画布与"推给平台的手机摄像头画面"是**两条互不相干的链路** ——
        // 平台点播到的画面一点遮挡都没有，等于遮挡做在了唯一不上传的那条链路上。
        // 设备屏幕上的可见面改由 HUD「图像」页的遮挡区域列表承担（`ptz/ImageTabContent.kt`）。
        // ⛔ 别因为"画布上看不到遮挡了"就把这段加回来 —— 那只会让缺陷重新变得看不见。

        // ---- 前端 OSD 文本（A.2.1.12）----
        for (line in config.osdLines) {
            if (line.text.isBlank()) continue
            Text(
                text = line.text,
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                // ⛔ 必须显式给 lineHeight，否则行框按 Material3 的 24sp 记账（见文件头约束 3）。
                style = TextStyle(lineHeight = 14.sp, shadow = osdTextShadow),
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.offset(
                    x = with(density) { (line.x * widthPx).toDp() },
                    y = with(density) { (line.y * heightPx).toDp() },
                ),
            )
        }
    }
}

/**
 * OSD 文字描边 —— 画布内容深浅不定（3D 场景 + 磨砂层），纯白字在某些画面上读不出来。
 * 用 Compose 的 `shadow` 做一圈深色投影，效果接近真机的前端 OSD 描边。
 */
private val osdTextShadow = Shadow(
    color = Color.Black,
    offset = Offset(1f, 1f),
    blurRadius = 2f,
)
