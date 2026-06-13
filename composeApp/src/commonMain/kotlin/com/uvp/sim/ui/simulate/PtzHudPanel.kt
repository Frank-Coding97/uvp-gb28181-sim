package com.uvp.sim.ui.simulate

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
 * PTZ 命令 HUD 面板.
 *
 * 订阅 [DeviceControlState.lastCommand],显示:
 * - 命令类型(PTZCmd / IFameCmd / TeleBoot / RecordCmd ...)
 * - Raw hex(PTZCmd 16 字符,其他命令显示参数摘要)
 * - PTZCmd 特化:8 字节拆解表 + 方向/速度语义
 * - 非 PTZ 命令:类型 + 简短参数
 *
 * 纯 commonMain Compose,不依赖 3D 渲染层,可独立挂载到任何 Screen.
 * 真机调试时即使 3D 渲染未上线,这块也能让平台下发的命令实时可见.
 */
@Composable
fun PtzHudPanel(
    state: DeviceControlState,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(UvpColor.Surface)
            .padding(12.dp)
    ) {
        Text(
            "平台控制指令",
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = UvpColor.TextSecondary
        )
        Spacer(Modifier.height(6.dp))
        when (val cmd = state.lastCommand) {
            null -> EmptyHud()
            else -> when (cmd.type) {
                "PTZCmd" -> PtzDetail(cmd)
                else -> GenericDetail(cmd)
            }
        }
    }
}

@Composable
private fun EmptyHud() {
    Text(
        "等待平台控制指令…",
        fontSize = 12.sp,
        color = UvpColor.TextHint
    )
}

@Composable
private fun GenericDetail(cmd: LastDeviceCommand) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TypeChip(cmd.type)
            Spacer(Modifier.width(8.dp))
            Text(
                cmd.rawHex,
                fontSize = 12.sp,
                color = UvpColor.Text,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
private fun PtzDetail(cmd: LastDeviceCommand) {
    val ptz = cmd.ptz
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TypeChip("PTZCmd")
            Spacer(Modifier.width(8.dp))
            Text(
                cmd.rawHex,
                fontSize = 12.sp,
                color = UvpColor.Text,
                fontFamily = FontFamily.Monospace
            )
        }
        Spacer(Modifier.height(8.dp))
        if (ptz != null) {
            PtzByteBreakdown(cmd.rawHex, ptz)
            Spacer(Modifier.height(6.dp))
            Text(
                semanticLine(ptz),
                fontSize = 12.sp,
                color = UvpColor.Primary,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

/** 8 字节拆解表 — byte# / hex / 含义. */
@Composable
private fun PtzByteBreakdown(rawHex: String, ptz: PtzCommand) {
    if (rawHex.length != 16) return
    val bytes = (0 until 8).map { rawHex.substring(it * 2, it * 2 + 2) }
    val labels = listOf(
        "帧头", "版本", "地址", "指令码",
        "Pan速度", "Tilt速度", "Zoom速度", "校验"
    )
    val values = listOf(
        bytes[0].uppercase(),
        bytes[1].uppercase(),
        bytes[2].uppercase(),
        opCodeMnemonic(ptz),
        ptz.panSpeed.toString(),
        ptz.tiltSpeed.toString(),
        ptz.zoomSpeed.toString(),
        bytes[7].uppercase()
    )
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        for (i in 0 until 8) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "B$i",
                    fontSize = 10.sp,
                    color = UvpColor.TextHint,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.width(20.dp)
                )
                Text(
                    bytes[i].uppercase(),
                    fontSize = 11.sp,
                    color = UvpColor.Text,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.width(28.dp)
                )
                Text(
                    labels[i],
                    fontSize = 10.sp,
                    color = UvpColor.TextSecondary,
                    modifier = Modifier.width(60.dp)
                )
                Text(
                    values[i],
                    fontSize = 10.sp,
                    color = UvpColor.Text,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

private fun opCodeMnemonic(ptz: PtzCommand): String {
    val parts = mutableListOf<String>()
    when (ptz.panDirection) {
        PanDirection.LEFT -> parts += "左"
        PanDirection.RIGHT -> parts += "右"
        PanDirection.NONE -> Unit
    }
    when (ptz.tiltDirection) {
        TiltDirection.UP -> parts += "上"
        TiltDirection.DOWN -> parts += "下"
        TiltDirection.NONE -> Unit
    }
    when (ptz.zoomDirection) {
        ZoomDirection.IN -> parts += "ZoomIn"
        ZoomDirection.OUT -> parts += "ZoomOut"
        ZoomDirection.NONE -> Unit
    }
    return if (parts.isEmpty()) "停止" else parts.joinToString("+")
}

private fun semanticLine(ptz: PtzCommand): String {
    val arrow = buildString {
        append(when (ptz.panDirection) {
            PanDirection.LEFT -> "←"
            PanDirection.RIGHT -> "→"
            PanDirection.NONE -> ""
        })
        append(when (ptz.tiltDirection) {
            TiltDirection.UP -> "↑"
            TiltDirection.DOWN -> "↓"
            TiltDirection.NONE -> ""
        })
        if (this.isEmpty()) {
            append(when (ptz.zoomDirection) {
                ZoomDirection.IN -> "⊕"
                ZoomDirection.OUT -> "⊖"
                ZoomDirection.NONE -> "■"
            })
        }
    }
    return "$arrow  pan=${ptz.panSpeed}  tilt=${ptz.tiltSpeed}  zoom=${ptz.zoomSpeed}"
}

@Composable
private fun TypeChip(type: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(typeChipBg(type))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            type,
            fontSize = 10.sp,
            color = Color.White,
            fontWeight = FontWeight.SemiBold
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
