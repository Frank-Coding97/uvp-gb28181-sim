package com.uvp.sim.ui

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uvp.sim.sip.SipState
import kotlinx.coroutines.launch

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
fun HomeScreen(state: AppUiState, actions: AppActions, snackbar: SnackbarHostState) {
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(scroll).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        StatusBanner(state)
        CameraPreviewBox(state)
        SipConfigCard(state, actions, onFeedback = { msg ->
            scope.launch { snackbar.showSnackbar(msg) }
        })
        SnapshotButton(state, actions, onFeedback = { msg ->
            scope.launch { snackbar.showSnackbar(msg) }
        })
        ConnectButton(state, actions, onFeedback = { msg ->
            scope.launch { snackbar.showSnackbar(msg) }
        })
        AdvancedSection(state, actions)
    }
}

// ============= Status banner =============

@Composable
private fun StatusBanner(state: AppUiState) {
    val spec = when (state.sip) {
        SipState.Registered, SipState.InCall -> BannerSpec(
            UvpColor.SuccessBg, UvpColor.SuccessBorder, UvpColor.Success,
            "设备已注册", UvpColor.SuccessText,
            "心跳 ${state.config.keepaliveIntervalSeconds}s"
        )
        SipState.Registering -> BannerSpec(
            UvpColor.WarningBg, UvpColor.WarningBorder, UvpColor.Warning,
            "正在注册…", UvpColor.Warning, "等待平台响应"
        )
        SipState.Disconnected -> BannerSpec(
            UvpColor.BorderLight, UvpColor.Border, UvpColor.TextHint,
            "未连接", UvpColor.TextSecondary, "编辑配置 → 注册"
        )
        SipState.Failed -> {
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
        Text(spec.extra, fontSize = 11.sp, color = UvpColor.TextHint, fontFamily = FontFamily.Monospace,
            maxLines = 1)
    }
}

private data class BannerSpec(
    val bg: Color, val border: Color, val dot: Color,
    val text: String, val textColor: Color, val extra: String
)

// ============= Camera preview =============

@Composable
private fun CameraPreviewBox(state: AppUiState) {
    val live = state.sip == SipState.InCall
    val showPreview = state.sip == SipState.Registered || state.sip == SipState.InCall
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp)
            .background(Color(0xFF1F2937), RoundedCornerShape(8.dp))
            .clip(RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center
    ) {
        if (showPreview) {
            PlatformCameraPreview(modifier = Modifier.fillMaxSize())
        } else {
            Icon(
                Icons.Outlined.PhotoCamera, contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = Color.White.copy(alpha = 0.3f)
            )
            Text(
                "注册后开启预览",
                fontSize = 11.sp,
                color = Color.White.copy(alpha = 0.3f),
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp)
            )
        }
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp)
                .background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(4.dp))
                .padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (live) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(UvpColor.Danger))
                Spacer(Modifier.width(4.dp))
            }
            Text(
                when {
                    live -> "1280×720 · 25fps · LIVE"
                    showPreview -> "1280×720 · 预览"
                    else -> "未推流"
                },
                fontSize = 9.sp, color = Color.White.copy(alpha = 0.55f),
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

// ============= SIP config card (inline edit) =============

@Composable
private fun SipConfigCard(state: AppUiState, actions: AppActions, onFeedback: (String) -> Unit) {
    var editing by remember { mutableStateOf(false) }
    var ip by remember(state.config) { mutableStateOf(state.config.server.ip) }
    var port by remember(state.config) { mutableStateOf(state.config.server.port.toString()) }
    var deviceId by remember(state.config) { mutableStateOf(state.config.device.deviceId) }
    var transport by remember(state.config) { mutableStateOf(state.config.transport.name) }
    var serverId by remember(state.config) { mutableStateOf(state.config.server.serverId) }
    var domain by remember(state.config) { mutableStateOf(state.config.server.domain) }

    val locked = state.sip == SipState.Registered || state.sip == SipState.InCall
    // Force collapse when transitioning into a locked state mid-edit
    if (locked && editing) editing = false

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(UvpColor.Surface, RoundedCornerShape(8.dp))
            .border(1.dp, UvpColor.Border, RoundedCornerShape(8.dp))
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("SIP 配置", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = UvpColor.TextHint)
            Spacer(Modifier.weight(1f))
            val tint = if (locked) UvpColor.TextHint else UvpColor.Primary
            Row(
                modifier = Modifier
                    .clickable(enabled = !locked) { editing = !editing }
                    .padding(horizontal = 2.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Outlined.Edit, contentDescription = null,
                    modifier = Modifier.size(14.dp), tint = tint)
                Spacer(Modifier.width(4.dp))
                Text(
                    when {
                        locked -> "注销后修改"
                        editing -> "收起"
                        else -> "编辑"
                    },
                    fontSize = 12.sp, color = tint
                )
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(UvpColor.BorderLight))

        if (!editing) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                KvRow("服务器", "${state.config.server.ip}:${state.config.server.port}", true)
                KvRow("设备 ID", state.config.device.deviceId, true)
                KvRow("传输", state.config.transport.name, true)
                KvRow("服务器 ID", state.config.server.serverId, true)
                KvRow("域", state.config.server.domain, false)
            }
        } else {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    InlineField("服务器", ip, { ip = it }, Modifier.weight(2f))
                    InlineField("端口", port, { port = it.filter { c -> c.isDigit() } }, Modifier.weight(1f),
                        keyboard = KeyboardType.Number)
                }
                InlineField("设备 ID", deviceId, { deviceId = it.filter { c -> c.isDigit() } })
                InlineSegmented("传输方式", transport) { transport = it }
                InlineField("服务器 ID", serverId, { serverId = it.filter { c -> c.isDigit() } })
                InlineField("服务器域", domain, { domain = it.filter { c -> c.isDigit() } })
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            if (ip.isBlank() || deviceId.isBlank() || serverId.isBlank()) {
                                onFeedback("服务器、设备ID、服务器ID 不能为空")
                                return@Button
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
                                        username = deviceId
                                    ),
                                    transport = com.uvp.sim.network.TransportType.valueOf(transport)
                                )
                            )
                            editing = false
                            onFeedback("配置已保存")
                        },
                        modifier = Modifier.weight(1f).height(36.dp),
                        shape = RoundedCornerShape(6.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = UvpColor.Primary)
                    ) { Text("保存", fontSize = 12.sp, color = Color.White) }
                    OutlinedButton(
                        onClick = {
                            ip = state.config.server.ip
                            port = state.config.server.port.toString()
                            deviceId = state.config.device.deviceId
                            transport = state.config.transport.name
                            serverId = state.config.server.serverId
                            domain = state.config.server.domain
                            editing = false
                        },
                        modifier = Modifier.weight(1f).height(36.dp),
                        shape = RoundedCornerShape(6.dp)
                    ) { Text("取消", fontSize = 12.sp) }
                }
            }
        }
    }
}

