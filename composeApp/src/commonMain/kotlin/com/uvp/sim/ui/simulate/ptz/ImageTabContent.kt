package com.uvp.sim.ui.simulate.ptz

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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uvp.sim.ui.UvpColor
import com.uvp.sim.ui.model.DeviceControlDto
import com.uvp.sim.ui.model.UpgradeProgressDto
import com.uvp.sim.ui.model.UpgradeResultDto

/**
 * 图像 Tab — 一次性事件 / 配置变更展示.
 * GB-2022 §9.13 在线升级 / 拉框聚焦 / 抓拍 / 强制 I 帧 / 设备配置 等.
 */
@Composable
internal fun ImageTabContent(state: DeviceControlDto) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // GB-2022 §9.13 在线升级进度条(优先于其他事件显示)
        val upgrade = state.upgradeProgress
        if (upgrade != null) {
            UpgradeProgressRow(upgrade)
        }
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
        if (cmdLabel != null && upgrade == null) {
            ImageEventRow(
                label = cmdLabel.first,
                value = cmdLabel.second,
                highlight = true,
            )
        } else if (cmdLabel == null && upgrade == null) {
            ImageEventRow(
                label = "最近命令",
                value = "暂无图像类指令",
                highlight = false,
            )
        }
    }
}

/** GB-2022 §9.13 在线升级进度条 — 平台 DeviceUpgrade 触发,5s 假进度 0/30/60/100. */
@Composable
private fun UpgradeProgressRow(upgrade: UpgradeProgressDto) {
    val statusText = when (upgrade.result) {
        UpgradeResultDto.InProgress -> "升级中"
        UpgradeResultDto.Success -> "升级成功"
        UpgradeResultDto.Failure -> "升级失败"
    }
    val statusColor = when (upgrade.result) {
        UpgradeResultDto.InProgress -> UvpColor.Primary
        UpgradeResultDto.Success -> UvpColor.Success
        UpgradeResultDto.Failure -> UvpColor.Danger
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(UvpColor.PrimaryLight)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "在线升级 v${upgrade.firmware}",
                fontSize = 11.sp,
                color = UvpColor.Primary,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            Text(
                statusText,
                fontSize = 10.sp,
                color = statusColor,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "${upgrade.percent}%",
                fontSize = 11.sp,
                color = UvpColor.Text,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
            )
        }
        Spacer(Modifier.height(5.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(99.dp))
                .background(UvpColor.BorderLight),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(upgrade.percent / 100f)
                    .height(4.dp)
                    .clip(RoundedCornerShape(99.dp))
                    .background(statusColor)
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
