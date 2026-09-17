package com.uvp.sim.ui.simulate

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uvp.sim.ui.UvpColor
import com.uvp.sim.ui.model.DeviceCommandCategoryDto
import com.uvp.sim.ui.model.DeviceControlDto
import com.uvp.sim.ui.simulate.ptz.AuxTabContent
import com.uvp.sim.ui.simulate.ptz.HudTabRow
import com.uvp.sim.ui.simulate.ptz.ImageTabContent
import com.uvp.sim.ui.simulate.ptz.PtzTabContent
import com.uvp.sim.ui.simulate.ptz.StatusTabContent

/**
 * 平台控制 HUD — 4 Tab 分组(2026-06-18 PM 重设计):
 *   云台 / 状态 / 图像 / 辅助
 *
 * - 平台命令到达时自动切到对应 Tab(老板看屏幕就知道平台在做什么)
 * - 全中文化(REC → 录像 / GUARD → 布防 / Pan → 水平 ...)
 * - 设备侧状态回显严格只读(spec AC2):所有 chip / 灯 / 进度条都只是平台下发的状态
 * - **例外**:云台页内嵌一个本机方盘 + 光圈/聚焦长按键,那是模拟器自己的输入
 *   (演示"设备本地也能推镜头"),不表示收到平台命令 —— 分别由 [onLocalPtzAdjust] /
 *   [onLocalLensAdjust] 回调直接交给硬件层
 *
 * 命令到 Tab 映射:
 *   云台: PTZCmd(Motion+Preset) / PTZPreciseCtrl / HomePosition
 *   状态: RecordCmd / GuardCmd / AlarmCmd / TeleBoot
 *   图像: IFameCmd / SnapShotCmd / DragZoomIn-Out / DeviceConfig / DeviceUpgrade / FormatSDCard / TargetTrack
 *   辅助: PTZCmd(Aux on/off,byte3=0x89/0x8A)
 *
 * 2026-06-26 PR-F T1:4 Tab 内容拆到 [ptz] 子包,本文件只保留主入口编排.
 * 2026-06-27 轨 ④ PR-UI-PROTOCOL-FIX:HudTab.fromCommand 不再 parse rawHex,改读
 * `lastCommandCategory`(语义枚举,派生在 commonMain `deriveCommandCategory`).
 * 2026-09-16 本机手操几经搬迁(画布底部横条 → 画布下方独立行),最终收进本面板的
 * 云台页做成方盘控制台;其余三页仍是纯「平台指令回放」.
 */
enum class HudTab(val title: String) {
    Ptz("云台"),
    Status("状态"),
    Image("图像"),
    Aux("辅助");

    companion object {
        /** 把 UI DTO 的语义分类映成 HudTab,null 表示不切. */
        fun fromCategory(category: DeviceCommandCategoryDto?): HudTab? = when (category) {
            DeviceCommandCategoryDto.Ptz -> Ptz
            DeviceCommandCategoryDto.Status -> Status
            DeviceCommandCategoryDto.Image -> Image
            DeviceCommandCategoryDto.Aux -> Aux
            null -> null
        }
    }
}

@Composable
fun PtzHudPanel(
    state: DeviceControlDto,
    onLocalPtzAdjust: (Float, Float, Float) -> Unit,
    onLocalLensAdjust: (Float, Float) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    var selectedTab by remember { mutableStateOf(HudTab.Ptz) }

    // 各 Tab 是否有"未读"红点提示(收到命令但当前没在该 Tab)
    val tabBadges = remember { mutableStateOf<Set<HudTab>>(emptySet()) }

    // 平台命令到达 → 自动切到对应 Tab + 清掉该 Tab 的红点
    LaunchedEffect(state.lastCommand?.timestampMs) {
        val target = HudTab.fromCategory(state.lastCommandCategory) ?: return@LaunchedEffect
        if (target != selectedTab) {
            // 给其他非目标 Tab 留红点(不清掉),目标 Tab 切过去就消红点
            tabBadges.value = tabBadges.value + target
            selectedTab = target
        }
    }
    // 用户切到某 Tab → 清掉该 Tab 的红点
    LaunchedEffect(selectedTab) {
        tabBadges.value = tabBadges.value - selectedTab
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(UvpColor.Surface)
            .border(1.dp, UvpColor.BorderLight, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 11.dp)
    ) {
        // 标题
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "平台控制 HUD",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = UvpColor.Text
            )
            Spacer(Modifier.weight(1f))
            // 当前 tab 提示
            Text(
                "平台触发自动切换",
                fontSize = 9.sp,
                color = UvpColor.TextHint,
                fontWeight = FontWeight.Medium
            )
        }
        Spacer(Modifier.height(8.dp))
        // Tab 行
        HudTabRow(
            selected = selectedTab,
            onSelect = { selectedTab = it },
            badges = tabBadges.value,
        )
        Spacer(Modifier.height(10.dp))
        // 固定高度避免 tab 切换时面板抖动.
        // 2026-09-16 云台页重做成「云台控制台」(方盘 + 四周参数 + 预置位/看守位)后,
        // 需求高从 200dp 抬到 260dp;多出来的这块来自画布下方那条本机调试条被收进本页,
        // 画布高度基本维持调整后的值.
        // 2026-09-16 下午:方盘按键 28→36dp、方盘边长 98→128dp(+30dp),按用户反馈
        // 「卡片再大一点」,这里跟着抬到 284dp。云台页最高态(有巡航轨迹)实测约 271dp,
        // 余量 13dp。再加高就得重新分配画布高度了。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(284.dp),
            contentAlignment = Alignment.TopStart,
        ) {
            AnimatedContent(
                targetState = selectedTab,
                transitionSpec = {
                    fadeIn(animationSpec = tween(220)) togetherWith
                        fadeOut(animationSpec = tween(180))
                },
                label = "hud-tab-content"
            ) { tab ->
                when (tab) {
                    HudTab.Ptz -> PtzTabContent(state, onLocalPtzAdjust, onLocalLensAdjust)
                    HudTab.Status -> StatusTabContent(state)
                    HudTab.Image -> ImageTabContent(state)
                    HudTab.Aux -> AuxTabContent(state)
                }
            }
        }
    }
}
