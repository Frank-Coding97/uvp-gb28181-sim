package com.uvp.sim.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uvp.sim.sip.SipState

/**
 * 通道页 — M1 单通道展示 + 通道相关配置编辑。
 *
 * 第一节"真实通道":卡片显示通道名/ID/在线状态(原有)。
 * 第二节"通道配置":视频通道ID / 报警通道ID / 密码 / 心跳间隔(从原 HomeScreen 高级设置迁过来)。
 */
@Composable
fun ChannelScreen(state: AppUiState, actions: AppActions) {
    val toast = LocalToastHost.current
    val online = state.sip == SipState.Registered || state.sip == SipState.InCall
    val locked = state.sip == SipState.Registered || state.sip == SipState.InCall
    val scroll = rememberScrollState()

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(scroll).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        SectionLabel("真实通道 · 1")
        ChannelCard(
            name = "手机摄像头",
            id = state.config.device.videoChannelId,
            online = online
        )
        Spacer(Modifier.height(4.dp))
        SectionLabel("通道配置")
        ChannelConfigCard(
            state = state,
            locked = locked,
            onSave = { newCfg ->
                actions.onConfigSave(newCfg)
                toast.success("通道配置已保存")
            }
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        color = UvpColor.TextHint,
        modifier = Modifier.padding(start = 2.dp, bottom = 2.dp)
    )
}

@Composable
private fun ChannelCard(name: String, id: String, online: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(UvpColor.Surface, RoundedCornerShape(8.dp))
            .border(1.dp, UvpColor.Border, RoundedCornerShape(8.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(UvpColor.PrimaryLight, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Outlined.PhotoCamera,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = UvpColor.Primary
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(name, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = UvpColor.Text)
            Text(id, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = UvpColor.TextSecondary)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            val dot = if (online) UvpColor.Success else UvpColor.TextHint
            val label = if (online) "在线" else "离线"
            val labelColor = if (online) UvpColor.SuccessText else UvpColor.TextHint
            Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
            Spacer(Modifier.width(6.dp))
            Text(label, fontSize = 12.sp, color = labelColor)
        }
    }
}

@Composable
private fun ChannelConfigCard(
    state: AppUiState,
    locked: Boolean,
    onSave: (com.uvp.sim.config.SimConfig) -> Unit
) {
    var videoChannelId by remember(state.config) { mutableStateOf(state.config.device.videoChannelId) }
    var alarmChannelId by remember(state.config) { mutableStateOf(state.config.device.alarmChannelId) }
    var password by remember(state.config) { mutableStateOf(state.config.device.password) }
    var keepalive by remember(state.config) {
        mutableStateOf(state.config.keepaliveIntervalSeconds.toString())
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(UvpColor.Surface, RoundedCornerShape(8.dp))
            .border(1.dp, UvpColor.Border, RoundedCornerShape(8.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        InlineField("视频通道 ID", videoChannelId,
            { videoChannelId = it.filter { c -> c.isDigit() } },
            enabled = !locked)
        InlineField("报警通道 ID", alarmChannelId,
            { alarmChannelId = it.filter { c -> c.isDigit() } },
            enabled = !locked)
        InlineField("注册密码", password, { password = it }, password = true, enabled = !locked)
        InlineField("心跳间隔(秒)", keepalive,
            { keepalive = it.filter { c -> c.isDigit() } },
            keyboard = KeyboardType.Number, enabled = !locked)
        Spacer(Modifier.height(2.dp))
        Button(
            enabled = !locked,
            onClick = {
                onSave(
                    state.config.copy(
                        device = state.config.device.copy(
                            videoChannelId = videoChannelId,
                            alarmChannelId = alarmChannelId,
                            password = password
                        ),
                        keepaliveIntervalSeconds = keepalive.toIntOrNull()?.coerceIn(15, 600) ?: 60
                    )
                )
            },
            modifier = Modifier.fillMaxWidth().height(40.dp),
            shape = RoundedCornerShape(6.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = UvpColor.Primary,
                disabledContainerColor = UvpColor.Border
            )
        ) {
            Text(
                if (locked) "注销后修改" else "保存",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = if (locked) UvpColor.TextHint else Color.White
            )
        }
    }
}
