package com.uvp.sim.ui.capability

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uvp.sim.ui.AppActions
import com.uvp.sim.ui.AppUiState
import com.uvp.sim.ui.SubPageContainer
import com.uvp.sim.ui.SubscriptionKind
import com.uvp.sim.ui.UvpColor
import com.uvp.sim.ui.floatingBottomBarReservedBottom
import com.uvp.sim.ui.simulate.SimulateScreen

/**
 * 「能力」Tab 主屏 — 卡片化展示已实现的国标扩展能力。
 *
 * 当前卡片:
 *  - 目录管理(§9.3.1)
 *  - 模拟控制(§4)
 *  - 报警订阅(§9.4)
 */
@Composable
fun CapabilityScreen(
    state: AppUiState,
    actions: AppActions,
    openAlarmTarget: Boolean = false,
    onAlarmTargetConsumed: () -> Unit = {}
) {
    var showCatalog by remember { mutableStateOf(false) }
    var showRecording by remember { mutableStateOf(false) }
    var showAlarm by remember { mutableStateOf(false) }
    var showClockSync by remember { mutableStateOf(false) }
    var showRecordPlan by remember { mutableStateOf(false) }

    // 主屏长按报警 tile 携带 target → 自动打开报警子页
    androidx.compose.runtime.LaunchedEffect(openAlarmTarget) {
        if (openAlarmTarget) {
            showAlarm = true
            onAlarmTargetConsumed()
        }
    }

    // 子页统一走 SubPageContainer —— 自动隐藏 tab bar + iOS 左边缘 swipe-back 手势。
    if (showCatalog) {
        SubPageContainer(onBack = { showCatalog = false }) {
            CatalogManagementScreen(
                state = state,
                actions = actions,
                onBack = { showCatalog = false }
            )
        }
        return
    }
    if (showRecording) {
        SubPageContainer(onBack = { showRecording = false }) {
            RecordingSubScreen(state = state, actions = actions, onBack = { showRecording = false })
        }
        return
    }
    if (showAlarm) {
        SubPageContainer(onBack = { showAlarm = false }) {
            AlarmManagementScreen(state = state, actions = actions, onBack = { showAlarm = false })
        }
        return
    }
    if (showClockSync) {
        SubPageContainer(onBack = { showClockSync = false }) {
            ClockSyncScreen(state = state, actions = actions, onBack = { showClockSync = false })
        }
        return
    }
    if (showRecordPlan) {
        SubPageContainer(onBack = { showRecordPlan = false }) {
            VideoRecordPlanScreen(state = state, actions = actions, onBack = { showRecordPlan = false })
        }
        return
    }

    val catalogSub = state.subscriptions[SubscriptionKind.Catalog]
    val catalogActive = catalogSub?.active == true
    val alarmSub = state.subscriptions[SubscriptionKind.Alarm]
    val isAlarming = state.deviceControl.isAlarming
    val recordPlan = state.deviceControl.deviceConfig.videoRecordPlan
    // 报警录像段数（含"录像期间报警、事后打标记"的段）—— 现场最常问的是"报了警到底录没录"，
    // 这个数比"报警了几次"更能回答它。
    val alarmRecordingCount = state.recording.files.count {
        it.alarm != null || it.source == com.uvp.sim.ui.model.RecordSourceDto.Alarm
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(UvpColor.Bg)
            .padding(
                start = 14.dp,
                end = 14.dp,
                top = 10.dp,
                // 悬浮 tab bar 底部预留(iOS 130dp / 其他 0dp),让第二排 tile 不被遮
                bottom = 10.dp + floatingBottomBarReservedBottom
            ),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        val tiles = listOf(
            CapabilityTileSpec(
                icon = Icons.Outlined.AccountTree,
                title = "目录管理",
                metric = "${state.catalogTree.size} 节点",
                status = if (catalogActive) {
                    TileStatus.Active(
                        text = buildString {
                            append("已订阅")
                            catalogSub?.remainingSeconds?.let { append(" · 剩 ${it}s") }
                        }
                    )
                } else {
                    TileStatus.Idle("未订阅")
                },
                onClick = { showCatalog = true },
            ),
            CapabilityTileSpec(
                icon = Icons.Outlined.Movie,
                title = "录像列表",
                metric = "${state.recording.files.size} 文件",
                status = if (state.recording.isRecording) {
                    TileStatus.Active("录像中")
                } else {
                    TileStatus.Idle("待机")
                },
                onClick = { showRecording = true },
            ),
            CapabilityTileSpec(
                icon = Icons.Outlined.NotificationsActive,
                title = "报警",
                metric = if (alarmRecordingCount > 0) {
                    "${state.alarmHistory.size} 次 · $alarmRecordingCount 段录像"
                } else {
                    "${state.alarmHistory.size} 次"
                },
                status = when {
                    isAlarming -> TileStatus.Warning("报警中")
                    alarmSub?.active == true -> TileStatus.Active(
                        buildString {
                            append("已订阅")
                            alarmSub.remainingSeconds?.let { append(" · ${it}s") }
                            append(" · ${alarmSub.notifyCount} 推")
                        }
                    )
                    else -> TileStatus.Idle("未订阅 · ${state.alarmHistory.size} 次")
                },
                onClick = { showAlarm = true },
            ),
            CapabilityTileSpec(
                icon = Icons.Outlined.Schedule,
                title = "设备校时",
                metric = if (state.clockOffset.isSynced) {
                    formatOffsetMs(state.clockOffset.localOffsetMs() ?: 0L)
                } else "未校时",
                status = if (state.clockOffset.isSynced) {
                    TileStatus.Active("已校时")
                } else {
                    TileStatus.Idle("等注册")
                },
                onClick = { showClockSync = true },
            ),
            CapabilityTileSpec(
                icon = Icons.Outlined.CalendarMonth,
                title = "录像计划",
                metric = if (recordPlan.configured) {
                    "${recordPlan.dayCount} 天 · ${recordPlan.segmentCount} 段"
                } else "未配置",
                status = when {
                    // 计划正在驱动录像 —— 这条比"已启用"更值得先说：现场一眼要能确认
                    // 「计划真的在录」，而不是「配置是启用的」。
                    state.recording.isRecording &&
                        state.recording.source == com.uvp.sim.ui.model.RecordSourceDto.Schedule ->
                        TileStatus.Active("计划录像中")
                    recordPlan.configured && recordPlan.enabled -> TileStatus.Active(
                        if (recordPlan.streamNumber == 0) "已启用 · 主码流"
                        else "已启用 · 子码流${recordPlan.streamNumber}"
                    )
                    // ⛔「配过但关着」与「从没配过」必须分开说：两者的 enabled 都是 false，
                    // 但一条是有人刚关掉、另一条是设备出厂就这样（同 `PictureMaskViewDto.configured`）。
                    recordPlan.configured -> TileStatus.Idle("已配置 · 未启用")
                    else -> TileStatus.Idle("出厂默认")
                },
                onClick = { showRecordPlan = true },
            ),
        )
        tiles.chunked(TILE_COLUMNS).forEach { rowTiles ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                rowTiles.forEach { spec ->
                    CapabilityTile(
                        icon = spec.icon,
                        title = spec.title,
                        metric = spec.metric,
                        status = spec.status,
                        onClick = spec.onClick,
                        modifier = Modifier.weight(1f)
                    )
                }
                // ⛔ 末行不满时补等宽空白，而不是让最后一张 `weight(1f)` 拉满整行：
                // 一张卡横跨两列宽，视觉上会读成「重点项 / 横幅」，与它和其它卡平级的语
                // 义不符，且卡片一多一少整页网格就变形。
                repeat(TILE_COLUMNS - rowTiles.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

/** 能力页网格列数。奇数张卡片时末行留白，不允许拉伸。 */
private const val TILE_COLUMNS = 2

private data class CapabilityTileSpec(
    val icon: ImageVector,
    val title: String,
    val metric: String,
    val status: TileStatus,
    val onClick: () -> Unit,
)

private sealed interface TileStatus {
    val text: String
    data class Active(override val text: String) : TileStatus
    data class Idle(override val text: String) : TileStatus
    data class Warning(override val text: String) : TileStatus
}

@Composable
private fun CapabilityTile(
    icon: ImageVector,
    title: String,
    metric: String,
    status: TileStatus,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    val enabled = onClick != null
    val contentAlpha = if (enabled) 1f else 0.55f

    val (dotColor, statusTextColor) = when (status) {
        is TileStatus.Active -> UvpColor.Success to UvpColor.Success
        is TileStatus.Idle -> UvpColor.TextHint to UvpColor.TextSecondary
        is TileStatus.Warning -> UvpColor.Warning to UvpColor.Warning
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .let { if (enabled) it.clickable(onClick = onClick!!) else it },
        color = UvpColor.Surface
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        UvpColor.Primary.copy(alpha = if (enabled) 0.12f else 0.06f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = UvpColor.Primary.copy(alpha = contentAlpha),
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                title,
                color = UvpColor.Text.copy(alpha = contentAlpha),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(10.dp))
            Text(
                metric,
                color = UvpColor.Text.copy(alpha = contentAlpha * 0.85f),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(dotColor)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    status.text,
                    color = statusTextColor,
                    fontSize = 10.sp
                )
            }
        }
    }
}

@Composable
private fun RecordingSubScreen(state: AppUiState, actions: AppActions, onBack: () -> Unit) {
    com.uvp.sim.ui.PlatformBackHandler(enabled = true, onBack = onBack)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(UvpColor.Bg)
    ) {
        CapabilitySubPageHeader(title = "录像列表", onBack = onBack)
        com.uvp.sim.ui.RecordingScreen(state = state, actions = actions, modifier = Modifier.weight(1f))
    }
}