// ============= Snapshot button =============

@Composable
private fun SnapshotButton(state: AppUiState, actions: AppActions, onFeedback: (String) -> Unit) {
    val canFire = state.sip == SipState.Registered || state.sip == SipState.InCall
    if (!canFire) return
    OutlinedButton(
        onClick = {
            actions.onSnapshot()
            onFeedback("抓拍已上报")
        },
        modifier = Modifier.fillMaxWidth().height(40.dp),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, UvpColor.Primary),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = UvpColor.Primary)
    ) {
        Icon(Icons.Outlined.PhotoCamera, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text("抓拍上报", fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

// ============= Connect / Disconnect =============

@Composable
private fun ConnectButton(state: AppUiState, actions: AppActions, onFeedback: (String) -> Unit) {
    when (state.sip) {
        SipState.Disconnected, SipState.Failed -> {
            Button(
                onClick = {
                    actions.onConnect()
                    onFeedback("正在注册…")
                },
                modifier = Modifier.fillMaxWidth().height(44.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = UvpColor.Primary)
            ) {
                Text("注 册", fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    color = Color.White, letterSpacing = 4.sp)
            }
        }
        SipState.Registering -> {
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
        SipState.Registered, SipState.InCall -> {
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

// ============= Advanced settings (collapsed) =============

@Composable
private fun AdvancedSection(state: AppUiState, actions: AppActions) {
    var expanded by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(UvpColor.Surface, RoundedCornerShape(8.dp))
            .border(1.dp, UvpColor.Border, RoundedCornerShape(8.dp))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("高级设置", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = UvpColor.TextHint)
            Spacer(Modifier.weight(1f))
            Icon(
                if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = UvpColor.TextHint
            )
        }
        if (expanded) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(UvpColor.BorderLight))
            AdvancedContent(state, actions)
        }
    }
}

@Composable
private fun AdvancedContent(state: AppUiState, actions: AppActions) {
    var videoChannelId by remember(state.config) { mutableStateOf(state.config.device.videoChannelId) }
    var alarmChannelId by remember(state.config) { mutableStateOf(state.config.device.alarmChannelId) }
    var password by remember(state.config) { mutableStateOf(state.config.device.password) }
    var keepalive by remember(state.config) { mutableStateOf(state.config.keepaliveIntervalSeconds.toString()) }
    val locked = state.sip == SipState.Registered || state.sip == SipState.InCall

    Column(
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        InlineField("视频通道 ID", videoChannelId, { videoChannelId = it.filter { c -> c.isDigit() } },
            enabled = !locked)
        InlineField("报警通道 ID", alarmChannelId, { alarmChannelId = it.filter { c -> c.isDigit() } },
            enabled = !locked)
        InlineField("密码", password, { password = it }, password = true, enabled = !locked)
        InlineField("心跳间隔(秒)", keepalive, { keepalive = it.filter { c -> c.isDigit() } },
            keyboard = KeyboardType.Number, enabled = !locked)
        Spacer(Modifier.height(4.dp))
        Button(
            enabled = !locked,
            onClick = {
                actions.onConfigSave(
                    state.config.copy(
                        device = state.config.device.copy(
                            videoChannelId = videoChannelId,
                            alarmChannelId = alarmChannelId,
                            password = password
                        ),
                        keepaliveIntervalSeconds = keepalive.toIntOrNull() ?: 60
                    )
                )
            },
            modifier = Modifier.fillMaxWidth().height(36.dp),
            shape = RoundedCornerShape(6.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = UvpColor.Primary,
                disabledContainerColor = UvpColor.Border
            )
        ) {
            Text(
                if (locked) "注销后修改" else "保存高级设置",
                fontSize = 12.sp,
                color = if (locked) UvpColor.TextHint else Color.White
            )
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
private fun InlineField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    keyboard: KeyboardType = KeyboardType.Text,
    password: Boolean = false,
    enabled: Boolean = true
) {
    Column(modifier = modifier) {
        Text(label, fontSize = 11.sp, color = UvpColor.TextHint)
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            enabled = enabled,
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, fontFamily = FontFamily.Monospace),
            keyboardOptions = KeyboardOptions(keyboardType = keyboard),
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            shape = RoundedCornerShape(6.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = UvpColor.Primary,
                unfocusedBorderColor = UvpColor.Border,
                focusedContainerColor = UvpColor.Surface,
                unfocusedContainerColor = UvpColor.Surface,
                disabledBorderColor = UvpColor.BorderLight,
                disabledTextColor = UvpColor.TextSecondary,
                disabledContainerColor = UvpColor.Surface
            )
        )
    }
}

@Composable
private fun InlineSegmented(label: String, active: String, onChange: (String) -> Unit) {
    Column {
        Text(label, fontSize = 11.sp, color = UvpColor.TextHint)
        Spacer(Modifier.height(2.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(UvpColor.CodeBg, RoundedCornerShape(6.dp))
                .border(1.dp, UvpColor.Border, RoundedCornerShape(6.dp))
                .padding(2.dp)
        ) {
            listOf("UDP", "TCP").forEach { t ->
                val sel = t == active
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .background(if (sel) UvpColor.Surface else Color.Transparent, RoundedCornerShape(4.dp))
                        .clickable { onChange(t) }
                        .padding(vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(t, fontSize = 12.sp,
                        fontWeight = if (sel) FontWeight.Medium else FontWeight.Normal,
                        color = if (sel) UvpColor.Primary else UvpColor.TextSecondary)
                }
            }
        }
    }
}
