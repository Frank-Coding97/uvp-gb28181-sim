package com.uvp.sim.ui.simulate.ptz

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.outlined.CenterFocusStrong
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uvp.sim.ui.UvpColor
import com.uvp.sim.ui.model.DeviceConfigRowDto
import com.uvp.sim.ui.model.DeviceControlDto
import com.uvp.sim.ui.model.LastDeviceCommandDto
import com.uvp.sim.ui.model.UpgradeProgressDto
import com.uvp.sim.ui.model.UpgradeResultDto
import com.uvp.sim.ui.simulate.useTickingNow

internal const val KEY_FRAME_FEEDBACK_MS = 2_000L

internal fun isKeyFrameRequestActive(command: LastDeviceCommandDto?, nowMs: Long): Boolean {
    if (command?.type != "IFameCmd" && command?.type != "IFrameCmd") return false
    return nowMs - command.timestampMs in 0..KEY_FRAME_FEEDBACK_MS
}

/**
 * 图像 Tab — 一次性事件 / 配置变更展示.
 * GB-2022 §9.13 在线升级 / 拉框聚焦 / 抓拍 / 强制 I 帧 / 设备配置 等.
 */
@Composable
internal fun ImageTabContent(state: DeviceControlDto) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val nowMs = useTickingNow(intervalMs = 250L)
        val keyFrameCommand = state.lastCommand?.type == "IFameCmd" || state.lastCommand?.type == "IFrameCmd"
        KeyFrameRequestIndicator(
            active = isKeyFrameRequestActive(state.lastCommand, nowMs),
        )

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
        } else if (cmdLabel == null && upgrade == null && !keyFrameCommand) {
            ImageEventRow(
                label = "最近命令",
                value = "暂无图像类指令",
                highlight = false,
            )
        }

        // GB-2022 A.2.3.2 设备配置族（视频参数属性 / 画面遮挡 / 翻转 / 前端 OSD / 录像计划 /
        // 报警录像 / 报警上报 / 基本参数 / 图像抓拍）—— 常驻项恒显，其余只列**平台真的下发过**的。
        DeviceConfigSection(rows = state.deviceConfig.rows)
    }
}

/**
 * HUD 内容区能容纳的**普通**配置行上限（不含常驻行，见 [DeviceConfigSection]）。
 *
 * ⛔ 这是个**硬预算**，不是"差不多就行"：`PtzHudPanel` 的内容区是**定高 284dp、不滚动**的 Box，
 * 超出的行会被直接裁掉，而且裁掉的往往是最后一行（也就是刚下发的那一项）。
 *
 * 现算一遍（改字号 / 加行前重算）：
 * ```
 *   关键帧指示器   ≈ 70dp   （标题 24sp 行框 + 副标题 24sp 行框 + 上下内边距 22）
 *   拉框聚焦行     ≈ 40dp
 *   最近命令行     ≈ 40dp
 *   组间距 2×6     = 12dp
 *   本区块标题行   ≈ 20dp
 *   ──────────────────────
 *   小计 ≈ 182dp  →  余 284 − 182 = 102dp  →  每行约 46dp（含 6dp 间距）→ **区块总共最多 2 行**
 * ```
 * ⚠️ 上面算出的 2 是**区块总行数**（常驻行 + 普通行），不是普通行自己的额度。
 * 「视频参数属性」是常驻行且排第一（见 [DeviceConfigRowDto.alwaysVisible]），
 * 所以普通项只剩 1 个名额 —— **2026-09-19 把它从 2 降到 1，就是为了换出常驻行而总高度不变**。
 * ⛔ 再加行之前先重算上面那段：改字号、加行、加区块都会动这个预算。
 * 多出来的用一行「另有 N 项」报余数 —— **绝不把集合全铺出来**
 * （同"巡航轨迹条数由协议决定、必须限量+报余数"那一条）。
 * 想看全部 9 项请用平台侧面板；设备屏幕这边只保证"常驻项 + 最新下发的看得见"。
 *
 * ⚠️ `internal`（不是 `private`）：[selectConfigRows] 的单测要按它算"最坏情况下总行数"，
 * 抄一个 `2` 进测试就等于把预算写两份 —— 改成 3 的那天测试还是绿的。
 */
internal const val MAX_VISIBLE_CONFIG_ROWS = 1

