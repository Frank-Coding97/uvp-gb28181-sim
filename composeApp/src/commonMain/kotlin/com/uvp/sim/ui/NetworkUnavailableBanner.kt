package com.uvp.sim.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uvp.sim.network.NetworkState

/**
 * 顶部网络不可用 banner。
 *
 * 显示规则:
 * - networkRuntimeState 是 [NetworkState.Unavailable] → 红色 banner + 跳转设置入口
 * - 其余状态 → 不渲染(0 高度,不占空间)
 *
 * 设计原因(plan U3):状态卡显示原因 + 主屏顶 banner 双重提醒,
 * 避免老板切到别的页就忘了网络当前不可用。
 */
@Composable
fun NetworkUnavailableBanner(
    runtime: NetworkState,
    onClick: () -> Unit,
) {
    if (runtime !is NetworkState.Unavailable) return

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(UvpColor.DangerBg)
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Outlined.WifiOff,
            contentDescription = null,
            tint = UvpColor.Danger,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "网络不可用 · ${runtime.reason}",
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = UvpColor.DangerText,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "去设置 →",
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = UvpColor.Danger
        )
    }
}
