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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.uvp.sim.ui.AppUiState
import com.uvp.sim.ui.AppActions
import com.uvp.sim.ui.UvpColor
import com.uvp.sim.domain.TimeSyncSource
import kotlinx.coroutines.delay
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * 能力中心 → 设备校时 子屏。展示 [com.uvp.sim.ui.model.ClockOffsetDto] 全部字段。
 *
 * - 平台基准时间(注册 200 OK Date 头解析得到)
 * - 本地系统时间(实时刷,1Hz)
 * - 偏移(平台 - 本地,注册时刻锁定)
 * - 原始 Date 头(等宽字体)
 * - 上次校时时刻
 *
 * 不修改手机系统时钟,仅显示。
 */
@Composable
fun ClockSyncScreen(state: AppUiState, actions: AppActions, onBack: () -> Unit) {
    com.uvp.sim.ui.PlatformBackHandler(enabled = true, onBack = onBack)
    val offset = state.clockOffset
    var nowMs by remember { mutableStateOf(Clock.System.now().toEpochMilliseconds()) }
    var ntpServer by remember(state.config.timeSync.ntpServer) {
        mutableStateOf(state.config.timeSync.ntpServer)
    }
    LaunchedEffect(Unit) {
        while (true) { delay(1_000); nowMs = Clock.System.now().toEpochMilliseconds() }
    }

    Column(modifier = Modifier.fillMaxSize().background(UvpColor.Bg)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "返回", tint = UvpColor.Text) }
            Spacer(Modifier.width(4.dp))
            Text("设备校时", color = UvpColor.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }

        Column(
            modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
        ) {
            NtpConfigCard(
                enabled = state.config.timeSync.ntpEnabled,
                server = ntpServer,
                onServerChange = { ntpServer = it },
                onEnabledChange = { enabled ->
                    actions.onConfigSave(state.config.copy(
                        timeSync = state.config.timeSync.copy(ntpEnabled = enabled),
                    ))
                },
                onApply = {
                    actions.onConfigSave(state.config.copy(
                        timeSync = state.config.timeSync.copy(ntpServer = ntpServer.trim()),
                    ))
                },
            )
            Spacer(Modifier.height(12.dp))
            if (!offset.isSynced) {
                UnSyncedHint()
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    InfoCard("当前校时源", when (offset.source) {
                        TimeSyncSource.NTP -> "NTP"
                        TimeSyncSource.SIP_DATE -> "SIP Date"
                        TimeSyncSource.NONE -> "未校时"
                    }, subtitle = if (offset.source == TimeSyncSource.NTP) {
                        "NTP 优先；失效自动回退 SIP Date"
                    } else {
                        "注册 200 OK Date 头"
                    })
                    InfoCard("平台基准时间", formatIsoLocal(offset.platformBaselineMs!!),
                        subtitle = "当前逻辑协议时钟基准")
                    InfoCard("当前对外设备时间", formatIsoLocal(offset.adjustedNowMs()),
                        subtitle = "= 平台基准 + 单调时钟流逝(对外 ISO 时间统一基于此)")
                    InfoCard("本地系统时间", formatIsoLocal(nowMs),
                        subtitle = "未经校准的手机墙钟,仅参考")
                    val deltaMs = offset.localOffsetMs() ?: 0L
                    InfoCard("校时偏移", formatOffsetMs(deltaMs),
                        subtitle = "平台 − 本地(正值=平台超前)")
                    InfoCard(
                        title = "校时来源原文",
                        value = offset.rawDateHeader.orEmpty(),
                        subtitle = if (offset.source == TimeSyncSource.NTP) "NTP 服务地址" else "RFC1123 / ISO8601",
                        monospace = true
                    )
                }
                Spacer(Modifier.height(12.dp))
                DisclaimerNote()
            }
            Spacer(Modifier.height(14.dp))
        }
    }
}

@Composable
private fun NtpConfigCard(
    enabled: Boolean,
    server: String,
    onServerChange: (String) -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onApply: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
        color = UvpColor.Surface,
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("NTP 优先", color = UvpColor.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text("关闭或请求失败时自动使用 SIP Date", color = UvpColor.TextHint, fontSize = 11.sp)
                }
                Switch(checked = enabled, onCheckedChange = onEnabledChange)
            }
            if (enabled) {
                OutlinedTextField(
                    value = server,
                    onValueChange = onServerChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("NTP 服务器") },
                    placeholder = { Text("ntp.aliyun.com") },
                    singleLine = true,
                )
                Button(
                    onClick = onApply,
                    enabled = server.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("应用 NTP 地址") }
            }
        }
    }
}

@Composable
private fun UnSyncedHint() {
    Box(
        modifier = Modifier.fillMaxWidth().padding(14.dp).clip(RoundedCornerShape(8.dp))
            .background(UvpColor.WarningBg).padding(14.dp)
    ) {
        Column {
            Text("尚未校时", color = UvpColor.Warning, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(
                "设备未注册，或 NTP 与平台 SIP Date 均不可用。注册成功后自动校时。",
                color = UvpColor.TextSecondary, fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun InfoCard(title: String, value: String, subtitle: String? = null, monospace: Boolean = false) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = UvpColor.Surface,
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(title, color = UvpColor.TextSecondary, fontSize = 11.sp)
            Spacer(Modifier.height(4.dp))
            Text(
                value, color = UvpColor.Text, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default
            )
            if (subtitle != null) {
                Spacer(Modifier.height(4.dp))
                Text(subtitle, color = UvpColor.TextHint, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun DisclaimerNote() {
    Text(
        "注:校时仅维护逻辑协议时钟,不修改手机系统时钟。内部日志与超时仍使用系统/单调时钟。",
        color = UvpColor.TextHint, fontSize = 11.sp,
        modifier = Modifier.padding(horizontal = 14.dp)
    )
}

internal fun formatIsoLocal(epochMs: Long): String {
    val ldt = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(TimeZone.currentSystemDefault())
    fun p2(v: Int) = v.toString().padStart(2, '0')
    return buildString {
        append(ldt.year.toString().padStart(4, '0')).append('-')
        append(p2(ldt.monthNumber)).append('-')
        append(p2(ldt.dayOfMonth)).append('T')
        append(p2(ldt.hour)).append(':')
        append(p2(ldt.minute)).append(':')
        append(p2(ldt.second))
    }
}

internal fun formatOffsetMs(ms: Long): String {
    val sign = if (ms >= 0) "+" else "-"
    val abs = if (ms < 0) -ms else ms
    return when {
        abs < 1_000 -> "${sign}${abs}ms"
        abs < 60_000 -> "${sign}${abs / 1000}.${(abs % 1000) / 100}s"
        else -> "${sign}${abs / 60_000}m${(abs % 60_000) / 1000}s"
    }
}
