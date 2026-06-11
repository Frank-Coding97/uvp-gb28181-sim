package com.uvp.sim.ui

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uvp.sim.config.DeviceConfig
import com.uvp.sim.config.GbVersion
import com.uvp.sim.config.ServerConfig
import com.uvp.sim.config.SimConfig
import com.uvp.sim.network.TransportType

/**
 * 配置 Config screen — 1:1 还原 index-v1.html § SCREEN 2.
 *
 * 顶部 3 tab:基础(active)/ 流媒体 / 高级
 * 基础 tab 内容:
 *   - 国标版本下拉
 *   - SIP 服务器 + 端口 (2 列)
 *   - 服务器ID(必填红 *)
 *   - SIP 域
 *   - 设备编码(必填红 *)
 *   - 用户名 + 密码 (2 列)
 *   - 传输协议 segmented (UDP|TCP)
 *   - 注册参数 section title
 *   - 有效期 / 心跳 / 超时 (3 列)
 * 底部:保存按钮(覆盖原型的 FAB,因为不做扫码)
 */
@Composable
fun ConfigScreen(state: AppUiState, actions: AppActions) {
    var ip by remember(state.config) { mutableStateOf(state.config.server.ip) }
    var port by remember(state.config) { mutableStateOf(state.config.server.port.toString()) }
    var serverId by remember(state.config) { mutableStateOf(state.config.server.serverId) }
    var domain by remember(state.config) { mutableStateOf(state.config.server.domain) }
    var deviceId by remember(state.config) { mutableStateOf(state.config.device.deviceId) }
    var videoChannelId by remember(state.config) { mutableStateOf(state.config.device.videoChannelId) }
    var alarmChannelId by remember(state.config) { mutableStateOf(state.config.device.alarmChannelId) }
    var username by remember(state.config) { mutableStateOf(state.config.device.username) }
    var password by remember(state.config) { mutableStateOf(state.config.device.password) }
    var transport by remember(state.config) { mutableStateOf(state.config.transport) }
    var expires by remember(state.config) { mutableStateOf(state.config.expiresSeconds.toString()) }
    var keepalive by remember(state.config) { mutableStateOf(state.config.keepaliveIntervalSeconds.toString()) }
    var maxTimeout by remember(state.config) { mutableStateOf(state.config.maxKeepaliveTimeouts.toString()) }
    var activeTab by rememberSaveable { mutableStateOf("基础") }

    Column(modifier = Modifier.fillMaxSize().background(UvpColor.Bg)) {
        TabRow(activeTab) { activeTab = it }
        when (activeTab) {
            "基础" -> BasicConfigContent(
                ip = ip, onIp = { ip = it },
                port = port, onPort = { port = it.filter { c -> c.isDigit() } },
                serverId = serverId, onServerId = { serverId = it.filter { c -> c.isDigit() } },
                domain = domain, onDomain = { domain = it.filter { c -> c.isDigit() } },
                deviceId = deviceId, onDeviceId = { deviceId = it.filter { c -> c.isDigit() } },
                videoChannelId = videoChannelId, onVideoChannelId = { videoChannelId = it.filter { c -> c.isDigit() } },
                alarmChannelId = alarmChannelId, onAlarmChannelId = { alarmChannelId = it.filter { c -> c.isDigit() } },
                username = username, onUsername = { username = it.filter { c -> c.isDigit() } },
                password = password, onPassword = { password = it },
                transport = transport, onTransport = { transport = it },
                expires = expires, onExpires = { expires = it.filter { c -> c.isDigit() } },
                keepalive = keepalive, onKeepalive = { keepalive = it.filter { c -> c.isDigit() } },
                maxTimeout = maxTimeout, onMaxTimeout = { maxTimeout = it.filter { c -> c.isDigit() } },
                onSave = {
                    actions.onConfigSave(
                        SimConfig(
                            gbVersion = state.config.gbVersion,
                            server = ServerConfig(
                                ip = ip,
                                port = port.toIntOrNull() ?: 5060,
                                serverId = serverId,
                                domain = domain
                            ),
                            device = DeviceConfig(
                                deviceId = deviceId,
                                videoChannelId = videoChannelId,
                                alarmChannelId = alarmChannelId,
                                username = username.ifBlank { deviceId },
                                password = password
                            ),
                            transport = transport,
                            expiresSeconds = expires.toIntOrNull() ?: 3600,
                            keepaliveIntervalSeconds = keepalive.toIntOrNull() ?: 60,
                            maxKeepaliveTimeouts = maxTimeout.toIntOrNull() ?: 3,
                            userAgent = state.config.userAgent
                        )
                    )
                }
            )
            "流媒体" -> ComingSoonContent("流媒体设置", "M2 上线 — 分辨率 / 码率 / 帧率 / 编码")
            "高级" -> ComingSoonContent("高级设置", "M2 上线 — 测试连接 / 日志级别 / 特殊设置")
        }
    }
}

