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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uvp.sim.sip.SipState

/**
 * 主屏 Home — 1:1 还原 index-v1.html § SCREEN 1.
 *
 * 结构 (从上到下):
 *   - 状态横条 banner (绿/红/橙底色 + 左点 + 文字 + 右辅助信息)
 *   - 摄像头预览大方框 (深灰 + 中央相机 icon + 右上 LIVE 徽章)
 *   - SIP 配置摘要卡片 (卡头 "SIP 配置摘要" + 右上"编辑"链接;3 行 K-V)
 *   - 4 主动业务色块按钮 (一行 4 等分,蓝色 icon + 灰色 label)
 *   - 底部"注 销"红描边大按钮 (Disconnect / Failed 时显示蓝色"注 册"实心)
 */
@Composable
fun HomeScreen(state: AppUiState, actions: AppActions, onNavigate: (AppTab) -> Unit) {
    val scroll = rememberScrollState()
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(scroll).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        StatusBanner(state)
        CameraPreviewBox(state)
        SipSummaryCardInline(state, actions)
        QuickActionsGrid(state, actions)
        Spacer(Modifier.height(2.dp))
        ConnectButton(state, actions)
    }
}

// ============= Status banner =============

@Composable
private fun StatusBanner(state: AppUiState) {
    val (bg, border, dotColor, text, textColor, extra) = when (state.sip) {
        SipState.Registered, SipState.InCall -> BannerSpec(
            UvpColor.SuccessBg, UvpColor.SuccessBorder, UvpColor.Success,
            "设备已注册",
            UvpColor.SuccessText,
            "心跳 ${state.config.keepaliveIntervalSeconds}s"
        )
        SipState.Registering -> BannerSpec(
            UvpColor.WarningBg, UvpColor.WarningBorder, UvpColor.Warning,
            "正在注册…",
            UvpColor.Warning,
            "等待平台响应"
        )
        SipState.Disconnected -> BannerSpec(
            UvpColor.BorderLight, UvpColor.Border, UvpColor.TextHint,
            "未连接",
            UvpColor.TextSecondary,
            "请点下方 注 册"
        )
        SipState.Failed -> BannerSpec(
            UvpColor.DangerBg, UvpColor.DangerBorder, UvpColor.Danger,
            "注册失败",
            UvpColor.DangerText,
            "查看日志"
        )
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg, RoundedCornerShape(8.dp))
            .border(1.dp, border, RoundedCornerShape(8.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(dotColor))
        Spacer(Modifier.width(10.dp))
        Text(text, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = textColor)
        Spacer(Modifier.weight(1f))
        Text(extra, fontSize = 11.sp, color = UvpColor.TextHint, fontFamily = FontFamily.Monospace)
    }
}

private data class BannerSpec(
    val bg: Color, val border: Color, val dot: Color,
    val text: String, val textColor: Color, val extra: String
)

// ============= Camera preview box =============

@Composable
private fun CameraPreviewBox(state: AppUiState) {
    val live = state.sip == SipState.InCall
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp)
            .background(Color(0xFF1F2937), RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Outlined.PhotoCamera, contentDescription = null,
            modifier = Modifier.size(28.dp),
            tint = Color.White.copy(alpha = 0.3f)
        )
        // top-right LIVE badge
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp)
                .background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(4.dp))
                .padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (live) {
                Box(
                    Modifier.size(6.dp).clip(CircleShape).background(UvpColor.Danger)
                )
                Spacer(Modifier.width(4.dp))
            }
            Text(
                if (live) "1280×720 · 25fps · LIVE" else "未推流",
                fontSize = 9.sp, color = Color.White.copy(alpha = 0.55f),
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

// ============= SIP summary card (with inline edit) =============

@Composable
private fun SipSummaryCardInline(state: AppUiState, actions: AppActions) {
    var editing by remember { mutableStateOf(false) }
    var ip by remember(state.config) { mutableStateOf(state.config.server.ip) }
    var port by remember(state.config) { mutableStateOf(state.config.server.port.toString()) }
    var deviceId by remember(state.config) { mutableStateOf(state.config.device.deviceId) }
    var transport by remember(state.config) { mutableStateOf(state.config.transport.name) }
    var talkTransport by remember { mutableStateOf("UDP") }
    var serverId by remember(state.config) { mutableStateOf(state.config.server.serverId) }
    var domain by remember(state.config) { mutableStateOf(state.config.server.domain) }

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
            Text(
                "SIP 配置摘要",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = UvpColor.TextHint
            )
            Spacer(Modifier.weight(1f))
            Row(
                modifier = Modifier.clickable { editing = !editing },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Outlined.Edit, contentDescription = "编辑",
                    modifier = Modifier.size(14.dp), tint = UvpColor.Primary
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    if (editing) "收起" else "编辑",
                    fontSize = 12.sp, color = UvpColor.Primary
                )
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(UvpColor.BorderLight))

        if (!editing) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                KvRow("服务器", "${state.config.server.ip}:${state.config.server.port}", divider = true)
                KvRow("设备ID", state.config.device.deviceId, divider = true)
                KvRow(
                    "协议",
                    "${state.config.transport.name} · GB/T 28181-${state.config.gbVersion.label.takeLast(4)}",
                    divider = false
                )
            }
        } else {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                InlineField("服务器", ip) { ip = it }
                InlineField("端口", port) { port = it.filter { c -> c.isDigit() } }
                InlineField("设备 ID", deviceId) { deviceId = it.filter { c -> c.isDigit() } }
                InlineSegmented("传输方式", transport) { transport = it }
                InlineSegmented("对讲传输 (M2)", talkTransport, enabled = false) { talkTransport = it }
                InlineField("服务器 ID", serverId) { serverId = it.filter { c -> c.isDigit() } }
                InlineField("服务器域", domain) { domain = it.filter { c -> c.isDigit() } }
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
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

@Composable
private fun InlineField(label: String, value: String, onChange: (String) -> Unit) {
    Column {
        Text(label, fontSize = 11.sp, color = UvpColor.TextHint)
        Spacer(Modifier.height(2.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            modifier = Modifier.fillMaxWidth().height(42.dp),
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(
                fontSize = 13.sp, fontFamily = FontFamily.Monospace
            ),
            shape = RoundedCornerShape(6.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = UvpColor.Primary,
                unfocusedBorderColor = UvpColor.Border,
                focusedContainerColor = UvpColor.Surface,
                unfocusedContainerColor = UvpColor.Surface
            )
        )
    }
}

@Composable
private fun InlineSegmented(label: String, active: String, enabled: Boolean = true, onChange: (String) -> Unit) {
    Column(modifier = Modifier.run { if (!enabled) this.then(Modifier) else this }) {
        Text(label, fontSize = 11.sp, color = if (enabled) UvpColor.TextHint else UvpColor.TextHint.copy(alpha = 0.5f))
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
                        .background(
                            if (sel) UvpColor.Surface else Color.Transparent,
                            RoundedCornerShape(4.dp)
                        )
                        .clickable(enabled = enabled) { onChange(t) }
                        .padding(vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        t,
                        fontSize = 12.sp,
                        fontWeight = if (sel) FontWeight.Medium else FontWeight.Normal,
                        color = if (!enabled) UvpColor.TextHint
                               else if (sel) UvpColor.Primary
                               else UvpColor.TextSecondary
                    )
                }
            }
        }
    }
}

