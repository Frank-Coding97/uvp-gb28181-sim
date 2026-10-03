package com.uvp.sim.ui.simulate

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uvp.sim.ui.UvpColor
import com.uvp.sim.ui.model.DeviceControlDto

/**
 * 摄像监控台 — 顶部标题栏 + 中间 3D Filament 视图(含装饰性 overlay)。
 *
 * 标题栏: 模拟中心 logo + StatusHeadline 状态短句.
 * 3D 区: CameraGlbView + FrostedGlass + AuxFeedback + Guard + DragZoom
 *   + DeviceConfigOverlay(画面遮挡黑块 + 前端 OSD 文本,GB-2022 A.2.3.2)
 *   + TargetTrackOverlay(目标跟踪框 + 模式角标,GB-2022 A.2.3.1.14)
 *   + CameraGlbView 自带的 PtzThumbnail(右下角缩略图).
 *   **这一层全是只读装饰,不放任何可交互控件** —— 曾经把本机 PTZ 手操条浮在这里,
 *   会盖住右下角缩略图(2026-09-16 踩过)。手操现已收进 HUD 云台页([PtzTabContent])。
 *   ⚠️ 判据是**有没有 `clickable` / `pointerInput`**,不是"画布上什么都不许放":
 *   `DeviceConfigOverlay` 是全尺寸的,但它同样只读,手势照常穿透。
 *   📌 判据还有下半句:**"设备状态回显"不该放这里**。存储卡卡片 2026-09-19 就从右上角
 *   搬去了 HUD「状态」页 —— 画布只留"画面里的东西"(遮挡 / OSD / 角标 / 缩略图)。
 *   ⚠️ `TargetTrackOverlay` 是**这条判据的边界案例**,判它算"画面里的东西"的依据:
 *   它画的是一个**框**（跟踪目标所在区域，坐标来自协议），那个模式角标是框的**从属标注**，
 *   不是一块独立的"设备现在在干什么"的状态卡片 —— 后者（跟踪模式短句）在顶部
 *   `StatusHeadline` 里，两处别互相搬。
 */
@Composable
internal fun MonitoringStage(
    state: DeviceControlDto,
    onPoseTick: (Float, Float, Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = UvpColor.Surface,
            shape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp),
            tonalElevation = 0.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(UvpColor.PrimaryLight),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Outlined.Videocam,
                        contentDescription = null,
                        tint = UvpColor.Primary,
                        modifier = Modifier.size(15.dp)
                    )
                }
                Text(
                    "模拟中心",
                    modifier = Modifier.padding(start = 8.dp),
                    color = UvpColor.Text,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Box(Modifier.weight(1f))
                StatusHeadline(state = state)
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(bottomStart = 8.dp, bottomEnd = 8.dp))
                .background(CameraStageBase)  // Filament 之下兜底色,跟 clearColor 同色
                .border(
                    1.dp,
                    UvpColor.BorderLight,
                    RoundedCornerShape(bottomStart = 8.dp, bottomEnd = 8.dp)
                )
        ) {
            // GB-2022 A.2.1.23 画面镜像 —— **本画布不施加**（2026-09-20 用户明确）。
            //
            // ⛔ 镜像是**真流**的事，它的唯一可见面是**平台播放器**：拉流那一路由
            //    `CameraTexturePass.setFrameMirror`（Android）/ `IosFrameProcessor.applyFrameMirror`
            //    （iOS）施加在**相机帧**上，且在 OSD / 遮挡**之前**。
            //    本画布是本机 3D 预览，与真流链路**没有共享代码** —— 在这里再翻一次只是
            //    "设备屏幕上也能看出方向"的示意，**不代表功能成立**：上一轮判成"半实现"
            //    就是因为画布在翻、而平台上其实一动不动。
            // ⛔ 状态回显也不在这里 —— 它在 HUD「图像」页的「画面镜像」四卡片
            //    （`ImageTabContent.mirrorSectionState`）。别再往画布上角标。
            CameraGlbView(
                state = state,
                onPoseTick = onPoseTick,
                modifier = Modifier.fillMaxSize()
            )

            // 磨砂玻璃质感叠层(在 3D 之上,GuardOverlay 之下)
            // 中心保持透明不影响球机,只在边缘 / 上下沿透出"光透磨砂"的高级感
            FrostedGlassOverlay(modifier = Modifier.fillMaxSize())

            // 辅助控制视觉反馈(雨刷扫动;标准只定义编号 1 = 雨刷,其余编号无叠加)
            AuxFeedbackOverlay(state = state, modifier = Modifier.fillMaxSize())

            // GuardCmd 力场罩(径向渐变光圈 + 边缘描边)— state.isGuarded 切换时 600ms 淡入/淡出
            GuardOverlay(
                isGuarded = state.isGuarded,
                modifier = Modifier.fillMaxSize()
            )

            // DragZoom 线框可视化(平台拉框聚焦)— state.dragZoomRect 写入时 200ms 入 / 1.4× 放大 / 600ms 淡出
            DragZoomOverlay(
                rect = state.dragZoomRect,
                modifier = Modifier.fillMaxSize()
            )

            // 设备配置族(GB-2022 A.2.3.2)的只读叠层:画面遮挡(黑块)+ 前端 OSD 文本。
            // 落位是**全画布**而不是某个角 —— 协议坐标是绝对像素、以画面左上角为原点,
            // 只能在整幅画面上还原。同样不带 clickable,手势照常穿透(见该文件头的硬约束)。
            // 排在装饰层最后:遮挡是"画面内容",该盖住磨砂/雨刷这些装饰效果。
            DeviceConfigOverlay(
                config = state.deviceConfig,
                modifier = Modifier.fillMaxSize()
            )

            // 目标跟踪框(GB-2022 A.2.3.1.14)—— **持续显示**,直到平台下发 Stop。
            // 排在这层**最上面**:它是"设备自己画在画面上的标注"(跟 OSD 同类),该盖住遮挡黑块
            // 之外的其它叠加;遮挡是"画面内容被挡掉",两者语义不冲突,先后只影响可见性。
            // ⛔ 为什么必须在画布上:9.3.1 d) 把目标跟踪列为**无应答命令**(表 1 序号 13 应答栏
            //    "（无）"),平台收不到任何回执;附录 A 也**没有**查询命令能把跟踪态读回去 ——
            //    ⇒ 设备屏幕是这条命令唯一的可见面,只写 lastCommand(UI 只认 3 秒内)等于
            //    平台点完"手动跟踪"两侧都毫无动静。
            TargetTrackOverlay(
                track = state.targetTrack,
                modifier = Modifier.fillMaxSize()
            )

            // 📌 「存储卡」原本也在这层(右上角 176dp 卡片),2026-09-19 已收进
            // HUD「状态」页(见 ptz/StorageCardSection.kt)—— 画布只留"画面内容"。

        }
    }
}

/** 中性蓝灰,与 Android/iOS Filament clearColor 同色,给 Compose 层兜底. */
internal val CameraStageBase = Color(0xFF263238)
