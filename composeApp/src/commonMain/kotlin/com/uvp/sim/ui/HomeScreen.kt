package com.uvp.sim.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.animation.core.animateFloat
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uvp.sim.config.AudioTransportType
import com.uvp.sim.gb28181.AlarmPayload
import com.uvp.sim.ui.model.SipStateDto

/**
 * 主屏 Home — 产品经理视角重构。
 *
 * 核心原则:用户在这一屏完成所有操作,不跳转。
 * - 状态 banner (失败时直接展示原因)
 * - 视频预览
 * - SIP 配置摘要 (内联编辑,7 字段)
 * - 抓拍按钮 (仅 M1 可用的主动业务,不展示 disabled 的)
 * - 注册/注销
 * - 底部"高级设置"折叠(通道ID/用户名/密码/注册参数)
 */
@Composable
fun HomeScreen(state: AppUiState, actions: AppActions) {
    val scroll = rememberScrollState()
    val toast = LocalToastHost.current
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(scroll).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        StatusBanner(state)
        BroadcastIndicator(state, actions)
        CameraPreviewBox(state)
        SipConfigCard(state, actions, onFeedback = { msg ->
            toast.success(msg)
        })
        ActionButtons(state, actions, onFeedback = { msg ->
            toast.success(msg)
        })
        ConnectButton(state, actions, onFeedback = { msg ->
            toast.info(msg)
        })
    }
}

// ============= Status banner =============

@Composable
private fun StatusBanner(state: AppUiState) {
    val spec = when (state.sip) {
        SipStateDto.Registered, SipStateDto.InCall -> BannerSpec(
            UvpColor.SuccessBg, UvpColor.SuccessBorder, UvpColor.Success,
            "设备已注册", UvpColor.SuccessText,
            "心跳 ${state.config.keepaliveIntervalSeconds}s"
        )
        SipStateDto.Registering -> BannerSpec(
            UvpColor.WarningBg, UvpColor.WarningBorder, UvpColor.Warning,
            "正在注册…", UvpColor.Warning, "等待平台响应"
        )
        SipStateDto.Disconnected -> BannerSpec(
            UvpColor.BorderLight, UvpColor.Border, UvpColor.TextHint,
            "未连接", UvpColor.TextSecondary,
            if (state.config.isReadyToRegister) "编辑配置 → 注册"
            else "请先填写 SIP 配置"
        )
        SipStateDto.Failed -> {
            val reason = state.events.filterIsInstance<com.uvp.sim.domain.SimEvent.RegistrationFailed>()
                .firstOrNull()?.reason ?: "未知原因"
            BannerSpec(
                UvpColor.DangerBg, UvpColor.DangerBorder, UvpColor.Danger,
                "注册失败", UvpColor.DangerText, reason
            )
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(spec.bg, RoundedCornerShape(8.dp))
            .border(1.dp, spec.border, RoundedCornerShape(8.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(spec.dot))
        Spacer(Modifier.width(10.dp))
        Text(spec.text, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = spec.textColor)
        Spacer(Modifier.weight(1f))
        // 推流状态(原视频框右上角 chip 上移到此):仅注册后显示
        val streamLabel = when (state.sip) {
            SipStateDto.InCall -> "1280×720 · 25fps · LIVE"
            SipStateDto.Registered -> "1280×720 · 预览"
            else -> null
        }
        if (streamLabel != null) {
            Text(streamLabel, fontSize = 11.sp, color = UvpColor.TextHint,
                fontFamily = FontFamily.Monospace, maxLines = 1)
            Spacer(Modifier.width(10.dp))
        }
        Text(spec.extra, fontSize = 11.sp, color = UvpColor.TextHint, fontFamily = FontFamily.Monospace,
            maxLines = 1)
    }
}

private data class BannerSpec(
    val bg: Color, val border: Color, val dot: Color,
    val text: String, val textColor: Color, val extra: String
)

// ============= M3 语音广播下行指示 =============

@Composable
private fun BroadcastIndicator(state: AppUiState, actions: AppActions) {
    val bc = state.broadcast
    if (!bc.isReceiving) return
    var showSheet by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0x1AFF9800), RoundedCornerShape(8.dp))
            .border(1.dp, Color(0xFFFF9800), RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("📢", fontSize = 14.sp)
        Spacer(Modifier.width(8.dp))
        Text(
            "对讲中 ← ${bc.sourceId?.takeLast(4) ?: "----"}",
            fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color(0xFFE65100),
            modifier = Modifier.weight(1f).clickable { showSheet = true }
        )
        Text(
            "${bc.codec ?: "PCMA"} · ${bc.rxPackets}pkt",
            fontSize = 11.sp, color = UvpColor.TextHint, fontFamily = FontFamily.Monospace, maxLines = 1
        )
        Spacer(Modifier.width(12.dp))
        Text(
            if (bc.speakerOn) "🔊" else "🔇", fontSize = 15.sp,
            modifier = Modifier.clickable { actions.onBroadcastToggleSpeaker(!bc.speakerOn) }
        )
        Spacer(Modifier.width(12.dp))
        Text(
            "✕", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFFE65100),
            modifier = Modifier.clickable { actions.onBroadcastStop() }
        )
    }
    if (showSheet) {
        BroadcastStatsSheet(bc) { showSheet = false }
    }
}