@Composable
private fun KvRow(key: String, value: String, divider: Boolean) {
    Column {
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
            Text(key, fontSize = 11.5.sp, color = UvpColor.TextHint, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.weight(1f))
            Text(value, fontSize = 11.5.sp, color = UvpColor.Text, fontWeight = FontWeight.Medium,
                fontFamily = FontFamily.Monospace)
        }
        if (divider) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(UvpColor.BorderLight))
        }
    }
}

// ============= 4 quick action buttons =============

@Composable
private fun QuickActionsGrid(state: AppUiState, actions: AppActions) {
    val canFire = state.sip == SipState.Registered || state.sip == SipState.InCall
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        QaButton(Icons.Outlined.PhotoCamera, "抓拍",
            enabled = canFire, modifier = Modifier.weight(1f),
            onClick = actions::onSnapshot)
        QaButton(Icons.Outlined.Warning, "报警",
            enabled = false, modifier = Modifier.weight(1f),
            onClick = {}, badge = "M2")
        QaButton(Icons.Outlined.LocationOn, "位置",
            enabled = false, modifier = Modifier.weight(1f),
            onClick = {}, badge = "M2")
        QaButton(Icons.Outlined.PlayArrow, "录像",
            enabled = false, modifier = Modifier.weight(1f),
            onClick = {}, badge = "M2")
    }
}

@Composable
private fun QaButton(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    badge: String = ""
) {
    val borderColor = if (enabled) UvpColor.Border else UvpColor.BorderLight
    val iconTint = if (enabled) UvpColor.Primary else UvpColor.TextHint
    val labelColor = if (enabled) UvpColor.TextSecondary else UvpColor.TextHint
    Box(
        modifier = modifier
            .height(80.dp)
            .background(UvpColor.Surface, RoundedCornerShape(8.dp))
            .border(1.dp, borderColor, RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(18.dp), tint = iconTint)
            Spacer(Modifier.height(6.dp))
            Text(label, fontSize = 10.sp, fontWeight = FontWeight.Medium, color = labelColor)
            if (badge.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(badge, fontSize = 8.sp, color = UvpColor.TextHint,
                    fontFamily = FontFamily.Monospace)
            }
        }
    }
}

// ============= Connect / Disconnect button =============

@Composable
private fun ConnectButton(state: AppUiState, actions: AppActions) {
    when (state.sip) {
        SipState.Disconnected, SipState.Failed -> {
            Button(
                onClick = actions::onConnect,
                modifier = Modifier.fillMaxWidth().height(44.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = UvpColor.Primary)
            ) {
                Text(
                    "注 册",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    letterSpacing = 4.sp
                )
            }
        }
        SipState.Registering -> {
            Button(
                onClick = {},
                enabled = false,
                modifier = Modifier.fillMaxWidth().height(44.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(
                    disabledContainerColor = UvpColor.Primary.copy(alpha = 0.5f),
                    disabledContentColor = Color.White.copy(alpha = 0.7f)
                )
            ) {
                Text(
                    "注册中…",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 2.sp
                )
            }
        }
        SipState.Registered, SipState.InCall -> {
            OutlinedButton(
                onClick = actions::onDisconnect,
                modifier = Modifier.fillMaxWidth().height(44.dp),
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, UvpColor.Danger),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = UvpColor.Danger)
            ) {
                Text(
                    "注 销",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = UvpColor.Danger,
                    letterSpacing = 4.sp
                )
            }
        }
    }
}
