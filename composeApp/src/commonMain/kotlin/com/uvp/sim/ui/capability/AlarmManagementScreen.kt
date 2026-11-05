package com.uvp.sim.ui.capability

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uvp.sim.config.CatalogNodeType
import com.uvp.sim.gb28181.AlarmMethod
import com.uvp.sim.gb28181.AlarmNotify
import com.uvp.sim.gb28181.AlarmPayload
import com.uvp.sim.gb28181.AlarmPriority
import com.uvp.sim.gb28181.AlarmReportKind
import com.uvp.sim.gb28181.AlarmTemplates
import com.uvp.sim.gb28181.AlarmType
import com.uvp.sim.ui.AlarmFireMode
import com.uvp.sim.ui.AppActions
import com.uvp.sim.ui.AppUiState
import com.uvp.sim.ui.LocalToastHost
import com.uvp.sim.ui.SubscriptionKind
import com.uvp.sim.ui.UvpColor

/**
 * 报警子页(spec F)— 报警单详细编辑 + 预览 XML + 发送/复位 + 历史 + 订阅人。
 *
 * 进入方式:能力页报警卡点击 / 主屏报警 tile 长按(target)。
 *
 * 拆分:小部件在 [AlarmManagementWidgets],高级模拟在 [AlarmAdvancedSimulationSection]。
 */