/**
 * [selectConfigRows] 的结果。
 *
 * [pinned] = 常驻行（`alwaysVisible`，全部展示）；[visible] = 平台配过的项里**名额内**的那些；
 * [hiddenCount] = 被名额挡掉、由「另有 N 项未展示」这一行代表的数量；
 * [configuredCount] = 平台配过的项总数（**不含常驻行**），用于区块标题的统计。
 */
internal data class ConfigRowSelection(
    val pinned: List<DeviceConfigRowDto>,
    val visible: List<DeviceConfigRowDto>,
    val hiddenCount: Int,
    val configuredCount: Int,
)

/**
 * 从摘要行里选出**实际要画出来的**那几行。
 *
 * ⛔ 抽成纯函数就是为了让"常驻行(≤1) + 普通行(≤[MAX_VISIBLE_CONFIG_ROWS]) ≤ 2"
 * 这条高度预算能被单测**钉住** —— 之前它只活在这段 Composable 的分支里，
 * 想验证只能靠跑起 UI 用眼睛看有没有被裁掉，而裁掉的恰恰是最后一行（刚下发的那项）。
 *
 * [hiddenCount] 用 `coerceAtLeast(0)`：名额 >= 已配项数时**必须**是 0，
 * 否则会显示"另有 -1 项未展示"。
 */
internal fun selectConfigRows(rows: List<DeviceConfigRowDto>): ConfigRowSelection {
    val pinned = rows.filter { it.alwaysVisible }
    val configured = rows.filter { !it.alwaysVisible && it.fromPlatform }
    return ConfigRowSelection(
        pinned = pinned,
        visible = configured.take(MAX_VISIBLE_CONFIG_ROWS),
        hiddenCount = (configured.size - MAX_VISIBLE_CONFIG_ROWS).coerceAtLeast(0),
        configuredCount = configured.size,
    )
}

/**
 * 「设备配置族 / 平台下发配置」只读区块。
 *
 * 行数**不随已配项数增长**：常驻一行覆盖统计（`设备配置族 9 项 · 平台已配 N 项`），
 * 下面先铺**常驻行**（`alwaysVisible`，即「视频参数属性」—— 它永远有值），
 * 再铺平台配过的项（≤ [MAX_VISIBLE_CONFIG_ROWS]，超出报余数）。
 * 平台一项都没配时也至少看得到统计行 + 常驻行，明确告诉操作员"设备现在基本是出厂默认"。
 *
 * ⛔ 常驻行**不受** [MAX_VISIBLE_CONFIG_ROWS] 限制，但因此更不能多加：
 * 常驻行(≤1) + 普通行(≤[MAX_VISIBLE_CONFIG_ROWS]) 必须 ≤ 2 —— 见该常量的高度预算。
 * 选取规则全在 [selectConfigRows]（纯函数、有单测），这里只负责画。
 */
@Composable
private fun DeviceConfigSection(rows: List<DeviceConfigRowDto>) {
    if (rows.isEmpty()) return
    val selection = selectConfigRows(rows)
    Text(
        "设备配置族 ${rows.size} 项 · 平台已配 ${selection.configuredCount} 项",
        fontSize = 10.sp,
        // ⛔ 显式给 lineHeight：UvpTheme 没覆写 typography，不给就按 Material3 的 24.sp 记账。
        lineHeight = 13.sp,
        color = UvpColor.TextHint,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        softWrap = false,
    )
    for (row in selection.pinned) {
        ImageEventRow(label = row.label, value = row.value, highlight = true)
    }
    for (row in selection.visible) {
        ImageEventRow(label = row.label, value = row.value, highlight = true)
    }
    if (selection.hiddenCount > 0) {
        Text(
            "另有 ${selection.hiddenCount} 项未展示",
            fontSize = 10.sp,
            lineHeight = 13.sp,
            color = UvpColor.TextHint,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
private fun KeyFrameRequestIndicator(active: Boolean) {
    val background = if (active) UvpColor.Primary else UvpColor.Bg
    val foreground = if (active) Color.White else UvpColor.TextSecondary
    val border = if (active) UvpColor.Primary else UvpColor.BorderLight

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(background)
            .border(1.dp, border, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 11.dp),
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
                color = foreground,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                if (active) "已收到平台请求" else "等待平台请求",
                fontSize = 9.sp,
                color = if (active) Color.White.copy(alpha = 0.82f) else UvpColor.TextHint,
                fontWeight = FontWeight.Medium,
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