@Composable
private fun BroadcastStatsSheet(bc: BroadcastState, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
        title = { Text("语音对讲") },
        text = {
            Column {
                Text("来源: ${bc.sourceId ?: "-"}", fontSize = 12.sp)
                Text("编码: ${bc.codec ?: "-"}", fontSize = 12.sp)
                Text("本地端口: ${bc.localAudioPort}", fontSize = 12.sp)
                Text("远端: ${bc.remoteAudioHost ?: "-"}:${bc.remoteAudioPort}", fontSize = 12.sp)
                Text("接收包数: ${bc.rxPackets}", fontSize = 12.sp)
                Text("接收字节: ${bc.rxBytes}", fontSize = 12.sp)
                Text("丢包(seq): ${bc.seqLost}", fontSize = 12.sp)
                Text("解码错误: ${bc.decodeErrors}", fontSize = 12.sp)
            }
        }
    )
}

// ============= Camera preview =============

@Composable
private fun CameraPreviewBox(state: AppUiState) {
    val showPreview = state.sip == SipStateDto.Registered || state.sip == SipStateDto.InCall
    val isRecording = state.recording.isRecording
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (showPreview) {
                    Brush.linearGradient(listOf(Color(0xFF1F2937), Color(0xFF1F2937)))
                } else {
                    Brush.linearGradient(
                        colors = listOf(Color(0xFF0B1E3F), Color(0xFF0F2A57), Color(0xFF1A4480)),
                        start = Offset(0f, 0f),
                        end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
                    )
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        if (showPreview) {
            PlatformCameraPreview(modifier = Modifier.fillMaxSize())
        } else {
            BrandCover()
        }
        // 录像红点(左上角):脉动闪烁 + REC + 计时器
        if (isRecording) {
            RecordingBadge(
                startMs = state.recording.startMs,
                source = state.recording.source,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
            )
        }
        // 注:分辨率/推流状态 chip 已上移到 StatusBanner,视频框顶部留给烧进流的 OSD
    }
}

/**
 * 录像标徽 — 脉动红点 + REC + 计时器(mm:ss)。
 *
 * 放在视频预览区左上角,不挡现有 LIVE 标(右上角)。脉动用 infiniteTransition
 * 让 alpha 在 1.0 ↔ 0.35 之间 800ms 周期循环,跟相机录像机的视觉惯例一致。
 */
