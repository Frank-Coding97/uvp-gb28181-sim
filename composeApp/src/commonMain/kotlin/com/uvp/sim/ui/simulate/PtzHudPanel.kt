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
import com.uvp.sim.ui.model.DeviceControlDto
import com.uvp.sim.ui.model.LastDeviceCommandDto
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
 * - 设备 UI 严格只读(spec AC2),所有 chip / 灯 / 开关都是状态展示
 *
 * 命令到 Tab 映射:
 *   云台: PTZCmd(Motion+Preset) / PTZPreciseCtrl / HomePosition
 *   状态: RecordCmd / GuardCmd / AlarmCmd / TeleBoot
 *   图像: IFameCmd / SnapShotCmd / DragZoomIn-Out / DeviceConfig / DeviceUpgrade / FormatSDCard / TargetTrack
 *   辅助: PTZCmd(Aux on/off,byte3=0x89/0x8A)
 *
 * 2026-06-26 PR-F T1:4 Tab 内容拆到 [ptz] 子包,本文件只保留主入口编排.
 */
enum class HudTab(val title: String) {
    Ptz("云台"),
    Status("状态"),
    Image("图像"),
    Aux("辅助");

    companion object {
        /** 根据 lastCommand 类型 + rawHex 标记决定该切到哪个 Tab,null 表示不切. */
        fun fromCommand(cmd: LastDeviceCommandDto?): HudTab? {
            if (cmd == null) return null
            return when (cmd.type) {
                "PTZCmd" -> {
                    // 辅助控制的 lastCommand.rawHex 由 dispatcher 写中文(如"雨刷 ON")
                    val raw = cmd.rawHex
                    val isAux = raw.startsWith("雨刷") || raw.startsWith("红外灯") ||
                        raw.startsWith("加热") || raw.startsWith("除雾") ||
                        raw.startsWith("制冷") || raw.startsWith("Aux")
                    if (isAux) Aux else Ptz
                }
                "PTZPreciseCtrl" -> Ptz
                "RecordCmd", "GuardCmd", "AlarmCmd", "TeleBoot" -> Status
                "IFameCmd", "SnapShotCmd", "DeviceConfig",
                "DeviceUpgrade", "FormatSDCard", "TargetTrack" -> Image
                else -> null
            }
        }
    }
}

@Composable
fun PtzHudPanel(
    state: DeviceControlDto,
    modifier: Modifier = Modifier,
) {
    var selectedTab by remember { mutableStateOf(HudTab.Ptz) }

    // 各 Tab 是否有"未读"红点提示(收到命令但当前没在该 Tab)
    val tabBadges = remember { mutableStateOf<Set<HudTab>>(emptySet()) }

    // 平台命令到达 → 自动切到对应 Tab + 清掉该 Tab 的红点
    LaunchedEffect(state.lastCommand?.timestampMs) {
        val target = HudTab.fromCommand(state.lastCommand) ?: return@LaunchedEffect
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
                "平台控制",
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
        // Tab 内容 — 固定高度避免不同 tab 切换时整体面板高度抖动(老板 06-18 反馈)
        // 184dp 实测云台 Tab(三大字 74 + 聚焦光圈 52 + 预置位 28 + 间距 16 = 170)+ 余量
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(184.dp),
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
                    HudTab.Ptz -> PtzTabContent(state)
                    HudTab.Status -> StatusTabContent(state)
                    HudTab.Image -> ImageTabContent(state)
                    HudTab.Aux -> AuxTabContent(state)
                }
            }
        }
    }
}
