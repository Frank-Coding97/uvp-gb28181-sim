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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
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
import com.uvp.sim.ui.UvpColor
import kotlinx.coroutines.delay
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
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
fun ClockSyncScreen(state: AppUiState, onBack: () -> Unit) {
    com.uvp.sim.ui.PlatformBackHandler(enabled = true, onBack = onBack)
    val offset = state.clockOffset
    var nowMs by remember { mutableStateOf(Clock.System.now().toEpochMilliseconds()) }
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

        if (!offset.isSynced) {
            UnSyncedHint()
        } else {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                InfoCard("平台基准时间", formatIsoLocal(offset.platformBaselineMs!!),
                    subtitle = "解析自注册 200 OK 的 Date 头")
                InfoCard("当前对外设备时间", formatIsoLocal(offset.adjustedNowMs()),
                    subtitle = "= 平台基准 + 单调时钟流逝(对外 ISO 时间统一基于此)")
                InfoCard("本地系统时间", formatIsoLocal(nowMs),
                    subtitle = "未经校准的手机墙钟,仅参考")
                val deltaMs = offset.localOffsetMs() ?: 0L
                InfoCard("校时偏移", formatOffsetMs(deltaMs),
                    subtitle = "平台 − 本地(正值=平台超前)")
                InfoCard(
                    title = "原始 Date 头",
                    value = offset.rawDateHeader.orEmpty(),
                    subtitle = "RFC1123 / ISO8601 双格式兼容",
                    monospace = true
                )
            }
            Spacer(Modifier.height(12.dp))
            DisclaimerNote()
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
                "设备未注册或平台 200 OK 未带 Date 头。注册成功后自动校时。",
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
        "注:校时仅记录平台与本地的偏移,不修改手机系统时钟。对外 SIP / MANSCDP 时间戳基于校准基准生成。",
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