@Composable
private fun RecordingBadge(
    startMs: Long?,
    source: com.uvp.sim.ui.model.RecordSourceDto?,
    modifier: Modifier = Modifier
) {
    val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "rec-pulse")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.35f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            animation = androidx.compose.animation.core.tween(durationMillis = 800),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
        ),
        label = "rec-alpha"
    )
    // 计时器:每秒重组一次,显示从 startMs 到现在的 mm:ss
    var elapsedSec by remember { mutableStateOf(0L) }
    LaunchedEffect(startMs) {
        if (startMs == null) return@LaunchedEffect
        while (true) {
            elapsedSec = ((kotlinx.datetime.Clock.System.now().toEpochMilliseconds() - startMs) / 1000)
                .coerceAtLeast(0)
            kotlinx.coroutines.delay(1000)
        }
    }
    val mm = (elapsedSec / 60).toString().padStart(2, '0')
    val ss = (elapsedSec % 60).toString().padStart(2, '0')
    val sourceLabel = when (source) {
        com.uvp.sim.ui.model.RecordSourceDto.PlatformCmd -> "REC·平台"
        else -> "REC"
    }
    Row(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(UvpColor.Danger.copy(alpha = alpha))
        )
        Spacer(Modifier.width(5.dp))
        Text(
            "$sourceLabel  $mm:$ss",
            fontSize = 10.sp,
            color = Color.White,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun BrandCover() {
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // GB/T 28181 chip
            Box(
                modifier = Modifier
                    .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(10.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 10.dp, vertical = 3.dp)
            ) {
                Text(
                    "GB/T 28181-2022",
                    fontSize = 9.sp,
                    color = Color.White.copy(alpha = 0.7f),
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.sp
                )
            }
            Spacer(Modifier.height(10.dp))
            // UVP 渐变大字
            Text(
                "UVP",
                fontSize = 60.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 10.sp,
                style = androidx.compose.ui.text.TextStyle(
                    brush = Brush.linearGradient(
                        listOf(Color(0xFFFFFFFF), Color(0xFF7CC4FF))
                    )
                )
            )
        }
        Text(
            "注册后开启预览",
            fontSize = 10.sp,
            color = Color.White.copy(alpha = 0.4f),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp)
        )
    }
}

// ============= SIP config card (inline edit) =============