@Composable
private fun TabRow(active: String, onTabClick: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(UvpColor.Surface)
            .padding(horizontal = 12.dp)
    ) {
        listOf("基础", "流媒体", "高级").forEach { tab ->
            Tab(tab, tab == active) { onTabClick(tab) }
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(UvpColor.Border))
}

@Composable
private fun Tab(label: String, active: Boolean, onClick: () -> Unit) {
    val color = if (active) UvpColor.Primary else UvpColor.TextSecondary
    Column(
        modifier = Modifier
            .padding(end = 4.dp)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            label,
            fontSize = 13.sp,
            fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
            color = color,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
        )
        Box(
            Modifier
                .height(2.dp)
                .width(28.dp)
                .background(if (active) UvpColor.Primary else Color.Transparent)
        )
    }
}

@Composable
private fun BasicConfigContent(
    ip: String, onIp: (String) -> Unit,
    port: String, onPort: (String) -> Unit,
    serverId: String, onServerId: (String) -> Unit,
    domain: String, onDomain: (String) -> Unit,
    deviceId: String, onDeviceId: (String) -> Unit,
    videoChannelId: String, onVideoChannelId: (String) -> Unit,
    alarmChannelId: String, onAlarmChannelId: (String) -> Unit,
    username: String, onUsername: (String) -> Unit,
    password: String, onPassword: (String) -> Unit,
    transport: TransportType, onTransport: (TransportType) -> Unit,
    expires: String, onExpires: (String) -> Unit,
    keepalive: String, onKeepalive: (String) -> Unit,
    maxTimeout: String, onMaxTimeout: (String) -> Unit,
    onSave: () -> Unit
) {
    val scroll = rememberScrollState()
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp).verticalScroll(scroll),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Spacer(Modifier.height(8.dp))
        SectionTitle("连接")
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FormItem("SIP 服务器", "*", modifier = Modifier.weight(2f)) {
                CompactInput(ip, onIp)
            }
            FormItem("端口", "*", modifier = Modifier.weight(1f)) {
                CompactInput(port, onPort, keyboard = KeyboardType.Number)
            }
        }
        FormItem("设备编码", "*") { CompactInput(deviceId, onDeviceId) }
        FormItem("传输协议") { Segmented(transport, onTransport) }

        SectionTitle("平台")
        FormItem("服务器 ID", "*") { CompactInput(serverId, onServerId) }
        FormItem("服务器域") { CompactInput(domain, onDomain) }

        SectionTitle("通道")
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FormItem("视频通道 ID", " ", modifier = Modifier.weight(1f)) {
                CompactInput(videoChannelId, onVideoChannelId)
            }
            FormItem("报警通道 ID", " ", modifier = Modifier.weight(1f)) {
                CompactInput(alarmChannelId, onAlarmChannelId)
            }
        }

        SectionTitle("认证")
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FormItem("用户名", " ", modifier = Modifier.weight(1f)) {
                CompactInput(username, onUsername)
            }
            FormItem("密码", " ", modifier = Modifier.weight(1f)) {
                CompactInput(password, onPassword, password = true)
            }
        }

        FormItem("传输协议") { Segmented(transport, onTransport) }

        SectionTitle("注册参数")
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FormItem("有效期(秒)", " ", modifier = Modifier.weight(1f)) {
                CompactInput(expires, onExpires, keyboard = KeyboardType.Number)
            }
            FormItem("心跳(秒)", " ", modifier = Modifier.weight(1f)) {
                CompactInput(keepalive, onKeepalive, keyboard = KeyboardType.Number)
            }
            FormItem("超时次数", " ", modifier = Modifier.weight(1f)) {
                CompactInput(maxTimeout, onMaxTimeout, keyboard = KeyboardType.Number)
            }
        }

        Spacer(Modifier.height(8.dp))
        Button(
            onClick = onSave,
            modifier = Modifier.fillMaxWidth().height(44.dp),
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(containerColor = UvpColor.Primary)
        ) {
            Text("保存", fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                color = Color.White, letterSpacing = 2.sp)
        }
        Text(
            "保存后下次 Connect 生效;若已注册会自动重连",
            fontSize = 11.sp, color = UvpColor.TextHint,
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 24.dp)
        )
    }
}

