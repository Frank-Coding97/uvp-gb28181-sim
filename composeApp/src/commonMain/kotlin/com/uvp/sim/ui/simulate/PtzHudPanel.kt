package com.uvp.sim.ui.simulate

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AcUnit
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.WaterDrop
import androidx.compose.material.icons.outlined.Whatshot
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uvp.sim.domain.DeviceControlState
import com.uvp.sim.domain.LastDeviceCommand
import com.uvp.sim.domain.PtzPose
import com.uvp.sim.gb28181.AuxFunction
import com.uvp.sim.ui.UvpColor

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
 */
enum class HudTab(val title: String) {
    Ptz("云台"),
    Status("状态"),
    Image("图像"),
    Aux("辅助");

    companion object {
        /** 根据 lastCommand 类型 + rawHex 标记决定该切到哪个 Tab,null 表示不切. */
        fun fromCommand(cmd: LastDeviceCommand?): HudTab? {
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
    state: DeviceControlState,
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
        TabRow(
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

// ─────────────────────────────────────────────────────────────────────
// Tab 行
// ─────────────────────────────────────────────────────────────────────

@Composable
private fun TabRow(
    selected: HudTab,
    onSelect: (HudTab) -> Unit,
    badges: Set<HudTab>,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(UvpColor.Bg)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for (tab in HudTab.entries) {
            TabItem(
                tab = tab,
                selected = tab == selected,
                hasBadge = tab in badges,
                onClick = { onSelect(tab) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun TabItem(
    tab: HudTab,
    selected: Boolean,
    hasBadge: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bg = if (selected) UvpColor.Surface else Color.Transparent
    val fg = if (selected) UvpColor.Primary else UvpColor.TextSecondary
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .clickable { onClick() }
            .padding(vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                tab.title,
                color = fg,
                fontSize = 12.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            )
            if (hasBadge) {
                Spacer(Modifier.width(4.dp))
                Box(
                    Modifier
                        .size(5.dp)
                        .clip(RoundedCornerShape(99.dp))
                        .background(UvpColor.Danger)
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────
// 云台 Tab
// ─────────────────────────────────────────────────────────────────────

@Composable
private fun PtzTabContent(state: DeviceControlState) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(UvpColor.Bg)
                .padding(horizontal = 8.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            PoseStat("水平", state.panAngle, "°", isActive = state.panSpeed != 0f)
            PoseStatDivider()
            PoseStat("俯仰", state.tiltAngle, "°", isActive = state.tiltSpeed != 0f)
            PoseStatDivider()
            PoseStat("变焦", state.zoomLevel, "×", isActive = state.zoomSpeed != 0f)
        }
        Spacer(Modifier.height(8.dp))
        // 聚焦 / 光圈进度条(平台 PTZCmd byte3 bit6/bit7 累积驱动)
        val cmd = state.lastCommand
        val ptz = cmd?.ptz
        val focusActive = cmd?.type == "PTZCmd" && ptz != null &&
            ptz.focusDirection != com.uvp.sim.gb28181.FocusDirection.NONE
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LevelBar(
                label = "聚焦",
                level = state.focusLevel,
                modifier = Modifier.weight(1f),
                hint = if (focusActive) "调节中" else null,
            )
            LevelBar(
                label = "光圈",
                level = state.irisLevel,
                modifier = Modifier.weight(1f),
                hint = "等待协议",  // Iris byte3 编码各家不同,真机抓包后再补
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("预置位", fontSize = 10.sp, color = UvpColor.TextHint, fontWeight = FontWeight.Medium)
            Spacer(Modifier.width(8.dp))
            PresetChipRow(presets = state.presets, currentIndex = state.currentPresetIndex)
        }
    }
}

/** 聚焦 / 光圈横向进度条 — 0~1 范围,只读显示. */
@Composable
private fun LevelBar(
    label: String,
    level: Float,
    modifier: Modifier = Modifier,
    hint: String? = null,
) {
    val pct = (level.coerceIn(0f, 1f) * 100f).toInt()
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(UvpColor.Bg)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 10.sp, color = UvpColor.TextHint, fontWeight = FontWeight.Medium)
            Spacer(Modifier.weight(1f))
            Text(
                "$pct%",
                fontSize = 11.sp,
                color = UvpColor.Text,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
            )
            if (hint != null) {
                Spacer(Modifier.width(6.dp))
                Text(hint, fontSize = 9.sp, color = UvpColor.Primary, fontWeight = FontWeight.Medium)
            }
        }
        Spacer(Modifier.height(4.dp))
        // 横向进度槽 + 填充
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(99.dp))
                .background(UvpColor.BorderLight),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(level.coerceIn(0f, 1f))
                    .height(4.dp)
                    .clip(RoundedCornerShape(99.dp))
                    .background(UvpColor.Primary)
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────
// 状态 Tab
// ─────────────────────────────────────────────────────────────────────

@Composable
private fun StatusTabContent(state: DeviceControlState) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BigStatusLamp(
                label = "录像",
                active = state.isRecording,
                activeColor = UvpColor.Danger,
                modifier = Modifier.weight(1f),
            )
            BigStatusLamp(
                label = "布防",
                active = state.isGuarded,
                activeColor = UvpColor.Success,
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BigStatusLamp(
                label = "报警",
                active = state.isAlarming,
                activeColor = UvpColor.Warning,
                modifier = Modifier.weight(1f),
            )
            BigStatusLamp(
                label = "重启",
                active = state.isRebooting,
                activeColor = UvpColor.Info,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun BigStatusLamp(
    label: String,
    active: Boolean,
    activeColor: Color,
    modifier: Modifier = Modifier,
) {
    val pulse by animateFloatAsState(
        targetValue = if (active) 1f else 0f,
        animationSpec = tween(durationMillis = 400),
        label = "lamp-pulse-$label",
    )
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (active) activeColor.copy(alpha = 0.10f) else UvpColor.Bg)
            .border(
                1.dp,
                if (active) activeColor.copy(alpha = 0.35f) else UvpColor.BorderLight,
                RoundedCornerShape(8.dp)
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(12.dp)
                .clip(RoundedCornerShape(99.dp))
                .background(if (active) activeColor else UvpColor.Border)
                .graphicsLayer { scaleX = 1f + pulse * 0.1f; scaleY = 1f + pulse * 0.1f }
        )
        Spacer(Modifier.width(8.dp))
        Column {
            Text(
                label,
                fontSize = 11.sp,
                color = if (active) activeColor else UvpColor.TextSecondary,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                if (active) "已开启" else "未开启",
                fontSize = 9.sp,
                color = if (active) activeColor.copy(alpha = 0.85f) else UvpColor.TextHint,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────
// 图像 Tab — 一次性事件 / 配置变更
// ─────────────────────────────────────────────────────────────────────

@Composable
private fun ImageTabContent(state: DeviceControlState) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // 拉框聚焦
        val rect = state.dragZoomRect
        ImageEventRow(
            label = "拉框聚焦",
            value = if (rect != null) "(${rect.midX}, ${rect.midY})  ${rect.lengthX}×${rect.lengthY}" else "—",
            highlight = rect != null,
        )
        // 最近一条相关命令
        val cmd = state.lastCommand
        val cmdLabel = when (cmd?.type) {
            "IFameCmd" -> "强制关键帧" to "已下发"
            "SnapShotCmd" -> "抓拍" to "已下发"
            "DeviceConfig" -> "设备配置" to (cmd.rawHex.take(20))
            "DeviceUpgrade" -> "设备升级" to ("v${cmd.rawHex}")
            "FormatSDCard" -> "格式化 SD" to (cmd.rawHex)
            "TargetTrack" -> "目标跟踪" to (cmd.rawHex)
            else -> null
        }
        if (cmdLabel != null) {
            ImageEventRow(
                label = cmdLabel.first,
                value = cmdLabel.second,
                highlight = true,
            )
        } else {
            ImageEventRow(
                label = "最近命令",
                value = "暂无图像类指令",
                highlight = false,
            )
        }
    }
}

@Composable
private fun ImageEventRow(
    label: String,
    value: String,
    highlight: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (highlight) UvpColor.PrimaryLight else UvpColor.Bg)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            fontSize = 11.sp,
            color = if (highlight) UvpColor.Primary else UvpColor.TextSecondary,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.weight(1f))
        Text(
            value,
            fontSize = 11.sp,
            color = if (highlight) UvpColor.PrimaryDark else UvpColor.TextHint,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ─────────────────────────────────────────────────────────────────────
// 辅助 Tab — 5 个开关图标
// ─────────────────────────────────────────────────────────────────────

@Composable
private fun AuxTabContent(state: DeviceControlState) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (func in AuxFunction.entries) {
            val on = state.auxStates[func.index] == true
            val sinceMs = state.auxTimestamps[func.index]
            AuxToggle(
                func = func,
                on = on,
                sinceMs = sinceMs,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun AuxToggle(
    func: AuxFunction,
    on: Boolean,
    sinceMs: Long?,
    modifier: Modifier = Modifier,
) {
    val tint = if (on) UvpColor.Primary else UvpColor.TextHint
    val bg = if (on) UvpColor.PrimaryLight else UvpColor.Bg
    val border = if (on) UvpColor.Primary.copy(alpha = 0.4f) else UvpColor.BorderLight
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(8.dp))
            .padding(vertical = 10.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            auxIcon(func),
            contentDescription = func.displayName,
            tint = tint,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            func.displayName,
            fontSize = 10.sp,
            color = tint,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(2.dp))
        // 状态文案:运行中显示运行时长,关闭显示 OFF 或最近一次时长
        AuxStatusLine(on = on, sinceMs = sinceMs)
    }
}

/** 显示"已开 X 分钟" / "OFF" 等运行状态副文本,每秒刷新一次. */
@Composable
private fun AuxStatusLine(on: Boolean, sinceMs: Long?) {
    val nowMs = useTickingNow(intervalMs = 1000L)
    val text = when {
        on && sinceMs != null -> "已开 ${formatDuration(nowMs - sinceMs)}"
        on -> "ON"
        else -> "OFF"
    }
    Text(
        text,
        fontSize = 8.sp,
        color = if (on) UvpColor.PrimaryDark else UvpColor.TextHint,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
    )
}

/** 简短时长格式: <60s → 'Ns' / <60min → 'NmS' / 否则 'NhM' . */
private fun formatDuration(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return when {
        s < 60 -> "${s}s"
        s < 3600 -> "${s / 60}m${s % 60}"
        else -> "${s / 3600}h${(s % 3600) / 60}m"
    }
}

private fun auxIcon(func: AuxFunction): ImageVector = when (func) {
    AuxFunction.Wiper -> Icons.Outlined.WaterDrop
    AuxFunction.InfraredLight -> Icons.Outlined.Visibility
    AuxFunction.Heater -> Icons.Outlined.LocalFireDepartment
    AuxFunction.Defog -> Icons.Outlined.CleaningServices
    AuxFunction.Cooler -> Icons.Outlined.AcUnit
}

// ─────────────────────────────────────────────────────────────────────
// 复用组件:PoseStat / 预置位 chip
// ─────────────────────────────────────────────────────────────────────

@Composable
private fun PoseStat(
    label: String,
    value: Float,
    unit: String,
    isActive: Boolean,
) {
    val valueColor = if (isActive) UvpColor.Primary else UvpColor.Text
    val indicatorColor = if (isActive) UvpColor.Primary else UvpColor.Border
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(82.dp)
    ) {
        Box(
            Modifier
                .size(width = 18.dp, height = 3.dp)
                .clip(RoundedCornerShape(99.dp))
                .background(indicatorColor)
        )
        Spacer(Modifier.height(5.dp))
        Text(
            label,
            fontSize = 10.sp,
            color = UvpColor.TextHint,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.4.sp
        )
        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                formatPose(value, unit),
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = valueColor,
                fontFamily = FontFamily.Monospace
            )
            Text(
                unit,
                fontSize = 11.sp,
                color = UvpColor.TextSecondary,
                modifier = Modifier.padding(start = 1.dp, bottom = 3.dp)
            )
        }
    }
}

@Composable
private fun PoseStatDivider() {
    Box(
        Modifier
            .width(1.dp)
            .height(28.dp)
            .background(UvpColor.BorderLight)
    )
}

private fun formatPose(value: Float, unit: String): String {
    return if (unit == "×") {
        // KMP 友好的 "%.1f"
        val rounded = kotlin.math.round(value * 10f).toInt()
        val whole = rounded / 10
        val frac = kotlin.math.abs(rounded % 10)
        "$whole.$frac"
    } else {
        val rounded = kotlin.math.round(value).toInt()
        if (rounded > 0) "+$rounded" else "$rounded"
    }
}

@Composable
private fun PresetChipRow(presets: Map<Int, PtzPose>, currentIndex: Int?) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        for (idx in 1..8) {
            PresetChip(
                idx = idx,
                isSet = presets.containsKey(idx),
                isCurrent = currentIndex == idx,
            )
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.PresetChip(
    idx: Int,
    isSet: Boolean,
    isCurrent: Boolean,
) {
    val bg = when {
        isCurrent -> UvpColor.Primary
        isSet -> UvpColor.PrimaryLight
        else -> UvpColor.BorderLight
    }
    val fg = when {
        isCurrent -> Color.White
        isSet -> UvpColor.Primary
        else -> UvpColor.TextHint
    }
    val targetScale = if (isCurrent) 1.05f else 1f
    val scale by animateFloatAsState(
        targetScale,
        animationSpec = tween(durationMillis = if (isCurrent) 200 else 300),
        label = "preset-scale-$idx"
    )
    Box(
        modifier = Modifier
            .weight(1f)
            .height(28.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .let { m ->
                if (isCurrent) m.border(1.dp, UvpColor.Primary, RoundedCornerShape(6.dp))
                else m
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "P$idx",
            color = fg,
            fontSize = 11.sp,
            fontWeight = if (isCurrent || isSet) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}