@Composable
private fun SipConfigCard(state: AppUiState, actions: AppActions, onFeedback: (String) -> Unit) {
    val toast = LocalToastHost.current
    var editing by remember { mutableStateOf(false) }
    var ip by remember(state.config) { mutableStateOf(state.config.server.ip) }
    var port by remember(state.config) {
        // port == 0 当"未填"哨兵(默认值),UI 渲染为空串让 placeholder 露出来。
        mutableStateOf(if (state.config.server.port == 0) "" else state.config.server.port.toString())
    }
    var deviceId by remember(state.config) { mutableStateOf(state.config.device.deviceId) }
    var password by remember(state.config) { mutableStateOf(state.config.device.password) }
    var transport by remember(state.config) { mutableStateOf(state.config.transport.name) }
    var audioTransport by remember(state.config) {
        mutableStateOf(state.config.audioTransport)
    }
    var serverId by remember(state.config) { mutableStateOf(state.config.server.serverId) }
    var domain by remember(state.config) { mutableStateOf(state.config.server.domain) }
    var domainManuallyEdited by remember(state.config) { mutableStateOf(false) }

    val locked = state.sip == SipStateDto.Registered || state.sip == SipStateDto.InCall
    if (locked && editing) editing = false

    fun resetFromConfig() {
        ip = state.config.server.ip
        port = if (state.config.server.port == 0) "" else state.config.server.port.toString()
        deviceId = state.config.device.deviceId
        password = state.config.device.password
        transport = state.config.transport.name
        audioTransport = state.config.audioTransport
        serverId = state.config.server.serverId
        domain = state.config.server.domain
        domainManuallyEdited = false
    }

    fun save(): Boolean {
        if (ip.isBlank() || deviceId.isBlank() || serverId.isBlank()) {
            toast.error("服务器、设备ID、服务器ID 不能为空")
            return false
        }
        actions.onConfigSave(
            state.config.copy(
                server = state.config.server.copy(
                    ip = ip,
                    port = port.toIntOrNull() ?: 5060,
                    serverId = serverId,
                    domain = domain
                ),
                device = state.config.device.copy(
                    deviceId = deviceId,
                    username = deviceId,
                    password = password
                ),
                transport = com.uvp.sim.network.TransportType.valueOf(transport),
                audioTransport = audioTransport
            )
        )
        domainManuallyEdited = false
        return true
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(UvpColor.Surface, RoundedCornerShape(8.dp))
            .border(1.dp, UvpColor.Border, RoundedCornerShape(8.dp))
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("SIP 配置", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = UvpColor.TextHint)
            Spacer(Modifier.weight(1f))
            val tint = if (locked) UvpColor.TextHint else UvpColor.Primary
            if (editing && !locked) {
                // 编辑态:取消(还原) + 完成(校验后保存) 两按钮并排,
                // 表单不合法时"完成"toast 报错,用户可点"取消"无校验退出。
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable {
                            resetFromConfig()
                            editing = false
                        }
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "取消",
                        fontSize = 12.sp,
                        color = UvpColor.TextSecondary
                    )
                }
                Spacer(Modifier.width(4.dp))
            }
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .clickable(enabled = !locked) {
                        if (editing) {
                            // 完成 → 保存
                            if (save()) {
                                editing = false
                                onFeedback("已保存")
                            }
                        } else {
                            editing = true
                        }
                    }
                    .padding(horizontal = 6.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Outlined.Edit, contentDescription = null,
                    modifier = Modifier.size(13.dp), tint = tint)
                Spacer(Modifier.width(4.dp))
                Text(
                    when {
                        locked -> "注销后修改"
                        editing -> "完成"
                        else -> "编辑"
                    },
                    fontSize = 12.sp,
                    fontWeight = if (editing && !locked) FontWeight.SemiBold else FontWeight.Normal,
                    color = tint
                )
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(UvpColor.BorderLight))

        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp)
        ) {
            // 编辑态:可改;只读态:同样的行,但 enabled = false 就显灰且不响应点击
            // 复用同一组组件,避免两种布局割裂
            val canEdit = editing && !locked
            InlineEditableRow(
                label = "服务器",
                value = ip,
                enabled = canEdit,
                placeholder = "例如 192.168.1.100",
                trailing = {
                    InlineCompactPort(
                        port = port,
                        enabled = canEdit,
                        placeholder = "5060",
                        onChange = { port = it.filter { c -> c.isDigit() } }
                    )
                },
                onChange = { ip = it }
            )
            InlineEditableRow(
                "服务器 ID", serverId, canEdit, KeyboardType.Number,
                placeholder = "例如 34020000002000000001"
            ) { newId ->
                serverId = newId.filter { c -> c.isDigit() }
                if (!domainManuallyEdited) domain = serverId.take(10)
            }
            InlineEditableRow(
                "服务器域", domain, canEdit, KeyboardType.Number,
                placeholder = "例如 3402000000"
            ) { newDomain ->
                domain = newDomain.filter { c -> c.isDigit() }
                domainManuallyEdited = true
            }
            InlineEditableRow(
                "设备 ID", deviceId, canEdit, KeyboardType.Number,
                placeholder = "例如 34020000001310000001"
            ) {
                deviceId = it.filter { c -> c.isDigit() }
            }
            InlineEditableRow("注册密码", password, canEdit, KeyboardType.Password,
                masked = true, placeholder = "上级平台配置的 SIP 密码") { password = it }
            InlineSegmentedRow("信令传输", transport, listOf("UDP", "TCP"), canEdit) {
                transport = it
            }
            InlineSegmentedRow(
                "对讲传输", audioTransport.label,
                AudioTransportType.entries.map { it.label }, canEdit
            ) { picked ->
                audioTransport = AudioTransportType.entries.first { it.label == picked }
            }
        }
    }
}

// ============= Active business buttons =============

