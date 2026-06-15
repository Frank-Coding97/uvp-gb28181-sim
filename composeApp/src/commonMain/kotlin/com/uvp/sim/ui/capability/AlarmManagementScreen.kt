package com.uvp.sim.ui.capability

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uvp.sim.config.CatalogNodeType
import com.uvp.sim.gb28181.AlarmMethod
import com.uvp.sim.gb28181.AlarmNotify
import com.uvp.sim.gb28181.AlarmPayload
import com.uvp.sim.gb28181.AlarmPriority
import com.uvp.sim.gb28181.AlarmType
import com.uvp.sim.ui.AppActions
import com.uvp.sim.ui.AppUiState
import com.uvp.sim.ui.LocalToastHost
import com.uvp.sim.ui.SubscriptionKind
import com.uvp.sim.ui.UvpColor
import kotlinx.datetime.toLocalDateTime

/**
 * 报警子页(spec F)— 报警单详细编辑 + 预览 XML + 发送/复位 + 历史 + 订阅人。
 *
 * 进入方式:能力页报警卡点击 / 主屏报警 tile 长按(target)。
 */
@Composable
fun AlarmManagementScreen(state: AppUiState, actions: AppActions, onBack: () -> Unit) {
    val toast = LocalToastHost.current
    val scroll = rememberScrollState()
    val isAlarming = state.deviceControl.isAlarming

    // DeviceID 候选:catalogTree 里所有 AlarmChannel 节点 + 兜底 device.alarmChannelId
    val deviceIdCandidates = remember(state.catalogTree, state.config) {
        val fromTree = state.catalogTree.filter { it.type == CatalogNodeType.AlarmChannel }.map { it.id }
        (fromTree + state.config.device.alarmChannelId).distinct()
    }

    var deviceId by remember(state.config) { mutableStateOf(deviceIdCandidates.first()) }
    var priority by remember { mutableStateOf(AlarmPriority.General) }
    var method by remember { mutableStateOf(AlarmMethod.Device) }
    var type by remember { mutableStateOf(AlarmType.Other) }
    var typeParam by remember { mutableStateOf("") }
    var minutesAgo by remember { mutableStateOf(0) }
    var description by remember { mutableStateOf("详细报警单") }
    var longitude by remember(state.config) { mutableStateOf(state.config.mockPosition.longitude.toString()) }
    var latitude by remember(state.config) { mutableStateOf(state.config.mockPosition.latitude.toString()) }

    var showXmlPreview by remember { mutableStateOf(false) }
    var historyExpanded by remember { mutableStateOf(false) }
    var subscribersExpanded by remember { mutableStateOf(false) }

    fun buildDraft(): AlarmPayload = AlarmPayload(
        deviceId = deviceId,
        priority = priority,
        method = method,
        type = type,
        typeParam = typeParam.ifBlank { null },
        timeMs = if (minutesAgo > 0)
            kotlinx.datetime.Clock.System.now().toEpochMilliseconds() - minutesAgo * 60_000L
        else 0L,
        description = description,
        longitude = longitude.toDoubleOrNull(),
        latitude = latitude.toDoubleOrNull()
    )

    Column(
        modifier = Modifier.fillMaxSize().background(UvpColor.Bg)
    ) {
        // 工具栏
        Surface(color = UvpColor.Surface) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Outlined.ArrowBack, "返回", tint = UvpColor.Text, modifier = Modifier.size(20.dp))
                }
                Text("报警", color = UvpColor.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 4.dp))
                Spacer(Modifier.weight(1f))
                if (isAlarming) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(6.dp).clip(RoundedCornerShape(3.dp)).background(UvpColor.Warning))
                        Spacer(Modifier.width(4.dp))
                        Text("报警中", fontSize = 11.sp, color = UvpColor.Warning)
                    }
                }
            }
        }

        Column(
            modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(scroll).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // DeviceID 选择器
            FieldLabel("报警源通道 (DeviceID)")
            SegmentedPicker(
                options = deviceIdCandidates,
                selected = deviceId,
                labelOf = { it },
                onSelect = { deviceId = it }
            )

            FieldLabel("报警等级 (AlarmPriority)")
            SegmentedPicker(
                options = AlarmPriority.entries,
                selected = priority,
                labelOf = { it.label },
                onSelect = { priority = it }
            )

            FieldLabel("报警方式 (AlarmMethod)")
            SegmentedPicker(
                options = AlarmMethod.entries,
                selected = method,
                labelOf = { it.label },
                onSelect = { method = it }
            )

            FieldLabel("报警类型 (AlarmType)")
            SegmentedPicker(
                options = AlarmType.entries,
                selected = type,
                labelOf = { it.label },
                onSelect = { type = it }
            )

            FieldLabel("类型参数 (AlarmTypeParam,可空)")
            OutlinedTextField(
                value = typeParam,
                onValueChange = { typeParam = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("如:磁吸触发", fontSize = 12.sp) }
            )

            FieldLabel("报警时间")
            SegmentedPicker(
                options = listOf(0, 1, 5, 30),
                selected = minutesAgo,
                labelOf = { if (it == 0) "现在" else "${it}分钟前" },
                onSelect = { minutesAgo = it }
            )

            FieldLabel("报警描述 (AlarmDescription)")
            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
                maxLines = 3
            )

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f)) {
                    FieldLabel("经度")
                    OutlinedTextField(longitude, { longitude = it }, singleLine = true,
                        modifier = Modifier.fillMaxWidth())
                }
                Column(Modifier.weight(1f)) {
                    FieldLabel("纬度")
                    OutlinedTextField(latitude, { latitude = it }, singleLine = true,
                        modifier = Modifier.fillMaxWidth())
                }
            }

            // 底部按钮
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (isAlarming) {
                    Button(
                        onClick = { actions.onAlarmReset(); toast.info("已复位报警") },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = UvpColor.Primary)
                    ) { Text("复位报警") }
                    OutlinedButton(
                        onClick = { actions.onAlarmFire(buildDraft()); toast.info("已发送报警") },
                        modifier = Modifier.weight(1f)
                    ) { Text("再发一条") }
                } else {
                    Button(
                        onClick = { actions.onAlarmFire(buildDraft()); toast.info("已发送报警") },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = UvpColor.Warning)
                    ) { Text("发送报警") }
                    OutlinedButton(
                        onClick = { showXmlPreview = true },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Outlined.Code, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("预览 XML")
                    }
                }
            }

            // 历史折叠区
            CollapsibleHeader(
                icon = Icons.Outlined.History,
                title = "最近 10 条",
                count = state.alarmHistory.size,
                expanded = historyExpanded,
                onToggle = { historyExpanded = !historyExpanded }
            )
            if (historyExpanded) {
                if (state.alarmHistory.isEmpty()) {
                    Text("暂无记录", fontSize = 11.sp, color = UvpColor.TextHint,
                        modifier = Modifier.padding(start = 8.dp))
                } else {
                    state.alarmHistory.asReversed().take(10).forEach { rec ->
                        AlarmHistoryRow(
                            time = formatClock(rec.firedAtMs),
                            typeLabel = rec.payload.type.label,
                            desc = rec.payload.description.take(30),
                            subs = rec.notifiedSubscribers
                        )
                    }
                }
            }

            // 订阅人折叠区
            val alarmSub = state.subscriptions[SubscriptionKind.Alarm]
            CollapsibleHeader(
                icon = Icons.Outlined.History,
                title = "订阅人",
                count = if (alarmSub?.active == true) 1 else 0,
                expanded = subscribersExpanded,
                onToggle = { subscribersExpanded = !subscribersExpanded }
            )
            if (subscribersExpanded) {
                if (alarmSub?.active == true) {
                    Text(
                        "${alarmSub.subscriber ?: "—"} · 剩 ${alarmSub.remainingSeconds ?: 0}s · ${alarmSub.notifyCount} 推",
                        fontSize = 11.sp, color = UvpColor.TextSecondary,
                        fontFamily = FontFamily.Monospace, modifier = Modifier.padding(start = 8.dp)
                    )
                } else {
                    Text("暂无 Event:Alarm 订阅", fontSize = 11.sp, color = UvpColor.TextHint,
                        modifier = Modifier.padding(start = 8.dp))
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }

    if (showXmlPreview) {
        val xml = remember(deviceId, priority, method, type, typeParam, minutesAgo, description, longitude, latitude) {
            AlarmNotify.buildAlarm(state.config, "preview", buildDraft())
        }
        AlertDialog(
            onDismissRequest = { showXmlPreview = false },
            confirmButton = { TextButton(onClick = { showXmlPreview = false }) { Text("关闭") } },
            title = { Text("报警 XML 预览") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(xml, fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = UvpColor.Text)
                }
            }
        )
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(text, fontSize = 11.sp, color = UvpColor.TextSecondary, fontWeight = FontWeight.Medium)
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun <T> SegmentedPicker(
    options: List<T>,
    selected: T,
    labelOf: (T) -> String,
    onSelect: (T) -> Unit
) {
    androidx.compose.foundation.layout.FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        options.forEach { opt ->
            val sel = opt == selected
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (sel) UvpColor.PrimaryLight else Color.Transparent)
                    .border(1.dp, if (sel) UvpColor.Primary else UvpColor.Border, RoundedCornerShape(6.dp))
                    .clickable { onSelect(opt) }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text(
                    labelOf(opt),
                    fontSize = 12.sp,
                    fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (sel) UvpColor.Primary else UvpColor.TextSecondary
                )
            }
        }
    }
}

