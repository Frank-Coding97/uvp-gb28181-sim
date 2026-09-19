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
 * ## 画什么
 *  - **画面遮挡**（A.2.1.17）：按 `RegionList/Item/Point` 的两个角点画黑块。
 *    这是本批功能里**演起来最直观**的一项：平台配 2 个区域，设备屏幕上直接多两块黑。
 *  - **前端 OSD 文本**（A.2.1.12）：按 `Item/Text` + `X/Y` 把文本落在像素坐标处。
 *  - **画面翻转**（A.2.1.23）不在这里 —— 它是整个画面的变换，由 [MonitoringStage] 施加在
 *    3D 视图那一层（本叠层是"烧在画面上的内容"，不该跟着镜像一起翻）。
 *
 * ## 坐标：只做**一次**归一化，这里只乘画布尺寸
 * 协议坐标全部是视频帧的绝对像素，已在 `DeviceConfigMapper` 里按帧宽高归一化成 0~1。
 * 本文件只负责 `fraction × 画布像素尺寸`。⛔ 千万别在这里再从原始像素换算一次 ——
 * 两处各换算一次，改一处就静默错位，而且矩形**照样画得出来**，肉眼很难发现。
 *
 * ## 四条硬约束（技能里记着代价，别顺手破坏）
 *  1. **不带任何 `clickable` / `pointerInput`**：画布上的手势必须能穿透给 3D 视图。
 *     判据是"有没有可交互修饰符"，而不是"是不是画在画布上" —— 只读叠层一直都可以放。
 *  2. **叠层顺序**：本层放在装饰层之后、`StorageCardPanel` 之前。
 *     遮挡块是"画面内容"，应当盖住磨砂/雨刷这些装饰效果；但存储卡卡片是 UI 卡片，要在最上层。
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

        // ---- 画面遮挡（A.2.1.17）----
        if (config.pictureMask.on) {
            for (rect in config.pictureMask.rects) {
                val leftPx = rect.left * widthPx
                val topPx = rect.top * heightPx
                val widthPxRect = (rect.right - rect.left) * widthPx
                val heightPxRect = (rect.bottom - rect.top) * heightPx
                if (widthPxRect <= 0f || heightPxRect <= 0f) continue
                Box(
                    modifier = Modifier
                        .offset(
                            x = with(density) { leftPx.toDp() },
                            y = with(density) { topPx.toDp() },
                        )
                        .size(
                            width = with(density) { widthPxRect.toDp() },
                            height = with(density) { heightPxRect.toDp() },
                        )
                        .background(Color.Black)
                )
            }
        }

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