@Composable
private fun ActionButtons(state: AppUiState, actions: AppActions, onFeedback: (String) -> Unit) {
    val canFire = state.sip == SipStateDto.Registered || state.sip == SipStateDto.InCall
    val toast = LocalToastHost.current
    val navigator = LocalAppNavigator.current
    var detailFor by remember { mutableStateOf<SubscriptionKind?>(null) }
    var showAlarmResetConfirm by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        val isRecording = state.recording.isRecording
        ActionTile(
            icon = Icons.Outlined.Videocam,
            label = if (isRecording) "停止录像" else "录像",
            enabled = canFire || isRecording,
            modifier = Modifier.weight(1f),
            recordingActive = isRecording,
            onClick = {
                if (isRecording) {
                    actions.onRecordingStop()
                    toast.info("已停止录像")
                } else {
                    actions.onRecordingStart()
                    toast.info("开始录像")
                }
            }
        )
        val isAlarming = state.deviceControl.isAlarming
        val alarmSubscribed = state.subscriptions[SubscriptionKind.Alarm]?.active == true
        ActionTile(
            icon = Icons.Outlined.Warning,
            label = "报警",
            enabled = canFire || isAlarming,
            modifier = Modifier.weight(1f),
            alarmActive = isAlarming,
            subscribed = alarmSubscribed,
            onClick = {
                if (isAlarming) {
                    showAlarmResetConfirm = true
                } else {
                    actions.onAlarmFireDefault()
                    toast.info("已发送报警")
                }
            },
            onLongClick = { navigator.navigateToAlarm() }
        )
        SubscriptionTile(
            icon = Icons.Outlined.LocationOn,
            label = "位置订阅",
            status = state.subscriptions[SubscriptionKind.MobilePosition] ?: SubscriptionStatus(),
            modifier = Modifier.weight(1f),
            onClick = { detailFor = SubscriptionKind.MobilePosition }
        )
        SubscriptionTile(
            icon = Icons.Outlined.FolderOpen,
            label = "目录订阅",
            status = state.subscriptions[SubscriptionKind.Catalog] ?: SubscriptionStatus(),
            modifier = Modifier.weight(1f),
            onClick = { detailFor = SubscriptionKind.Catalog }
        )
    }
    if (showAlarmResetConfirm) {
        AlertDialog(
            onDismissRequest = { showAlarmResetConfirm = false },
            title = { Text("复位报警?") },
            text = { Text("当前处于报警中状态。复位为本地操作,不会向平台发送 SIP。") },
            confirmButton = {
                TextButton(onClick = {
                    actions.onAlarmReset()
                    showAlarmResetConfirm = false
                    toast.info("已复位报警")
                }) { Text("复位") }
            },
            dismissButton = {
                TextButton(onClick = { showAlarmResetConfirm = false }) { Text("取消") }
            }
        )
    }
    detailFor?.let { kind ->
        SubscriptionDetailSheet(
            kind = kind,
            status = state.subscriptions[kind] ?: SubscriptionStatus(),
            onDismiss = { detailFor = null }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ActionTile(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    recordingActive: Boolean = false,
    alarmActive: Boolean = false,
    subscribed: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit
) {
    val accent = when {
        recordingActive -> UvpColor.Danger
        alarmActive -> UvpColor.Warning
        enabled -> UvpColor.Primary
        else -> UvpColor.TextHint
    }
    val borderColor = if (!enabled && !recordingActive && !alarmActive) UvpColor.Border else accent
    val bg = when {
        recordingActive -> UvpColor.DangerBg
        alarmActive -> UvpColor.WarningBg
        else -> UvpColor.Surface
    }
    val pulsing = recordingActive || alarmActive
    val pulseColor = if (recordingActive) UvpColor.Danger else UvpColor.Warning
    Box(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(bg)
                .border(1.dp, borderColor, RoundedCornerShape(8.dp))
                .combinedClickable(
                    enabled = enabled,
                    onClick = onClick,
                    onLongClick = onLongClick
                )
                .padding(vertical = 5.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            Icon(
                icon, contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = accent
            )
            Text(
                label, fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = accent
            )
            Text(
                when {
                    recordingActive -> "录像中"
                    alarmActive -> "报警中"
                    enabled -> "可触发"
                    else -> "未就绪"
                },
                fontSize = 8.5.sp,
                color = accent.copy(alpha = 0.7f),
                fontFamily = FontFamily.Monospace
            )
        }
        if (pulsing) {
            // 右上角脉动点 — 录像红 / 报警橙,主屏一眼可见
            val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "tile-pulse")
            val alpha by transition.animateFloat(
                initialValue = 1f,
                targetValue = 0.3f,
                animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                    animation = androidx.compose.animation.core.tween(durationMillis = 800),
                    repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
                ),
                label = "tile-alpha"
            )
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(pulseColor.copy(alpha = alpha))
            )
        }
        // 订阅角标:平台 Event:Alarm 订阅活跃且非报警态 → 静态绿点(不脉动,区别于报警橙点)
        if (subscribed && !pulsing) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(UvpColor.Success)
            )
        }
    }
}

private val SubscriptionKind.title: String
    get() = when (this) {
        SubscriptionKind.MobilePosition -> "位置订阅"
        SubscriptionKind.Catalog -> "目录订阅"
        SubscriptionKind.Alarm -> "报警订阅"
    }

