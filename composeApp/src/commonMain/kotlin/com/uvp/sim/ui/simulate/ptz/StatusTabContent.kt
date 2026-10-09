package com.uvp.sim.ui.simulate.ptz

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CenterFocusStrong
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uvp.sim.ui.UvpColor
import com.uvp.sim.ui.model.DeviceControlDto
import com.uvp.sim.ui.model.LastDeviceCommandDto

/** 「请求关键帧」指示灯的保持时长 —— 平台的一次性命令，亮这么久就自动灭。 */
internal const val KEY_FRAME_FEEDBACK_MS = 2_000L

/**
 * 平台是否**刚刚**请求过关键帧（`IFameCmd` / `IFrameCmd` 两种拼写都认）。
 *
 * ⛔ 两种拼写都认是刻意的：本仓历史里两种都出现过，只认一种会让平台下发的另一半静默不亮。
 */
internal fun isKeyFrameRequestActive(command: LastDeviceCommandDto?, nowMs: Long): Boolean {
    if (command?.type != "IFameCmd" && command?.type != "IFrameCmd") return false
    return nowMs - command.timestampMs in 0..KEY_FRAME_FEEDBACK_MS
}

/** 设备页：持续状态、存储卡及设备维护反馈。 */
@Composable
internal fun StatusTabContent(state: DeviceControlDto) {
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
                label = "雨刷",
                active = state.auxStates[1] == true,
                activeColor = UvpColor.Info,
                modifier = Modifier.weight(1f),
            )
        }
        StorageCardSection(state = state)
        ImageEventRow("重启", if (state.isRebooting) "正在重启" else "暂无重启任务", state.isRebooting)
        state.upgradeProgress?.let { UpgradeProgressRow(it) }
        val cmd = state.lastCommand
        if (cmd?.type == "FormatSDCard") ImageEventRow("格式化请求", cmd.rawHex, true)
        if (cmd?.type == "DeviceUpgrade" && state.upgradeProgress == null) {
            ImageEventRow("升级请求", cmd.rawHex, true)
        }
    }
}

/**
 * 「请求关键帧」卡片 —— 满宽、与四盏状态灯同一套视觉语言（描边 + 角标 + 两行文字）。
 *
 * ⭐ 做成**卡片**而不是原来那种细窄的行：它要能一眼看出"平台在要关键帧了"，
 * 而那是个持续 2 秒的**瞬时事件** —— 细行在扫视时太容易被漏掉。
 */
@Composable
internal fun KeyFrameRequestCard(active: Boolean) {
    val background = if (active) UvpColor.Primary else UvpColor.Bg
    val foreground = if (active) Color.White else UvpColor.TextSecondary
    val border = if (active) UvpColor.Primary else UvpColor.BorderLight

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(background)
            .border(1.dp, border, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.CenterFocusStrong,
            contentDescription = null,
            tint = foreground,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(8.dp))
        Column {
            Text(
                "请求关键帧",
                fontSize = 11.sp,
                // ⛔ 显式给 lineHeight（不给就按 24.sp 记账，卡片会白高一截）。
                lineHeight = 14.sp,
                color = foreground,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                softWrap = false,
            )
            Text(
                if (active) "已收到平台请求" else "等待平台请求",
                fontSize = 9.sp,
                lineHeight = 14.sp,
                color = if (active) Color.White.copy(alpha = 0.82f) else UvpColor.TextHint,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                softWrap = false,
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
                lineHeight = 16.sp,
                color = if (active) activeColor else UvpColor.TextSecondary,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                if (active) "已开启" else "未开启",
                fontSize = 9.sp,
                lineHeight = 14.sp,
                color = if (active) activeColor.copy(alpha = 0.85f) else UvpColor.TextHint,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}