@Composable
private fun FormItem(label: String, required: String = "", modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 12.sp, color = UvpColor.TextSecondary)
            if (required.isNotBlank()) {
                Spacer(Modifier.width(2.dp))
                Text(required, fontSize = 12.sp, color = UvpColor.Danger)
            }
        }
        Spacer(Modifier.height(4.dp))
        content()
    }
}

@Composable
private fun CompactInput(
    value: String,
    onChange: (String) -> Unit,
    keyboard: KeyboardType = KeyboardType.Text,
    password: Boolean = false
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        textStyle = androidx.compose.ui.text.TextStyle(
            fontSize = 13.sp, fontFamily = FontFamily.Monospace
        ),
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        visualTransformation = if (password) PasswordVisualTransformation()
                               else androidx.compose.ui.text.input.VisualTransformation.None,
        shape = RoundedCornerShape(6.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = UvpColor.Primary,
            unfocusedBorderColor = UvpColor.Border,
            focusedContainerColor = UvpColor.Surface,
            unfocusedContainerColor = UvpColor.Surface
        )
    )
}

@Composable
private fun Segmented(active: TransportType, onChange: (TransportType) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(UvpColor.CodeBg, RoundedCornerShape(6.dp))
            .border(1.dp, UvpColor.Border, RoundedCornerShape(6.dp))
            .padding(2.dp)
    ) {
        TransportType.entries.forEach { t ->
            val sel = t == active
            Box(
                modifier = Modifier
                    .weight(1f)
                    .background(
                        if (sel) UvpColor.Surface else Color.Transparent,
                        RoundedCornerShape(4.dp)
                    )
                    .clickable { onChange(t) }
                    .padding(vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    t.name,
                    fontSize = 12.sp,
                    fontWeight = if (sel) FontWeight.Medium else FontWeight.Normal,
                    color = if (sel) UvpColor.Primary else UvpColor.TextSecondary
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text, fontSize = 11.sp, color = UvpColor.TextHint, fontWeight = FontWeight.Medium)
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f).height(1.dp).background(UvpColor.Border))
    }
}

@Composable
private fun ComingSoonContent(title: String, hint: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(title, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = UvpColor.TextSecondary)
        Spacer(Modifier.height(8.dp))
        Text(hint, fontSize = 12.sp, color = UvpColor.TextHint)
    }
}

private val Color: androidx.compose.ui.graphics.Color.Companion get() = androidx.compose.ui.graphics.Color
