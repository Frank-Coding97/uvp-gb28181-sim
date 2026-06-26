package com.uvp.sim.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uvp.sim.config.AudioTransportType
import com.uvp.sim.ui.model.SipStateDto

/**
 * SIP 配置卡 — 7 字段内联编辑(IP+端口同行 / 服务器ID / 服务器域 / 设备ID / 注册密码 /
 * 信令传输 / 对讲传输)。注册后锁定。从 HomeScreen.kt 拆出。
 */
@Composable
internal fun SipConfigCard(state: AppUiState, actions: AppActions, onFeedback: (String) -> Unit) {
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
