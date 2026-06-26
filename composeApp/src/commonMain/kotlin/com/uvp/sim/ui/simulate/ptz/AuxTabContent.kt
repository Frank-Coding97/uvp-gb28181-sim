package com.uvp.sim.ui.simulate.ptz

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AcUnit
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.WaterDrop
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uvp.sim.gb28181.AuxFunction
import com.uvp.sim.ui.UvpColor
import com.uvp.sim.ui.model.DeviceControlDto
import com.uvp.sim.ui.simulate.useTickingNow

/**
 * 辅助 Tab — 5 个开关图标(雨刷 / 红外灯 / 加热 / 除雾 / 制冷).
 */
@Composable
internal fun AuxTabContent(state: DeviceControlDto) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (func in AuxFunction.entries) {
            val on = state.auxStates[func.index] == true
            val sinceMs = state.auxTimestamps[func.index]
            AuxToggle(
                func = func,
                on = on,
                sinceMs = sinceMs,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun AuxToggle(
    func: AuxFunction,
    on: Boolean,
    sinceMs: Long?,
    modifier: Modifier = Modifier,
) {
    val tint = if (on) UvpColor.Primary else UvpColor.TextHint
    val bg = if (on) UvpColor.PrimaryLight else UvpColor.Bg
    val border = if (on) UvpColor.Primary.copy(alpha = 0.4f) else UvpColor.BorderLight
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(8.dp))
            .padding(vertical = 10.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            auxIcon(func),
            contentDescription = func.displayName,
            tint = tint,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            func.displayName,
            fontSize = 10.sp,
            color = tint,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(2.dp))
        // 状态文案:运行中显示运行时长,关闭显示 OFF 或最近一次时长
        AuxStatusLine(on = on, sinceMs = sinceMs)
    }
}

/** 显示"已开 X 分钟" / "OFF" 等运行状态副文本,每秒刷新一次. */
@Composable
private fun AuxStatusLine(on: Boolean, sinceMs: Long?) {
    val nowMs = useTickingNow(intervalMs = 1000L)
    val text = when {
        on && sinceMs != null -> "已开 ${formatDuration(nowMs - sinceMs)}"
        on -> "ON"
        else -> "OFF"
    }
    Text(
        text,
        fontSize = 8.sp,
        color = if (on) UvpColor.PrimaryDark else UvpColor.TextHint,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
    )
}

/** 简短时长格式: <60s → 'Ns' / <60min → 'NmS' / 否则 'NhM' . */
private fun formatDuration(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return when {
        s < 60 -> "${s}s"
        s < 3600 -> "${s / 60}m${s % 60}"
        else -> "${s / 3600}h${(s % 3600) / 60}m"
    }
}

private fun auxIcon(func: AuxFunction): ImageVector = when (func) {
    AuxFunction.Wiper -> Icons.Outlined.WaterDrop
    AuxFunction.InfraredLight -> Icons.Outlined.Visibility
    AuxFunction.Heater -> Icons.Outlined.LocalFireDepartment
    AuxFunction.Defog -> Icons.Outlined.CleaningServices
    AuxFunction.Cooler -> Icons.Outlined.AcUnit
}