@Composable
fun AlarmManagementScreen(state: AppUiState, actions: AppActions, onBack: () -> Unit) {
    com.uvp.sim.ui.PlatformBackHandler(enabled = true, onBack = onBack)
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
    var advancedExpanded by remember { mutableStateOf(false) }
    var alarmRecordExpanded by remember { mutableStateOf(false) }
    var alarmReportExpanded by remember { mutableStateOf(false) }
    // 固定报警的「上报类别」：决定这条报警受不受 A.2.1.18 开关约束。
    // null = 通用报警（人工触发/器件故障…），开关管不着。
    var reportKind by remember { mutableStateOf<AlarmReportKind?>(null) }

    fun buildDraft(): AlarmPayload = AlarmPayload(
        deviceId = deviceId,
        priority = priority,
        method = method,
        type = type,
        typeParam = typeParam.ifBlank { null },
        // 门控标签只给设备侧看，不进 XML —— 报文里区分靠 typeParam。
        reportKind = reportKind,
        timeMs = if (minutesAgo > 0)
            kotlin.time.Clock.System.now().toEpochMilliseconds() - minutesAgo * 60_000L
        else 0L,
        description = description,
        longitude = longitude.toDoubleOrNull(),
        latitude = latitude.toDoubleOrNull()
    )

    Column(
        modifier = Modifier.fillMaxSize().background(UvpColor.Bg)
    ) {
        // 工具栏
        CapabilitySubPageHeader(title = "报警", onBack = onBack) {
            if (isAlarming) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(6.dp).clip(RoundedCornerShape(3.dp)).background(UvpColor.Warning))
                    Spacer(Modifier.width(4.dp))
                    Text("报警中", fontSize = 11.sp, color = UvpColor.Warning)
                }
            }
        }

        Column(
            modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(scroll).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 发送模式(spec G2):随机模板池 / 指定固定单
            FieldLabel("发送模式")
            SegmentedPicker(
                options = listOf(AlarmFireMode.Random, AlarmFireMode.Fixed),
                selected = state.alarmFireMode,
                labelOf = { if (it == AlarmFireMode.Random) "随机" else "指定" },
                onSelect = { actions.onSetAlarmFireMode(it) }
            )

            if (state.alarmFireMode == AlarmFireMode.Random) {
                Text(
                    "随机模式:每次从下列 ${AlarmTemplates.builtin.size} 条预置模板中抽一条发送。主页一点即发。",
                    fontSize = 11.sp, color = UvpColor.TextSecondary
                )
                // ⚠️ 被上报开关关掉的类别要**明确标出来**：随机池已经把它们排除了
                // （见 SipViewModel.fireAlarmDefault），不标的话用户会以为"模板少了"。
                val reportCfgView = state.deviceControl.deviceConfig.alarmReport
                AlarmTemplates.builtin.forEach { tpl ->
                    val suppressed = when (tpl.reportKind) {
                        AlarmReportKind.MotionDetection -> !reportCfgView.motionDetection
                        AlarmReportKind.FieldDetection -> !reportCfgView.fieldDetection
                        null -> false
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier.size(5.dp).clip(RoundedCornerShape(3.dp))
                                .background(if (suppressed) UvpColor.TextHint else UvpColor.Warning)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("${tpl.type.label} · ${tpl.priority.label}", fontSize = 11.sp,
                            color = if (suppressed) UvpColor.TextHint else UvpColor.Text,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.width(120.dp))
                        Text(
                            if (suppressed) "${tpl.description}（已关闭上报）" else tpl.description,
                            fontSize = 11.sp,
                            color = if (suppressed) UvpColor.TextHint else UvpColor.TextSecondary,
                            maxLines = 1
                        )
                    }
                }
                Button(
                    onClick = { actions.onAlarmFireDefault(); toast.info("已随机发送报警") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = UvpColor.Warning)
                ) { Text("随机发一条") }

                // 随机模式下历史折叠区也展示
                AlarmHistorySection(state, historyExpanded) { historyExpanded = !historyExpanded }

                // 报警录像配置两种发送模式共用 —— 它描述的是"报警发生时设备怎么录"，
                // 与这次发哪一条报警单无关。
                AlarmVideoRecordSection(
                    view = state.deviceControl.deviceConfig.videoAlarmRecord,
                    expanded = alarmRecordExpanded,
                    onToggle = { alarmRecordExpanded = !alarmRecordExpanded },
                    onSave = { actions.onVideoAlarmRecordSave(it) },
                )

                // A.2.1.18 上报开关同样两种模式共用：它决定"哪类报警根本发不出去"。
                AlarmReportSection(
                    view = state.deviceControl.deviceConfig.alarmReport,
                    expanded = alarmReportExpanded,
                    onToggle = { alarmReportExpanded = !alarmReportExpanded },
                    onSave = { actions.onAlarmReportSave(it) },
                )
                return@Column
            }

            // ===== 指定模式:9 字段编辑表单 =====
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

            // 上报类别 —— 决定这条报警算不算"移动侦测 / 区域入侵"事件。
            // 选中的类别若在 A.2.1.18 里被关掉，设备会直接拦下（一条报文都不发），
            // 所以这里必须**提前**说清楚，不能等点了发送才发现没反应。
            FieldLabel("上报类别 (A.2.1.18 门控)")
            SegmentedPicker(
                options = listOf<AlarmReportKind?>(null, AlarmReportKind.MotionDetection, AlarmReportKind.FieldDetection),
                selected = reportKind,
                labelOf = { it?.label ?: "通用" },
                onSelect = { reportKind = it }
            )
            val reportView = state.deviceControl.deviceConfig.alarmReport
            val blocked = when (reportKind) {
                AlarmReportKind.MotionDetection -> !reportView.motionDetection
                AlarmReportKind.FieldDetection -> !reportView.fieldDetection
                null -> false
            }
            if (blocked) {
                Text(
                    "⚠️ ${reportKind?.label}上报已关闭 —— 这条报警会被设备拦下，平台收不到任何报文。" +
                        "先到下方「上报开关」里打开。",
                    fontSize = 10.sp,
                    color = UvpColor.Warning,
                    lineHeight = 14.sp,
                )
            } else {
                Text(
                    if (reportKind == null) "通用报警，不受移动侦测/区域入侵开关约束。"
                    else "受对应开关约束：关闭时设备不发。",
                    fontSize = 10.sp, color = UvpColor.TextHint, lineHeight = 14.sp,
                )
            }

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
            // 指定模式:保存为固定单(主页一点即发此单)
            if (!isAlarming) {
                OutlinedButton(
                    onClick = { actions.onSaveFixedAlarm(buildDraft()); toast.info("已保存固定单,主页一点即发") },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("保存为固定单") }
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
                            time = formatAlarmClock(rec.firedAtMs),
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

            // 报警录像配置区（A.2.1.16）—— 放在订阅人之后，与"发一条报警"的主流程分开，
            // 避免用户把配置项误当成发送表单的一部分。
            AlarmVideoRecordSection(
                view = state.deviceControl.deviceConfig.videoAlarmRecord,
                expanded = alarmRecordExpanded,
                onToggle = { alarmRecordExpanded = !alarmRecordExpanded },
                onSave = { actions.onVideoAlarmRecordSave(it) },
            )

            AlarmReportSection(
                view = state.deviceControl.deviceConfig.alarmReport,
                expanded = alarmReportExpanded,
                onToggle = { alarmReportExpanded = !alarmReportExpanded },
                onSave = { actions.onAlarmReportSave(it) },
            )

            // 高级模拟折叠区 — MediaStatus 122/123 演示
            AlarmAdvancedSimulationSection(
                sipState = state.sip,
                actions = actions,
                expanded = advancedExpanded,
                onToggle = { advancedExpanded = !advancedExpanded }
            )

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
