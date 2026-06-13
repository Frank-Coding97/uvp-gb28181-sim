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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uvp.sim.config.GbVersion
import com.uvp.sim.sip.SipState

/**
 * 设备配置 — 集中所有"设备级"参数,跟"通道""音视频"分开。
 *
 * 结构:
 *   [国标版本]   GbVersion 切换(影响 Catalog/DeviceInfo/DeviceStatus 输出形态)
 *   [基本信息]   设备名称 / 注册周期 / 心跳间隔 / 超时次数
 *   [出厂信息]   厂商 / 型号 / 固件 / 硬件版本(DeviceInfo §9.3.2 应答字段)
 *
 * 对讲传输方式留在 SIP 卡跟信令传输并排,便于一处编辑两个传输参数。
 *
 * 注册态全部 disabled,跟 SIP 卡和通道页保持一致(注销后再改)。
 */
@Composable
fun DeviceConfigScreen(state: AppUiState, actions: AppActions) {
    val toast = LocalToastHost.current
    val locked = state.sip == SipState.Registered || state.sip == SipState.InCall
    val scroll = rememberScrollState()

    var gbVersion by remember(state.config) { mutableStateOf(state.config.gbVersion) }
    var name by remember(state.config) { mutableStateOf(state.config.device.name) }
    var expires by remember(state.config) {
        mutableStateOf(state.config.expiresSeconds.toString())
    }
    var keepalive by remember(state.config) {
        mutableStateOf(state.config.keepaliveIntervalSeconds.toString())
    }
    var maxTimeouts by remember(state.config) {
        mutableStateOf(state.config.maxKeepaliveTimeouts.toString())
    }
    var manufacturer by remember(state.config) { mutableStateOf(state.config.device.manufacturer) }
    var model by remember(state.config) { mutableStateOf(state.config.device.model) }
    var firmware by remember(state.config) { mutableStateOf(state.config.device.firmware) }
    var hardwareVersion by remember(state.config) { mutableStateOf(state.config.device.hardwareVersion) }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(scroll).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        SectionLabel("国标版本")
        GroupCard {
            GbVersionSelector(
                selected = gbVersion,
                enabled = !locked,
                onSelect = { gbVersion = it }
            )
        }

        SectionLabel("基本信息")
        GroupCard {
            InlineEditableRow(
                label = "设备名称",
                value = name,
                enabled = !locked,
                keyboard = KeyboardType.Text
            ) { name = it }
            InlineEditableRow(
                label = "注册周期",
                value = expires,
                enabled = !locked,
                keyboard = KeyboardType.Number,
                trailing = { UnitSuffix("秒", !locked) }
            ) { expires = it.filter { c -> c.isDigit() } }
            InlineEditableRow(
                label = "心跳间隔",
                value = keepalive,
                enabled = !locked,
                keyboard = KeyboardType.Number,
                trailing = { UnitSuffix("秒", !locked) }
            ) { keepalive = it.filter { c -> c.isDigit() } }
            InlineEditableRow(
                label = "超时次数",
                value = maxTimeouts,
                enabled = !locked,
                keyboard = KeyboardType.Number,
                trailing = { UnitSuffix("次", !locked) }
            ) { maxTimeouts = it.filter { c -> c.isDigit() } }
        }

        SectionLabel("出厂信息 · DeviceInfo 应答字段")
        GroupCard {
            InlineEditableRow(
                label = "厂商",
                value = manufacturer,
                enabled = !locked,
                keyboard = KeyboardType.Text
            ) { manufacturer = it }
            InlineEditableRow(
                label = "型号",
                value = model,
                enabled = !locked,
                keyboard = KeyboardType.Text
            ) { model = it }
            InlineEditableRow(
                label = "固件版本",
                value = firmware,
                enabled = !locked,
                keyboard = KeyboardType.Text
            ) { firmware = it }
            InlineEditableRow(
                label = "硬件版本",
                value = hardwareVersion,
                enabled = !locked,
                keyboard = KeyboardType.Text
            ) { hardwareVersion = it }
        }

        Button(
            enabled = !locked,
            onClick = {
                if (name.isBlank()) {
                    toast.error("设备名称不能为空")
                    return@Button
                }
                if (manufacturer.isBlank() || model.isBlank()) {
                    toast.error("厂商 / 型号不能为空")
                    return@Button
                }
                actions.onConfigSave(
                    state.config.copy(
                        gbVersion = gbVersion,
                        device = state.config.device.copy(
                            name = name,
                            manufacturer = manufacturer,
                            model = model,
                            firmware = firmware,
                            hardwareVersion = hardwareVersion
                        ),
                        expiresSeconds = expires.toIntOrNull()?.coerceIn(60, 86_400) ?: 3600,
                        keepaliveIntervalSeconds = keepalive.toIntOrNull()?.coerceIn(15, 600) ?: 60,
                        maxKeepaliveTimeouts = maxTimeouts.toIntOrNull()?.coerceIn(1, 10) ?: 3
                    )
                )
                toast.success("设备配置已保存")
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

        Spacer(Modifier.height(2.dp))
        Text(
            "范围:注册周期 60–86400 秒;心跳间隔 15–600 秒;超时次数 1–10",
            fontSize = 10.sp,
            color = UvpColor.TextHint,
            modifier = Modifier.padding(horizontal = 4.dp)
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
private fun GroupCard(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(UvpColor.Surface, RoundedCornerShape(8.dp))
            .border(1.dp, UvpColor.Border, RoundedCornerShape(8.dp))
            .padding(horizontal = 14.dp, vertical = 4.dp)
    ) {
        content()
    }
}

@Composable
private fun GbVersionSelector(
    selected: GbVersion,
    enabled: Boolean,
    onSelect: (GbVersion) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        GbVersion.entries.forEach { v ->
            val isSel = v == selected
            val bg = when {
                !enabled -> UvpColor.Border
                isSel -> UvpColor.Primary
                else -> UvpColor.Surface
            }
            val fg = when {
                !enabled -> UvpColor.TextHint
                isSel -> Color.White
                else -> UvpColor.Text
            }
            val border = if (isSel) UvpColor.Primary else UvpColor.Border
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(36.dp)
                    .background(bg, RoundedCornerShape(6.dp))
                    .border(1.dp, border, RoundedCornerShape(6.dp))
                    .clickable(enabled = enabled) { onSelect(v) },
                contentAlignment = Alignment.Center
            ) {
                Text(v.label, fontSize = 12.sp, color = fg, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun UnitSuffix(text: String, enabled: Boolean) {
    Text(
        text,
        fontSize = 11.sp,
        color = if (enabled) UvpColor.TextHint else UvpColor.BorderLight,
        modifier = Modifier.padding(start = 6.dp)
    )
}