@Composable
private fun CollapsibleHeader(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    count: Int,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .clickable { onToggle() }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = UvpColor.TextSecondary, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text("$title ($count)", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = UvpColor.Text)
        Spacer(Modifier.weight(1f))
        Icon(
            if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
            null, tint = UvpColor.TextSecondary, modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun AlarmHistoryRow(time: String, typeLabel: String, desc: String, subs: Int) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(time, fontSize = 10.sp, color = UvpColor.TextHint, fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(64.dp))
        Spacer(Modifier.width(6.dp))
        Text(typeLabel, fontSize = 11.sp, color = UvpColor.Warning, fontWeight = FontWeight.Medium)
        Spacer(Modifier.width(6.dp))
        Text(desc, fontSize = 11.sp, color = UvpColor.TextSecondary, maxLines = 1,
            modifier = Modifier.weight(1f))
        if (subs > 0) {
            Text("${subs}推", fontSize = 10.sp, color = UvpColor.Primary, fontFamily = FontFamily.Monospace)
        }
    }
}

/** epoch ms → HH:mm:ss 本地时间。 */
private fun formatClock(epochMs: Long): String {
    val tz = kotlinx.datetime.TimeZone.currentSystemDefault()
    val ldt = kotlinx.datetime.Instant.fromEpochMilliseconds(epochMs)
        .toLocalDateTime(tz)
    fun p2(v: Int) = v.toString().padStart(2, '0')
    return "${p2(ldt.hour)}:${p2(ldt.minute)}:${p2(ldt.second)}"
}
