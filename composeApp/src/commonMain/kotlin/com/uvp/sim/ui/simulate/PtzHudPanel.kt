package com.uvp.sim.ui.simulate

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uvp.sim.domain.DeviceControlState
import com.uvp.sim.domain.LastDeviceCommand
import com.uvp.sim.gb28181.PanDirection
import com.uvp.sim.gb28181.PtzCommand
import com.uvp.sim.gb28181.TiltDirection
import com.uvp.sim.gb28181.ZoomDirection
import com.uvp.sim.ui.UvpColor

/**
 * 平台控制指令面板(2026-06-13 老板验收后产品化重构).
 *
 * 三层信息架构:
 *   1. 大字态势 — Pan/Tilt/Zoom 当前姿态(主要信息)
 *   2. 命令 chip + hex — 最近一次平台下发(辅助信息)
 *   3. 状态指示灯 — REC/GUARD/ALARM/REBOOT(右侧 chip 行)
 *
 * 抛弃:8 字节拆解大表(过度工程师化,产品场景下没人需要看 B0=A5 帧头).
 */
@Composable
fun PtzHudPanel(
    state: DeviceControlState,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(12.dp))
            .background(UvpColor.Surface)
            .border(1.dp, UvpColor.BorderLight, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        // Layer 1: 标题 + 状态灯
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "平台控制指令",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = UvpColor.TextSecondary,
                letterSpacing = 0.5.sp
            )
            Spacer(Modifier.weight(1f))
            StatusDot("REC", state.isRecording, UvpColor.Danger)
            Spacer(Modifier.width(6.dp))
            StatusDot("GUARD", state.isGuarded, UvpColor.Success)
            Spacer(Modifier.width(6.dp))
            StatusDot("ALARM", state.isAlarming, UvpColor.Warning)
            Spacer(Modifier.width(6.dp))
            StatusDot("REBOOT", state.isRebooting, UvpColor.Info)
        }

        Spacer(Modifier.height(8.dp))

        // Layer 2: 大字姿态(Pan / Tilt / Zoom 居中)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            PoseStat("Pan", state.panAngle, "°", isActive = state.panSpeed != 0f)
            PoseStatDivider()
            PoseStat("Tilt", state.tiltAngle, "°", isActive = state.tiltSpeed != 0f)
            PoseStatDivider()
            PoseStat("Zoom", state.zoomLevel, "×", isActive = state.zoomSpeed != 0f)
        }

        Spacer(Modifier.height(8.dp))

        // Layer 3: 最近命令 chip + hex / 摘要
        when (val cmd = state.lastCommand) {
            null -> EmptyRow()
            else -> CommandRow(cmd)
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
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
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
                fontSize = 22.sp,
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
        "%.1f".format(value)
    } else {
        val rounded = kotlin.math.round(value).toInt()
        if (rounded > 0) "+$rounded" else "$rounded"
    }
}

@Composable
private fun StatusDot(label: String, active: Boolean, activeColor: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(7.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(if (active) activeColor else UvpColor.Border)
        )
        Spacer(Modifier.width(3.dp))
        Text(
            label,
            fontSize = 9.sp,
            color = if (active) activeColor else UvpColor.TextHint,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            letterSpacing = 0.3.sp
        )
    }
}

@Composable
private fun EmptyRow() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(UvpColor.Border)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "等待平台下发指令…",
            fontSize = 12.sp,
            color = UvpColor.TextHint
        )
    }
}

@Composable
private fun CommandRow(cmd: LastDeviceCommand) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TypeChip(cmd.type)
        Spacer(Modifier.width(8.dp))
        Text(
            text = decodedSummary(cmd),
            fontSize = 12.sp,
            color = UvpColor.Text,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f)
        )
        if (cmd.type == "PTZCmd" && cmd.rawHex.length == 16) {
            Text(
                cmd.rawHex,
                fontSize = 10.sp,
                color = UvpColor.TextHint,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 0.5.sp
            )
        }
    }
}

private fun decodedSummary(cmd: LastDeviceCommand): String {
    val ptz = cmd.ptz
    if (cmd.type == "PTZCmd" && ptz != null) {
        return ptzArrowAndSpeeds(ptz)
    }
    return "${cmd.type}: ${cmd.rawHex.take(30)}"
}

private fun ptzArrowAndSpeeds(p: PtzCommand): String {
    val arrow = buildString {
        append(when (p.panDirection) {
            PanDirection.LEFT -> "←"
            PanDirection.RIGHT -> "→"
            PanDirection.NONE -> ""
        })
        append(when (p.tiltDirection) {
            TiltDirection.UP -> "↑"
            TiltDirection.DOWN -> "↓"
            TiltDirection.NONE -> ""
        })
        if (this.isEmpty()) {
            append(when (p.zoomDirection) {
                ZoomDirection.IN -> "⊕"
                ZoomDirection.OUT -> "⊖"
                ZoomDirection.NONE -> "■停"
            })
        }
    }
    val speeds = "Pan ${p.panSpeed} · Tilt ${p.tiltSpeed} · Zoom ${p.zoomSpeed}"
    return "$arrow  $speeds"
}

@Composable
private fun TypeChip(type: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(typeChipBg(type))
            .padding(horizontal = 7.dp, vertical = 2.dp)
    ) {
        Text(
            type,
            fontSize = 10.sp,
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.3.sp
        )
    }
}

private fun typeChipBg(type: String): Color = when (type) {
    "PTZCmd" -> UvpColor.Primary
    "IFameCmd" -> UvpColor.Info
    "TeleBoot" -> UvpColor.Warning
    "RecordCmd" -> UvpColor.Danger
    "GuardCmd" -> UvpColor.Success
    "AlarmCmd" -> UvpColor.Danger
    else -> UvpColor.TextSecondary
}