@Composable
private fun SubscriptionTile(
    icon: ImageVector,
    label: String,
    status: SubscriptionStatus,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val tone = if (status.active) UvpColor.SuccessText else UvpColor.TextHint
    val border = if (status.active) UvpColor.SuccessBorder else UvpColor.Border
    val bg = if (status.active) UvpColor.SuccessBg else UvpColor.Surface
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(1.dp)
    ) {
        Icon(
            icon, contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = tone
        )
        Text(
            label, fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = tone
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(5.dp).clip(CircleShape).background(tone))
            Spacer(Modifier.width(4.dp))
            Text(
                if (status.active) "已订阅" else "未订阅",
                fontSize = 8.5.sp,
                color = tone.copy(alpha = 0.7f),
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun SubscriptionDetailSheet(
    kind: SubscriptionKind,
    status: SubscriptionStatus,
    onDismiss: () -> Unit
) {
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = UvpColor.Surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .padding(bottom = 24.dp)
        ) {
            Text(kind.title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                color = UvpColor.Text)
            Spacer(Modifier.height(4.dp))
            Text(
                if (status.active) "上级平台已订阅,设备按周期 NOTIFY"
                else "上级平台尚未发起 SUBSCRIBE",
                fontSize = 12.sp, color = UvpColor.TextSecondary
            )
            Spacer(Modifier.height(16.dp))
            DetailKv("状态", if (status.active) "已订阅" else "未订阅")
            DetailKv("订阅者", status.subscriber ?: "—")
            DetailKv("Expires", status.expiresSeconds?.let { "${it}s" } ?: "—")
            DetailKv("剩余", status.remainingSeconds?.let { "${it}s" } ?: "—")
            DetailKv("Notify 计数", status.notifyCount.toString())
        }
    }
}

@Composable
private fun DetailKv(key: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(key, fontSize = 12.sp, color = UvpColor.TextHint,
            modifier = Modifier.width(80.dp))
        Text(value, fontSize = 12.sp, color = UvpColor.Text,
            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium)
    }
}

// ============= Connect / Disconnect =============

@Composable
private fun ConnectButton(state: AppUiState, actions: AppActions, onFeedback: (String) -> Unit) {
    when (state.sip) {
        SipStateDto.Disconnected, SipStateDto.Failed -> {
            val ready = state.config.isReadyToRegister
            Button(
                onClick = {
                    actions.onConnect()
                    onFeedback("正在注册…")
                },
                enabled = ready,
                modifier = Modifier.fillMaxWidth().height(44.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = UvpColor.Primary,
                    disabledContainerColor = UvpColor.Primary.copy(alpha = 0.35f),
                    disabledContentColor = Color.White.copy(alpha = 0.75f)
                )
            ) {
                Text(
                    if (ready) "注 册" else "请先填写 SIP 配置",
                    fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    letterSpacing = if (ready) 4.sp else 1.sp
                )
            }
        }
        SipStateDto.Registering -> {
            OutlinedButton(
                onClick = {
                    actions.onCancelConnect()
                    onFeedback("已取消注册")
                },
                modifier = Modifier.fillMaxWidth().height(44.dp),
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, UvpColor.Primary.copy(alpha = 0.5f)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = UvpColor.Primary)
            ) {
                Text("注册中… 点击取消", fontSize = 13.sp,
                    fontWeight = FontWeight.Medium, letterSpacing = 1.sp)
            }
        }
        SipStateDto.Registered, SipStateDto.InCall -> {
            OutlinedButton(
                onClick = {
                    actions.onDisconnect()
                    onFeedback("已注销")
                },
                modifier = Modifier.fillMaxWidth().height(44.dp),
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, UvpColor.Danger),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = UvpColor.Danger)
            ) {
                Text("注 销", fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    color = UvpColor.Danger, letterSpacing = 4.sp)
            }
        }
    }
}

// ============= Shared components =============

@Composable
private fun KvRow(key: String, value: String, divider: Boolean) {
    Column {
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
            Text(key, fontSize = 11.5.sp, color = UvpColor.TextHint, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.weight(1f))
            Text(value, fontSize = 11.5.sp, color = UvpColor.Text, fontWeight = FontWeight.Medium,
                fontFamily = FontFamily.Monospace)
        }
        if (divider) Box(Modifier.fillMaxWidth().height(1.dp).background(UvpColor.BorderLight))
    }
}

