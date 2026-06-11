package com.uvp.sim.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.uvp.sim.config.DeviceConfig
import com.uvp.sim.config.GbVersion
import com.uvp.sim.config.ServerConfig
import com.uvp.sim.config.SimConfig

/**
 * 配置 Config screen — 手填 SIP 服务器 + 设备 ID + 密码。
 * Save 按钮把更新后的 SimConfig 提交回 ViewModel,触发重新注册。
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
    var password by remember(state.config) { mutableStateOf(state.config.device.password) }

    val scroll = rememberScrollState()
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(scroll),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("SIP 服务器", style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(value = ip, onValueChange = { ip = it },
            label = { Text("SIP 服务器 IP") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = port, onValueChange = { port = it.filter { c -> c.isDigit() } },
            label = { Text("端口") }, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        OutlinedTextField(value = serverId, onValueChange = { serverId = it.filter { c -> c.isDigit() } },
            label = { Text("服务器 ID(20位)") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = domain, onValueChange = { domain = it.filter { c -> c.isDigit() } },
            label = { Text("SIP 域(10位)") }, modifier = Modifier.fillMaxWidth())

        Spacer(Modifier.height(4.dp))
        Text("设备 ID", style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(value = deviceId, onValueChange = { deviceId = it.filter { c -> c.isDigit() } },
            label = { Text("设备编码(20位)") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = videoChannelId, onValueChange = { videoChannelId = it.filter { c -> c.isDigit() } },
            label = { Text("视频通道 ID") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = alarmChannelId, onValueChange = { alarmChannelId = it.filter { c -> c.isDigit() } },
            label = { Text("报警通道 ID") }, modifier = Modifier.fillMaxWidth())

        Spacer(Modifier.height(4.dp))
        Text("认证", style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(value = password, onValueChange = { password = it },
            label = { Text("SIP 密码") }, modifier = Modifier.fillMaxWidth(),
            visualTransformation = PasswordVisualTransformation())

        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                actions.onConfigSave(
                    SimConfig(
                        gbVersion = GbVersion.V2022,
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
                            username = deviceId,
                            password = password
                        ),
                        transport = state.config.transport,
                        keepaliveIntervalSeconds = state.config.keepaliveIntervalSeconds
                    )
                )
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("保存(下次 Connect 生效)") }
    }
}
