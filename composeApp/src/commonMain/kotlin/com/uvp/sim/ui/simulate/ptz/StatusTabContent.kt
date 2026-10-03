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
import com.uvp.sim.ui.simulate.useTickingNow

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

/**
 * 状态 Tab — 录像 / 布防 / 报警 / 重启 4 盏大状态灯 + 「请求关键帧」一次性指示灯。
 *
 * ## 2026-09-19：「请求关键帧」从「图像」页搬到这里
 * 它虽然属于"图像类命令"，但语义是**设备此刻在做什么**（一次性、2 秒自动灭的指示灯），
 * 与这四盏灯同类；放在「图像」页里和"配置/坐标"那些**有持续状态的东西**混在一起，
 * 反而让人以为它是一条可配置项。用户的整理要求正是「放到状态下面，做成四四方方的卡片」。
 *
 * ## 高度预算（HUD 内容区**定高 284dp、不滚动**）
 * ```
 *   两行状态灯 2 × 68 = 136dp   （BigStatusLamp 的两行文字各按 24dp 行框记账，见下方警示）
 *   行间距      3 × 8  =  24dp
 *   关键帧卡片          56dp
 *   存储卡区块          59dp   （2 张卡的最坏情况，加法见 [StorageCardSection]）
 *   ─────────────────────────
 *   合计 ≈ 275dp  →  余 9dp ✓
 * ```
 * ⛔ **这一页已经塞满了**：往后再加任何一块，都得先腾地方（把某块压矮或搬去别的 Tab），
 * 否则被挤出去的正好是最后一行 —— 而 HUD 内容区不滚动，它不会"滑出来"。
 * ⚠️ **`BigStatusLamp` 里的两个 `Text` 没有显式给 `lineHeight`**，所以它们按 Material3
 * `bodyLarge` 的 **24sp** 行框记账（11.sp 的字也是 24dp 高）—— 一盏灯因此是 68dp 而不是
 * 直觉上的 ~44dp。上面的 136dp 是**固定开销**，往本页再加内容前先把它算进去。
 * （本文件新增的卡片本身给了 `lineHeight`，所以它是 56dp 而不是 68dp。）
 */
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
                label = "重启",
                active = state.isRebooting,
                activeColor = UvpColor.Info,
                modifier = Modifier.weight(1f),
            )
        }
        // 平台请求关键帧（GB-2022 A.2.3.1.5）—— 2 秒后自动灭的一次性指示灯。
        KeyFrameRequestCard(
            active = isKeyFrameRequestActive(state.lastCommand, useTickingNow(intervalMs = 250L)),
        )
        // 「存储卡」区块（GB-2022 A.2.4.14 查询 / A.2.6.16 应答）。
        // 2026-09-19 从 3D 画面右上角的悬浮卡片**收进本页**：跟上面四盏灯同类
        // （"设备现在处于什么状态"的回显），摆在画布上只会糊住画面。
        StorageCardSection(state = state)
    }
}

/**
 * 「请求关键帧」卡片 —— 满宽、与四盏状态灯同一套视觉语言（描边 + 角标 + 两行文字）。
 *
 * ⭐ 做成**卡片**而不是原来那种细窄的行：它要能一眼看出"平台在要关键帧了"，
 * 而那是个持续 2 秒的**瞬时事件** —— 细行在扫视时太容易被漏掉。
 */
@Composable
private fun KeyFrameRequestCard(active: Boolean) {
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