@Composable
internal fun InlineField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    keyboard: KeyboardType = KeyboardType.Text,
    password: Boolean = false,
    enabled: Boolean = true
) {
    Column(modifier = modifier) {
        if (label.isNotEmpty()) {
            Text(label, fontSize = 11.sp, color = UvpColor.TextSecondary, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(3.dp))
        }
        BasicTextField(
            value = value,
            onValueChange = onChange,
            enabled = enabled,
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                color = if (enabled) UvpColor.Text else UvpColor.TextSecondary
            ),
            keyboardOptions = KeyboardOptions(keyboardType = keyboard),
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            cursorBrush = androidx.compose.ui.graphics.SolidColor(UvpColor.Primary),
            modifier = Modifier
                .fillMaxWidth()
                .height(38.dp)
                .background(UvpColor.Surface, RoundedCornerShape(6.dp))
                .border(
                    1.dp,
                    if (enabled) UvpColor.Border else UvpColor.BorderLight,
                    RoundedCornerShape(6.dp)
                )
                .padding(horizontal = 10.dp),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart, modifier = Modifier.fillMaxSize()) {
                    inner()
                }
            }
        )
    }
}

@Composable
internal fun InlineSegmented(
    label: String,
    active: String,
    options: List<String> = listOf("UDP", "TCP"),
    enabled: Boolean = true,
    onChange: (String) -> Unit
) {
    Column {
        Text(label, fontSize = 11.sp, color = UvpColor.TextSecondary, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(3.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(UvpColor.CodeBg, RoundedCornerShape(6.dp))
                .border(
                    1.dp,
                    if (enabled) UvpColor.Border else UvpColor.BorderLight,
                    RoundedCornerShape(6.dp)
                )
                .padding(2.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            options.forEach { t ->
                val sel = t == active
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(4.dp))
                        .background(
                            when {
                                sel && enabled -> UvpColor.PrimaryLight
                                sel -> UvpColor.PrimaryLight.copy(alpha = 0.4f)
                                else -> Color.Transparent
                            }
                        )
                        .border(
                            if (sel) 1.5.dp else 0.dp,
                            when {
                                !sel -> Color.Transparent
                                enabled -> UvpColor.Primary
                                else -> UvpColor.Primary.copy(alpha = 0.5f)
                            },
                            RoundedCornerShape(4.dp)
                        )
                        .clickable(enabled = enabled) { onChange(t) }
                        .padding(vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        t,
                        fontSize = 12.sp,
                        fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                        color = when {
                            sel && !enabled -> UvpColor.Primary.copy(alpha = 0.6f)
                            !enabled -> UvpColor.TextHint
                            sel -> UvpColor.Primary
                            else -> UvpColor.TextSecondary
                        }
                    )
                }
            }
        }
    }
}

// ============= In-place editable row (no outline, focus underline) =============

/**
 * 一行 KV 风格原位编辑控件。左 label,右可编辑文字。
 *
 * 视觉:label 左对齐 72dp 宽,value 右占剩余宽,数字字段等宽字体。
 * 不画外框,聚焦时下面亮 1.5dp 蓝线,失焦回归边线灰。
 *
 * trailing 可选:用于"服务器 + 端口同行"场景,主输入占大头,trailing 占小头。
 */
