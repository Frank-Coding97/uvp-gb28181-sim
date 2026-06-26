package com.uvp.sim.ui.simulate.ptz

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uvp.sim.ui.UvpColor
import com.uvp.sim.ui.model.DeviceControlDto
import com.uvp.sim.ui.model.FocusDirectionDto
import com.uvp.sim.ui.model.PtzPoseDto

/**
 * 云台 Tab — 位姿三大字 + 聚焦/光圈进度 + 预置位/巡航 chips.
 */
@Composable
internal fun PtzTabContent(state: DeviceControlDto) {
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
            ptz.focusDirection != FocusDirectionDto.NONE
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
        // 巡航轨迹(若已设过)
        if (state.cruiseTracks.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("巡航", fontSize = 10.sp, color = UvpColor.TextHint, fontWeight = FontWeight.Medium)
                Spacer(Modifier.width(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    state.cruiseTracks.toSortedMap().forEach { (trackNum, points) ->
                        val active = state.activeCruiseTrack == trackNum
                        val bg = if (active) UvpColor.Primary else UvpColor.BorderLight
                        val fg = if (active) Color.White else UvpColor.TextSecondary
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(99.dp))
                                .background(bg)
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                        ) {
                            Text(
                                "T$trackNum·${points.size}",
                                color = fg,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }
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
private fun PresetChipRow(presets: Map<Int, PtzPoseDto>, currentIndex: Int?) {
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
private fun RowScope.PresetChip(
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