@Composable
internal fun InlineEditableRow(
    label: String,
    value: String,
    enabled: Boolean,
    keyboard: KeyboardType = KeyboardType.Text,
    masked: Boolean = false,
    placeholder: String = "",
    trailing: (@Composable () -> Unit)? = null,
    onChange: (String) -> Unit
) {
    val interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    var revealed by remember { mutableStateOf(false) }

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                label,
                modifier = Modifier.width(72.dp),
                fontSize = 11.5.sp,
                color = if (enabled) UvpColor.TextSecondary else UvpColor.TextHint,
                fontWeight = FontWeight.Medium
            )
            BasicTextField(
                value = value,
                onValueChange = onChange,
                enabled = enabled,
                singleLine = true,
                interactionSource = interactionSource,
                textStyle = androidx.compose.ui.text.TextStyle(
                    fontSize = 12.5.sp,
                    fontFamily = if (keyboard == KeyboardType.Number) FontFamily.Monospace
                    else FontFamily.Default,
                    color = if (enabled) UvpColor.Text else UvpColor.TextHint
                ),
                keyboardOptions = KeyboardOptions(keyboardType = keyboard),
                visualTransformation = if (masked && !revealed) PasswordVisualTransformation()
                else VisualTransformation.None,
                cursorBrush = androidx.compose.ui.graphics.SolidColor(UvpColor.Primary),
                decorationBox = { innerTextField ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (value.isEmpty() && placeholder.isNotEmpty()) {
                            Text(
                                placeholder,
                                fontSize = 12.5.sp,
                                fontFamily = if (keyboard == KeyboardType.Number) FontFamily.Monospace
                                else FontFamily.Default,
                                color = UvpColor.TextHint.copy(alpha = 0.6f),
                                maxLines = 1
                            )
                        }
                        innerTextField()
                    }
                },
                modifier = Modifier.weight(1f)
            )
            if (masked) {
                Icon(
                    imageVector = if (revealed) Icons.Outlined.VisibilityOff
                    else Icons.Outlined.Visibility,
                    contentDescription = if (revealed) "隐藏密码" else "显示密码",
                    tint = if (enabled) UvpColor.TextSecondary else UvpColor.TextHint,
                    modifier = Modifier
                        .padding(start = 4.dp)
                        .size(18.dp)
                        .clickable(enabled = enabled) { revealed = !revealed }
                )
            }
            trailing?.invoke()
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(if (focused && enabled) 1.dp else 0.5.dp)
                .background(if (focused && enabled) UvpColor.Primary else UvpColor.BorderLight)
        )
    }
}

/** 紧凑端口字段,跟服务器 IP 同行,固定窄宽. */
@Composable
private fun InlineCompactPort(
    port: String,
    enabled: Boolean,
    placeholder: String = "",
    onChange: (String) -> Unit
) {
    Spacer(Modifier.width(8.dp))
    Text(":", fontSize = 13.sp,
        color = if (enabled) UvpColor.TextSecondary else UvpColor.TextHint)
    Spacer(Modifier.width(4.dp))
    BasicTextField(
        value = port,
        onValueChange = onChange,
        enabled = enabled,
        singleLine = true,
        textStyle = androidx.compose.ui.text.TextStyle(
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
            color = if (enabled) UvpColor.Text else UvpColor.TextHint
        ),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        cursorBrush = androidx.compose.ui.graphics.SolidColor(UvpColor.Primary),
        decorationBox = { innerTextField ->
            Box(contentAlignment = Alignment.CenterStart) {
                if (port.isEmpty() && placeholder.isNotEmpty()) {
                    Text(
                        placeholder,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace,
                        color = UvpColor.TextHint.copy(alpha = 0.6f),
                        maxLines = 1
                    )
                }
                innerTextField()
            }
        },
        modifier = Modifier.width(54.dp)
    )
}

/** 行式 segmented:左 label,右选项 chip 行. */
@Composable
internal fun InlineSegmentedRow(
    label: String,
    active: String,
    options: List<String>,
    enabled: Boolean,
    onChange: (String) -> Unit
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                label,
                modifier = Modifier.width(72.dp),
                fontSize = 11.5.sp,
                color = if (enabled) UvpColor.TextSecondary else UvpColor.TextHint,
                fontWeight = FontWeight.Medium
            )
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                options.forEach { opt ->
                    val sel = opt == active
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(
                                when {
                                    sel && enabled -> UvpColor.PrimaryLight
                                    sel -> UvpColor.PrimaryLight.copy(alpha = 0.4f)
                                    else -> Color.Transparent
                                }
                            )
                            .border(
                                1.dp,
                                when {
                                    !sel -> UvpColor.BorderLight
                                    enabled -> UvpColor.Primary
                                    else -> UvpColor.Primary.copy(alpha = 0.5f)
                                },
                                RoundedCornerShape(4.dp)
                            )
                            .clickable(enabled = enabled) { onChange(opt) }
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            opt,
                            fontSize = 11.sp,
                            fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                            color = when {
                                sel && !enabled -> UvpColor.Primary.copy(alpha = 0.6f)
                                !enabled -> UvpColor.TextHint
                                sel -> UvpColor.Primary
                                else -> UvpColor.TextSecondary
                            }
                        )
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(UvpColor.BorderLight))
    }
}
